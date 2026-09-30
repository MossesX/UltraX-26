package com.ultrax26.recorder.calls

import kotlinx.serialization.Serializable

@Serializable
data class IceServerSpec(val urls: String, val username: String = "", val credential: String = "")

/**
 * Video-call configuration. Defaults use free public relays so calls work without any setup:
 * PeerJS Cloud (0.peerjs.com) for signaling, Google STUN, and the Open Relay Project TURN servers.
 * All of them can be replaced by your own (see server/README.md).
 */
@Serializable
data class CallSettings(
    val displayName: String = "",
    val signalingHost: String = "0.peerjs.com",
    val signalingPort: Int = 443,
    val signalingPath: String = "/",
    val signalingKey: String = "peerjs",
    val signalingSecure: Boolean = true,
    /** Where the browser client is hosted (docs/CALLS.md explains GitHub Pages). */
    val webClientUrl: String = "https://mossesx.github.io/UltraX-26/call/",
    /** Stable personal address so people can call you any time; blank = random id per session. */
    val myAddress: String = "",
    val availableForIncoming: Boolean = true,
    val iceServers: List<IceServerSpec> = listOf(
        IceServerSpec("stun:stun.l.google.com:19302"),
        IceServerSpec("stun:stun1.l.google.com:19302"),
        IceServerSpec("turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"),
        IceServerSpec("turn:openrelay.metered.ca:443", "openrelayproject", "openrelayproject"),
        IceServerSpec("turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"),
    ),
    val sendWidth: Int = 1280,
    val sendHeight: Int = 720,
    val sendFps: Int = 30,
    val maxBitrateKbps: Int = 2500,
    val speakerphone: Boolean = true,
    val ringtone: Boolean = true,
    val effectsInCalls: Boolean = true,
    val maxParticipants: Int = 6,
)
