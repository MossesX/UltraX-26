package com.ultrax26.recorder.calls

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import com.ultrax26.recorder.util.UxLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC factory, local tracks and the capture surface. The effects renderer draws the processed
 * camera image into [captureSurface]; a SurfaceTextureHelper turns that into WebRTC video frames.
 */
class WebRtcCore(context: Context, width: Int, height: Int, fps: Int) {
    val eglBase: EglBase = EglBase.create()
    val factory: PeerConnectionFactory
    val audioSource: AudioSource
    val audioTrack: AudioTrack
    val videoSource: VideoSource
    val videoTrack: VideoTrack
    private val helper: SurfaceTextureHelper
    val captureSurface: Surface

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(false).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        audioTrack = factory.createAudioTrack("ux-audio", audioSource)
        videoSource = factory.createVideoSource(false)
        helper = SurfaceTextureHelper.create("ux-call-capture", eglBase.eglBaseContext)
        helper.setTextureSize(width, height)
        helper.startListening(VideoSink { frame -> videoSource.capturerObserver.onFrameCaptured(frame) })
        videoSource.capturerObserver.onCapturerStarted(true)
        videoSource.adaptOutputFormat(width, height, fps)
        captureSurface = Surface(helper.surfaceTexture)
        videoTrack = factory.createVideoTrack("ux-video", videoSource)
        UxLog.i("WebRtc", "core ready ${width}x$height@$fps")
    }

    fun iceServers(specs: List<IceServerSpec>): List<PeerConnection.IceServer> = specs.mapNotNull { s ->
        try {
            val b = PeerConnection.IceServer.builder(s.urls.trim())
            if (s.username.isNotBlank()) b.setUsername(s.username).setPassword(s.credential)
            b.createIceServer()
        } catch (t: Throwable) { UxLog.w("WebRtc", "bad ICE server ${s.urls}: ${t.message}"); null }
    }

    fun setMicEnabled(on: Boolean) { audioTrack.setEnabled(on) }
    fun setVideoEnabled(on: Boolean) { videoTrack.setEnabled(on) }

    fun release() {
        try { videoSource.capturerObserver.onCapturerStopped() } catch (_: Throwable) { }
        try { helper.stopListening(); helper.dispose() } catch (_: Throwable) { }
        try { captureSurface.release() } catch (_: Throwable) { }
        try { videoTrack.dispose(); audioTrack.dispose(); videoSource.dispose(); audioSource.dispose() } catch (_: Throwable) { }
        try { factory.dispose() } catch (_: Throwable) { }
        try { eglBase.release() } catch (_: Throwable) { }
    }
}
