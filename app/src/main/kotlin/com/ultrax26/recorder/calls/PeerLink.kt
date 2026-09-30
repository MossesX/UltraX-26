package com.ultrax26.recorder.calls

import com.ultrax26.recorder.util.UxLog
import kotlinx.serialization.json.JsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * One RTCPeerConnection, mirroring how PeerJS works: a MediaConnection and a DataConnection each get
 * their own peer connection. Signaling goes through [send] with PeerJS payloads.
 */
class PeerLink(
    private val core: WebRtcCore,
    val remotePeerId: String,
    val connectionId: String,
    val kind: Kind,
    val initiator: Boolean,
    iceServers: List<PeerConnection.IceServer>,
    private val send: (type: String, dst: String, payload: JsonObject) -> Unit,
    private val events: Events,
) {
    enum class Kind(val wire: String) { MEDIA("media"), DATA("data") }

    interface Events {
        fun onRemoteVideo(link: PeerLink, track: VideoTrack?)
        fun onConnectionState(link: PeerLink, state: PeerConnection.PeerConnectionState)
        fun onData(link: PeerLink, text: String)
        fun onDataOpen(link: PeerLink)
    }

    private val tag = "PeerLink"
    private val pc: PeerConnection
    private var dataChannel: DataChannel? = null
    private val pendingCandidates = ArrayList<IceCandidate>()
    @Volatile private var remoteSet = false
    @Volatile var remoteVideo: VideoTrack? = null
        private set
    @Volatile var connectionState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW
        private set
    var metadata: JsonObject? = null

    // Declared before init: the constructor hands it to createPeerConnection.
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) { }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { UxLog.d(tag, "$remotePeerId ice=$state") }
        override fun onIceConnectionReceivingChange(receiving: Boolean) { }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { }
        override fun onIceCandidate(candidate: IceCandidate) {
            send("CANDIDATE", remotePeerId, PeerJsCodec.candidate(connectionId, kind.wire, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) { }
        override fun onAddStream(stream: MediaStream) { }
        override fun onRemoveStream(stream: MediaStream) { }
        override fun onDataChannel(dc: DataChannel) { attachDataChannel(dc) }
        override fun onRenegotiationNeeded() { }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { connectionState = newState; events.onConnectionState(this@PeerLink, newState) }
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) { handleRemoteTrack(receiver) }
        override fun onTrack(transceiver: RtpTransceiver) { handleRemoteTrack(transceiver.receiver) }
    }

    init {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = core.factory.createPeerConnection(config, observer) ?: throw IllegalStateException("createPeerConnection returned null")
    }

    /** Initiator side: attach tracks / create the data channel and send the offer. */
    fun start(metadata: JsonObject? = null) {
        if (!initiator) return
        if (kind == Kind.MEDIA) addLocalTracks()
        else {
            val dc = pc.createDataChannel(connectionId, DataChannel.Init().apply { ordered = true })
            attachDataChannel(dc)
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                val payload = if (kind == Kind.MEDIA) PeerJsCodec.mediaOffer(connectionId, desc.description, metadata) else PeerJsCodec.dataOffer(connectionId, desc.description, metadata)
                send("OFFER", remotePeerId, payload)
            }
            override fun onCreateFailure(error: String) { UxLog.w(tag, "createOffer failed: $error") }
        }, org.webrtc.MediaConstraints())
    }

    /** Answerer side: apply the remote offer, attach our tracks, send the answer. */
    fun acceptOffer(sdp: String) {
        if (kind == Kind.MEDIA) addLocalTracks()
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(SdpObserverAdapter(), desc)
                        send("ANSWER", remotePeerId, PeerJsCodec.answer(connectionId, kind.wire, desc.description))
                    }
                    override fun onCreateFailure(error: String) { UxLog.w(tag, "createAnswer failed: $error") }
                }, org.webrtc.MediaConstraints())
            }
            override fun onSetFailure(error: String) { UxLog.w(tag, "setRemote(offer) failed: $error") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onRemoteAnswer(sdp: String) {
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() { remoteSet = true; flushCandidates() }
            override fun onSetFailure(error: String) { UxLog.w(tag, "setRemote(answer) failed: $error") }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val c = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        synchronized(pendingCandidates) { if (!remoteSet) { pendingCandidates += c; return } }
        pc.addIceCandidate(c)
    }

    private fun flushCandidates() {
        val list = synchronized(pendingCandidates) { val l = pendingCandidates.toList(); pendingCandidates.clear(); l }
        list.forEach { pc.addIceCandidate(it) }
    }

    private fun addLocalTracks() {
        val streamIds = listOf("ux-stream")
        pc.addTrack(core.videoTrack, streamIds)
        pc.addTrack(core.audioTrack, streamIds)
    }

    fun setMaxBitrate(kbps: Int) {
        try {
            for (sender in pc.senders) {
                if (sender.track()?.kind() != "video") continue
                val params = sender.parameters
                for (enc in params.encodings) enc.maxBitrateBps = kbps * 1000
                sender.parameters = params
            }
        } catch (t: Throwable) { UxLog.w(tag, "bitrate: ${t.message}") }
    }

    private fun attachDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) { }
            override fun onStateChange() { if (dc.state() == DataChannel.State.OPEN) events.onDataOpen(this@PeerLink) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // PeerJS "json" serialization sends UTF-8 JSON as *binary* frames; other peers may send text. Accept both.
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val text = try { String(bytes, StandardCharsets.UTF_8) } catch (_: Throwable) { return }
                if (text.isNotBlank() && (text[0] == '{' || text[0] == '[' || text[0] == '"')) events.onData(this@PeerLink, text)
            }
        })
    }

    fun sendData(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        // Binary frame: PeerJS's JSON serializer decodes with TextDecoder, which rejects text frames.
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), true))
    }

    val isDataOpen: Boolean get() = dataChannel?.state() == DataChannel.State.OPEN

    fun close() {
        try { dataChannel?.close(); dataChannel?.dispose() } catch (_: Throwable) { }
        dataChannel = null
        try { pc.close(); pc.dispose() } catch (_: Throwable) { }
    }

    private fun handleRemoteTrack(receiver: RtpReceiver) {
        val track = receiver.track() ?: return
        if (track.kind() == "video" && track is VideoTrack && remoteVideo !== track) { remoteVideo = track; events.onRemoteVideo(this, track) }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) { }
        override fun onSetSuccess() { }
        override fun onCreateFailure(error: String) { UxLog.w("PeerLink", "sdp create failure: $error") }
        override fun onSetFailure(error: String) { UxLog.w("PeerLink", "sdp set failure: $error") }
    }
}
