package com.ultrax26.recorder.calls

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.ultrax26.recorder.effects.EffectsRenderer
import com.ultrax26.recorder.recording.RecordingService
import com.ultrax26.recorder.settings.SettingsStore
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class CallPhase { IDLE, CONNECTING, READY, RINGING_IN, RINGING_OUT, IN_CALL, ENDED }

data class Participant(val peerId: String, val name: String, val connected: Boolean, val hasVideo: Boolean)
data class IncomingCall(val peerId: String, val name: String, val connectionId: String, val sdp: String, val roomKey: String?)
data class ChatMessage(val from: String, val text: String, val timeMs: Long)

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val myPeerId: String? = null,
    val inviteUrl: String? = null,
    val roomKey: String? = null,
    val participants: List<Participant> = emptyList(),
    val incoming: IncomingCall? = null,
    val micMuted: Boolean = false,
    val cameraOff: Boolean = false,
    val speaker: Boolean = true,
    val error: String? = null,
    val status: String = "",
    val chat: List<ChatMessage> = emptyList(),
    val startedAtMs: Long = 0L,
    val available: Boolean = false,
    val unreadChat: Int = 0,
) {
    val inCall: Boolean get() = phase == CallPhase.IN_CALL || phase == CallPhase.RINGING_OUT
}

/** Pure roster logic (unit-tested): who a newcomer should call so everyone ends up in a mesh. */
object Roster {
    fun forNewcomer(all: Collection<String>, newcomer: String, self: String): List<String> = all.filter { it != newcomer && it != self }
}

/**
 * Hosts and joins calls. Signaling is PeerJS-compatible; media is WebRTC; our video source is the
 * effects renderer output, so filters and backgrounds apply in calls too. Group calls are a mesh:
 * every guest receives the roster over a data channel from the host and calls the others directly.
 */
