package com.ultrax26.recorder.calls

import com.ultrax26.recorder.util.UxLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

/** One signaling message as relayed by a PeerJS server. */
data class SignalMessage(val type: String, val src: String?, val dst: String?, val payload: JsonObject?)

/**
 * Minimal client for the PeerJS signaling protocol (compatible with PeerJS Cloud and any
 * self-hosted `peerjs-server`). Messages are relayed verbatim between peer ids; media flows
 * peer-to-peer over WebRTC.
 */
class PeerJsSignaling(
    private val host: String, private val port: Int, private val path: String, private val key: String, private val secure: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(peerId: String)
        fun onMessage(msg: SignalMessage)
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    private val tag = "Signaling"
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private var heartbeat: Timer? = null
    @Volatile private var closedByUs = false
    @Volatile var peerId: String? = null
        private set
    @Volatile var connected = false
        private set
    private val json = Json { ignoreUnknownKeys = true }

    fun url(id: String): String {
        val p = if (path.isBlank()) "/" else if (path.endsWith("/")) path else "$path/"
        val token = InviteLinks.randomToken(12)
        return "${if (secure) "wss" else "ws"}://$host:$port${p}peerjs?key=$key&id=$id&token=$token&version=1.5.4"
    }

    fun connect(id: String) {
        closedByUs = false
        peerId = id
        val req = Request.Builder().url(url(id)).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { UxLog.i(tag, "socket open") }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false; stopHeartbeat(); if (!closedByUs) listener.onClosed("closed ($code) $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected = false; stopHeartbeat(); if (!closedByUs) listener.onError("signaling failed: ${t.message ?: t.javaClass.simpleName}") }
        })
    }

    private fun handle(text: String) {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (t: Throwable) { UxLog.w(tag, "bad message: $text"); return }
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "OPEN" -> { connected = true; startHeartbeat(); listener.onOpen(peerId ?: "") }
            "ERROR" -> listener.onError(obj["payload"]?.jsonObject?.get("msg")?.jsonPrimitive?.content ?: "signaling error")
            "ID-TAKEN" -> listener.onError("This call address is already in use")
            "INVALID-KEY" -> listener.onError("Invalid signaling key")
            "HEARTBEAT" -> { }
            else -> listener.onMessage(SignalMessage(type, obj["src"]?.jsonPrimitive?.content, obj["dst"]?.jsonPrimitive?.content, obj["payload"] as? JsonObject))
        }
    }

    fun send(type: String, dst: String, payload: JsonObject) {
        val msg = buildJsonObject { put("type", type); put("src", peerId ?: ""); put("dst", dst); put("payload", payload) }
        val ok = socket?.send(msg.toString()) ?: false
        if (!ok) UxLog.w(tag, "send failed ($type → $dst)")
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = Timer("ux-signal-hb", true).also { t -> t.schedule(object : TimerTask() { override fun run() { socket?.send("{\"type\":\"HEARTBEAT\"}") } }, 5000, 5000) }
    }

    private fun stopHeartbeat() { heartbeat?.cancel(); heartbeat = null }

    fun close() {
        closedByUs = true
        stopHeartbeat()
        try { socket?.close(1000, "bye") } catch (_: Throwable) { }
        socket = null; connected = false
    }
}

/** PeerJS payload helpers (shared by the Android client and unit tests). */
object PeerJsCodec {
    const val BROWSER = "UltraX26"

    fun mediaOffer(connectionId: String, sdp: String, metadata: JsonObject?): JsonObject = buildJsonObject {
        put("sdp", buildJsonObject { put("type", "offer"); put("sdp", sdp) })
        put("type", "media"); put("connectionId", connectionId); put("browser", BROWSER)
        if (metadata != null) put("metadata", metadata)
    }

    fun dataOffer(connectionId: String, sdp: String, metadata: JsonObject?): JsonObject = buildJsonObject {
        put("sdp", buildJsonObject { put("type", "offer"); put("sdp", sdp) })
        put("type", "data"); put("connectionId", connectionId); put("browser", BROWSER)
        put("label", connectionId); put("reliable", true); put("serialization", "json")
        if (metadata != null) put("metadata", metadata)
    }

    fun answer(connectionId: String, kind: String, sdp: String): JsonObject = buildJsonObject {
        put("sdp", buildJsonObject { put("type", "answer"); put("sdp", sdp) })
        put("type", kind); put("connectionId", connectionId); put("browser", BROWSER)
    }

    fun candidate(connectionId: String, kind: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): JsonObject = buildJsonObject {
        put("candidate", buildJsonObject { put("candidate", candidate); put("sdpMid", sdpMid ?: "0"); put("sdpMLineIndex", sdpMLineIndex) })
        put("type", kind); put("connectionId", connectionId)
    }

    fun sdpOf(payload: JsonObject): String? = payload["sdp"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content
    fun kindOf(payload: JsonObject): String = payload["type"]?.jsonPrimitive?.content ?: "media"
    fun connectionIdOf(payload: JsonObject): String = payload["connectionId"]?.jsonPrimitive?.content ?: ""
    fun metadataOf(payload: JsonObject): JsonObject? = payload["metadata"] as? JsonObject
    fun candidateOf(payload: JsonObject): Triple<String, String?, Int>? {
        val c = payload["candidate"]?.jsonObject ?: return null
        val cand = c["candidate"]?.jsonPrimitive?.content ?: return null
        return Triple(cand, c["sdpMid"]?.jsonPrimitive?.content, c["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
    }
}
