package com.ultrax26.recorder.calls

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/** Builds and parses the shareable call links opened by people who do not have the app. */
object InviteLinks {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private val rnd = SecureRandom()

    fun randomToken(len: Int): String = buildString { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    fun sanitizeAddress(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').take(32)

    fun peerId(address: String): String = if (address.isBlank()) "ux-${randomToken(10)}" else "ux-${sanitizeAddress(address)}"

    data class Invite(val to: String, val key: String?, val host: String?, val port: Int?, val path: String?, val apiKey: String?, val secure: Boolean?, val name: String?)

    fun build(s: CallSettings, hostPeerId: String, roomKey: String?, includeSignaling: Boolean = true): String {
        val base = s.webClientUrl.trim().ifEmpty { "https://mossesx.github.io/UltraX-26/call/" }
        val q = ArrayList<String>()
        q += "to=" + enc(hostPeerId)
        if (!roomKey.isNullOrBlank()) q += "k=" + enc(roomKey)
        if (includeSignaling) {
            val d = CallSettings()
            if (s.signalingHost != d.signalingHost) q += "h=" + enc(s.signalingHost)
            if (s.signalingPort != d.signalingPort) q += "p=" + s.signalingPort
            if (s.signalingPath != d.signalingPath) q += "path=" + enc(s.signalingPath)
            if (s.signalingKey != d.signalingKey) q += "key=" + enc(s.signalingKey)
            if (s.signalingSecure != d.signalingSecure) q += "s=" + (if (s.signalingSecure) "1" else "0")
        }
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + q.joinToString("&")
    }

    fun parse(url: String): Invite? {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return null
        val params = url.substring(qIdx + 1).substringBefore('#').split('&').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i <= 0) null else dec(kv.substring(0, i)) to dec(kv.substring(i + 1))
        }.toMap()
        val to = params["to"] ?: return null
        return Invite(to, params["k"], params["h"], params["p"]?.toIntOrNull(), params["path"], params["key"], params["s"]?.let { it == "1" || it == "true" }, params["n"])
    }

    fun inviteText(url: String, name: String): String =
        (if (name.isBlank()) "Join my video call" else "$name invites you to a video call") + " — open this link in any browser, no app needed:\n$url"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }
}