class CallManager(private val context: Context, private val settingsStore: SettingsStore) : PeerJsSignaling.Listener, PeerLink.Events {
    private val tag = "Calls"
    private companion object { const val RING_TIMEOUT_MS = 60_000L }
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    val state = MutableStateFlow(CallUiState())
    val remoteTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    var core: WebRtcCore? = null
        private set
    private var signaling: PeerJsSignaling? = null
    private val mediaLinks = HashMap<String, PeerLink>()   // remote peer id → media link
    private val dataLinks = HashMap<String, PeerLink>()    // remote peer id → data link (host ↔ guest)
    private val names = HashMap<String, String>()
    private var ringtone: Ringtone? = null
    private var renderer: EffectsRenderer? = null
    private var isHost = false
    private var myId: String? = null
    private var roomKey: String? = null
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** Tells the recording controller to keep the GL pipeline on (the call's video source). */
    var onPipelineWanted: ((Boolean) -> Unit)? = null

    private fun s() = settingsStore.current.calls
    private fun update(f: (CallUiState) -> CallUiState) { state.value = f(state.value) }
    private fun myName(): String = s().displayName.ifBlank { "UltraX user" }

    // ------------------------------------------------------------------------------------------
    // Renderer binding
    // ------------------------------------------------------------------------------------------

    /** Called whenever the recording controller (re)creates the GL renderer. */
    fun attachRenderer(r: EffectsRenderer?) = main.post {
        renderer = r
        val c = core
        if (r != null && c != null) r.setCallSurface(c.captureSurface)
    }

    private fun ensureCore(): WebRtcCore {
        core?.let { return it }
        val cs = s()
        val c = WebRtcCore(context, cs.sendWidth, cs.sendHeight, cs.sendFps)
        core = c
        renderer?.setCallSurface(c.captureSurface)
        onPipelineWanted?.invoke(true)
        return c
    }

    // ------------------------------------------------------------------------------------------
    // Signaling lifecycle
    // ------------------------------------------------------------------------------------------

    /** Connect to signaling with the stable address (or a random id) so the phone can be called. */
    fun goOnline() = main.post {
        if (signaling != null) return@post
        val cs = s()
        val id = InviteLinks.peerId(cs.myAddress)
        myId = id
        ensureCore()
        update { it.copy(phase = if (it.inCall) it.phase else CallPhase.CONNECTING, myPeerId = id, status = "Connecting to ${cs.signalingHost}…", error = null) }
        signaling = PeerJsSignaling(cs.signalingHost, cs.signalingPort, cs.signalingPath, cs.signalingKey, cs.signalingSecure, this).also { it.connect(id) }
    }

    fun goOffline() = main.post {
        if (state.value.inCall) return@post
        stopRinging()
        state.value.incoming?.let { inc -> signaling?.send("LEAVE", inc.peerId, buildJsonObject { }) }
        signaling?.close(); signaling = null
        isHost = false; roomKey = null
        update { it.copy(phase = CallPhase.IDLE, status = "", available = false, inviteUrl = null, incoming = null, roomKey = null) }
        releaseCore()
    }

    private fun releaseCore() {
        renderer?.setCallSurface(null)
        core?.release(); core = null
        onPipelineWanted?.invoke(false)
    }

    /** Host a call: make sure we are online and publish a link with a fresh room key. */
    fun hostCall() = main.post {
        roomKey = InviteLinks.randomToken(8)
        isHost = true
        goOnline()
        publishInvite()
        update { it.copy(roomKey = roomKey, status = if (it.phase == CallPhase.READY) "Share the link — waiting for someone to join" else it.status) }
    }

    private fun publishInvite() {
        val id = myId ?: return
        update { it.copy(inviteUrl = InviteLinks.build(s(), id, roomKey)) }
    }

    /** Call a peer address (another phone with the app, or a browser hosting a room). */
    fun callPeer(rawTarget: String, roomKeyForTarget: String? = null) = main.post {
        val target = InviteLinks.parse(rawTarget)?.let { it.to } ?: rawTarget.trim().let { if (it.startsWith("ux-")) it else "ux-" + InviteLinks.sanitizeAddress(it) }
        val key = InviteLinks.parse(rawTarget)?.key ?: roomKeyForTarget
        goOnline()
        pendingOutgoing = target to key
        update { it.copy(phase = CallPhase.RINGING_OUT, status = "Calling $target…", startedAtMs = 0L) }
        if (signaling?.connected == true) dialPending()
    }

    private var pendingOutgoing: Pair<String, String?>? = null

    private fun dialPending() {
        val (target, key) = pendingOutgoing ?: return
        pendingOutgoing = null
        startCallServices()
        val core = ensureCore()
        val meta = buildJsonObject { put("name", myName()); if (key != null) put("key", key); put("app", "ultrax26") }
        val link = newLink(core, target, "mc_${InviteLinks.randomToken(10)}", PeerLink.Kind.MEDIA, initiator = true)
        link.metadata = meta
        mediaLinks[target] = link
        names[target] = target
        link.start(meta)
        link.setMaxBitrate(s().maxBitrateKbps)
        refreshParticipants()
        main.postDelayed({
            if (state.value.phase == CallPhase.RINGING_OUT && mediaLinks[target] === link && link.connectionState != PeerConnection.PeerConnectionState.CONNECTED) {
                signaling?.send("LEAVE", target, buildJsonObject { })
                dropPeer(target)
                update { it.copy(status = "No answer from ${names[target] ?: target}") }
            }
        }, RING_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------------------------------
    // Incoming
    // ------------------------------------------------------------------------------------------

    fun accept() = main.post {
        val inc = state.value.incoming ?: return@post
        stopRinging()
        update { it.copy(incoming = null) }
        answerIncoming(inc)
    }

    fun decline() = main.post {
        stopRinging()
        val inc = state.value.incoming
        update { it.copy(incoming = null, phase = if (it.inCall) it.phase else CallPhase.READY, status = "Declined") }
        if (inc != null) signaling?.send("LEAVE", inc.peerId, buildJsonObject { })
    }

    private fun answerIncoming(inc: IncomingCall) {
        startCallServices()
        val core = ensureCore()
        names[inc.peerId] = inc.name
        val link = newLink(core, inc.peerId, inc.connectionId, PeerLink.Kind.MEDIA, initiator = false)
        mediaLinks[inc.peerId] = link
        link.acceptOffer(inc.sdp)
        link.setMaxBitrate(s().maxBitrateKbps)
        // Send the roster over a data channel so the newcomer can mesh with everyone else (host only).
        if (isHost) {
            val others = Roster.forNewcomer(mediaLinks.keys, inc.peerId, myId ?: "")
            val dl = newLink(core, inc.peerId, "dc_${InviteLinks.randomToken(10)}", PeerLink.Kind.DATA, initiator = true)
            dataLinks[inc.peerId] = dl
            dl.start(buildJsonObject { put("name", myName()); put("roster", buildJsonArray { others.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
            pendingRoster[inc.peerId] = others
        }
        update { it.copy(phase = CallPhase.IN_CALL, status = "Connecting to ${inc.name}…", startedAtMs = if (it.startedAtMs == 0L) Clock.bootMs() else it.startedAtMs) }
        refreshParticipants()
    }

    private val pendingRoster = HashMap<String, List<String>>()

    private fun newLink(core: WebRtcCore, peer: String, connectionId: String, kind: PeerLink.Kind, initiator: Boolean): PeerLink =
        PeerLink(core, peer, connectionId, kind, initiator, core.iceServers(s().iceServers), { type, dst, payload -> signaling?.send(type, dst, payload) }, this)

    // ------------------------------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------------------------------

    fun toggleMic() = main.post { val on = state.value.micMuted; core?.setMicEnabled(on); update { it.copy(micMuted = !on) } }
    fun toggleCamera() = main.post { val off = state.value.cameraOff; core?.setVideoEnabled(off); update { it.copy(cameraOff = !off) } }
    fun toggleSpeaker() = main.post { val sp = !state.value.speaker; audio.isSpeakerphoneOn = sp; update { it.copy(speaker = sp) } }

    fun markChatRead() = main.post { update { it.copy(unreadChat = 0) } }

    fun sendChat(text: String) = main.post {
        val msg = buildJsonObject { put("type", "chat"); put("name", myName()); put("text", text) }.toString()
        dataLinks.values.forEach { it.sendData(msg) }
        update { it.copy(chat = it.chat + ChatMessage("You", text, Clock.wallMs())) }
    }

    fun hangUp() = main.post {
        stopRinging()
        val bye = buildJsonObject { put("type", "bye") }.toString()
        dataLinks.values.forEach { it.sendData(bye) }
        mediaLinks.keys.forEach { peer -> signaling?.send("LEAVE", peer, buildJsonObject { }) }
        mediaLinks.values.forEach { it.close() }; mediaLinks.clear()
        dataLinks.values.forEach { it.close() }; dataLinks.clear()
        remoteTracks.value = emptyMap()
        pendingOutgoing = null
        isHost = false
        stopCallServices()
        update { it.copy(phase = if (signaling != null) CallPhase.READY else CallPhase.IDLE, participants = emptyList(), incoming = null, status = "Call ended", startedAtMs = 0L, chat = emptyList(), roomKey = null, unreadChat = 0) }
        roomKey = null
        publishInvite()
    }

    fun shutdown() { hangUp(); main.post { signaling?.close(); signaling = null; releaseCore(); update { CallUiState() } } }

    private fun startCallServices() {
        try { audio.mode = AudioManager.MODE_IN_COMMUNICATION; audio.isSpeakerphoneOn = state.value.speaker } catch (_: Throwable) { }
        RecordingService.startCall(context)
    }

    private fun stopCallServices() {
        try { audio.mode = AudioManager.MODE_NORMAL; audio.isSpeakerphoneOn = false } catch (_: Throwable) { }
        RecordingService.stopCall(context)
    }

    private fun startRinging() {
        if (!s().ringtone) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also { it.play() }
        } catch (t: Throwable) { UxLog.w(tag, "ringtone: ${t.message}") }
    }

    private fun stopRinging() { try { ringtone?.stop() } catch (_: Throwable) { }; ringtone = null }

    private fun refreshParticipants() {
        val list = mediaLinks.map { (id, link) -> Participant(id, names[id] ?: id, link.connectionState == PeerConnection.PeerConnectionState.CONNECTED, link.remoteVideo != null) }
        update { it.copy(participants = list) }
    }

    // ------------------------------------------------------------------------------------------
    // Signaling callbacks
    // ------------------------------------------------------------------------------------------

    override fun onOpen(peerId: String) = main.post {
        update { it.copy(phase = if (it.inCall) it.phase else CallPhase.READY, status = if (roomKey != null) "Share the link — waiting for someone to join" else "Online as $peerId", available = true, error = null) }
        publishInvite()
        if (pendingOutgoing != null) dialPending()
    }.let { }

    override fun onMessage(msg: SignalMessage) = main.post { handleSignal(msg) }.let { }

    private fun handleSignal(msg: SignalMessage) {
        val src = msg.src ?: return
        val payload = msg.payload
        when (msg.type) {
            "OFFER" -> {
                payload ?: return
                val kind = PeerJsCodec.kindOf(payload)
                val connId = PeerJsCodec.connectionIdOf(payload)
                val sdp = PeerJsCodec.sdpOf(payload) ?: return
                val meta = PeerJsCodec.metadataOf(payload)
                val name = meta?.get("name")?.jsonPrimitive?.content ?: src
                val key = meta?.get("key")?.jsonPrimitive?.content
                names[src] = name
                if (kind == "data") {
                    val core = ensureCore()
                    val dl = newLink(core, src, connId, PeerLink.Kind.DATA, initiator = false)
                    dataLinks[src] = dl
                    dl.acceptOffer(sdp)
                    meta?.get("roster")?.jsonArray?.let { arr -> meshWith(arr) }
                    return
                }
                val busy = state.value.inCall
                val cs = s()
                if (mediaLinks.size >= cs.maxParticipants - 1) { UxLog.w(tag, "call full, ignoring $src"); return }
                val keyOk = roomKey != null && key == roomKey
                if (!busy && !keyOk) {
                    // Someone calling my address: ring.
                    if (state.value.incoming != null) return
                    update { it.copy(phase = CallPhase.RINGING_IN, incoming = IncomingCall(src, name, connId, sdp, key), status = "Incoming call from $name") }
                    startRinging()
                } else {
                    // A guest joining my room (key matches) or a mesh peer while in a call: auto-answer.
                    answerIncoming(IncomingCall(src, name, connId, sdp, key))
                }
            }
            "ANSWER" -> {
                payload ?: return
                val sdp = PeerJsCodec.sdpOf(payload) ?: return
                val kind = PeerJsCodec.kindOf(payload)
                val link = if (kind == "data") dataLinks[src] else mediaLinks[src]
                link?.onRemoteAnswer(sdp)
                if (kind == "media") update { it.copy(phase = CallPhase.IN_CALL, status = "Connecting…", startedAtMs = if (it.startedAtMs == 0L) Clock.bootMs() else it.startedAtMs) }
            }
            "CANDIDATE" -> {
                payload ?: return
                val (cand, mid, idx) = PeerJsCodec.candidateOf(payload) ?: return
                val kind = PeerJsCodec.kindOf(payload)
                (if (kind == "data") dataLinks[src] else mediaLinks[src])?.onRemoteCandidate(cand, mid, idx)
            }
            "LEAVE", "EXPIRE" -> {
                val target = if (msg.type == "EXPIRE") msg.dst ?: src else src
                if (state.value.incoming?.peerId == target) { stopRinging(); update { it.copy(incoming = null, phase = if (it.inCall) it.phase else CallPhase.READY, status = "Missed call from ${names[target] ?: target}") } }
                dropPeer(target)
                if (msg.type == "EXPIRE" && state.value.phase == CallPhase.RINGING_OUT) update { it.copy(phase = CallPhase.READY, status = "$target is not online", error = "No one is listening at that address") }
            }
        }
    }

    /** Newcomer side: the host told us who else is in the room; call each of them. */
    private fun meshWith(roster: JsonArray) {
        val core = ensureCore()
        roster.forEach { el ->
            val peer = el.jsonPrimitive.content
            if (peer == myId || mediaLinks.containsKey(peer)) return@forEach
            val link = newLink(core, peer, "mc_${InviteLinks.randomToken(10)}", PeerLink.Kind.MEDIA, initiator = true)
            mediaLinks[peer] = link
            names[peer] = names[peer] ?: peer
            link.start(buildJsonObject { put("name", myName()); roomKey?.let { put("key", it) }; put("mesh", true) })
            link.setMaxBitrate(s().maxBitrateKbps)
        }
        refreshParticipants()
    }

    private fun dropPeer(peer: String) {
        mediaLinks.remove(peer)?.close()
        dataLinks.remove(peer)?.close()
        remoteTracks.value = remoteTracks.value - peer
        refreshParticipants()
        if (mediaLinks.isEmpty() && (state.value.phase == CallPhase.IN_CALL || state.value.phase == CallPhase.RINGING_OUT)) {
            val wasRinging = state.value.phase == CallPhase.RINGING_OUT
            stopCallServices()
            isHost = false
            update { it.copy(phase = if (signaling != null) CallPhase.READY else CallPhase.IDLE, status = if (wasRinging) "${names[peer] ?: peer} did not answer" else "Call ended", startedAtMs = 0L, chat = emptyList(), unreadChat = 0) }
        }
    }

    override fun onClosed(reason: String) = main.post { update { it.copy(available = false, status = "Signaling closed: $reason") }; signaling = null }.let { }
    override fun onError(message: String) = main.post { update { it.copy(error = message, status = message, phase = if (it.inCall) it.phase else CallPhase.IDLE, available = false) } }.let { }

    // ------------------------------------------------------------------------------------------
    // PeerLink events
    // ------------------------------------------------------------------------------------------

    override fun onRemoteVideo(link: PeerLink, track: VideoTrack?) = main.post {
        if (track != null) remoteTracks.value = remoteTracks.value + (link.remotePeerId to track) else remoteTracks.value = remoteTracks.value - link.remotePeerId
        refreshParticipants()
    }.let { }

    override fun onConnectionState(link: PeerLink, st: PeerConnection.PeerConnectionState) = main.post {
        when (st) {
            PeerConnection.PeerConnectionState.CONNECTED -> if (link.kind == PeerLink.Kind.MEDIA) update { it.copy(phase = CallPhase.IN_CALL, status = "Connected", error = null, startedAtMs = if (it.startedAtMs == 0L) Clock.bootMs() else it.startedAtMs) }
            PeerConnection.PeerConnectionState.DISCONNECTED -> if (link.kind == PeerLink.Kind.MEDIA) update { it.copy(status = "Reconnecting to ${names[link.remotePeerId] ?: link.remotePeerId}…") }
            PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> if (link.kind == PeerLink.Kind.MEDIA) dropPeer(link.remotePeerId)
            else -> { }
        }
        refreshParticipants()
    }.let { }

    override fun onData(link: PeerLink, text: String) = main.post {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "chat" -> {
                    val name = obj["name"]?.jsonPrimitive?.content ?: names[link.remotePeerId] ?: link.remotePeerId
                    val body = obj["text"]?.jsonPrimitive?.content ?: ""
                    update { it.copy(chat = it.chat + ChatMessage(name, body, Clock.wallMs()), unreadChat = it.unreadChat + 1) }
                    if (isHost) { val relay = buildJsonObject { put("type", "chat"); put("name", name); put("text", body) }.toString(); dataLinks.filterKeys { it != link.remotePeerId }.values.forEach { it.sendData(relay) } }
                }
                "roster" -> obj["peers"]?.jsonArray?.let { meshWith(it) }
                "name" -> { names[link.remotePeerId] = obj["name"]?.jsonPrimitive?.content ?: link.remotePeerId; refreshParticipants() }
                "bye" -> dropPeer(link.remotePeerId)
            }
        } catch (t: Throwable) { UxLog.w(tag, "data: ${t.message}") }
    }.let { }

    override fun onDataOpen(link: PeerLink) = main.post {
        link.sendData(buildJsonObject { put("type", "name"); put("name", myName()) }.toString())
        pendingRoster.remove(link.remotePeerId)?.let { others ->
            link.sendData(buildJsonObject { put("type", "roster"); put("peers", buildJsonArray { others.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }.toString())
        }
    }.let { }

    @Suppress("unused") private fun unusedSurface(s: Surface) = s
}
