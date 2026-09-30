package com.ultrax26.recorder

import com.ultrax26.recorder.calls.CallSettings
import com.ultrax26.recorder.calls.IceServerSpec
import com.ultrax26.recorder.calls.InviteLinks
import com.ultrax26.recorder.calls.PeerJsCodec
import com.ultrax26.recorder.calls.Roster
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.util.UxJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    @Test fun `random tokens are url safe and unique`() {
        val tokens = (1..200).map { InviteLinks.randomToken(10) }.toSet()
        assertEquals(200, tokens.size)
        tokens.forEach { t -> assertEquals(10, t.length); assertTrue(t.all { it in "abcdefghjkmnpqrstuvwxyz23456789" }) }
    }

    @Test fun `addresses are sanitized into stable peer ids`() {
        assertEquals("sam-jones", InviteLinks.sanitizeAddress("  Sam Jones! "))
        assertEquals("ux-sam-jones", InviteLinks.peerId("Sam Jones"))
        assertEquals(32, InviteLinks.sanitizeAddress("a".repeat(50)).length)
        assertTrue(InviteLinks.peerId("").startsWith("ux-"))
        assertEquals(13, InviteLinks.peerId("").length)
    }

    @Test fun `invite links omit default signaling and round-trip`() {
        val s = CallSettings()
        val url = InviteLinks.build(s, "ux-host1", "k3y2345")
        assertEquals("https://mossesx.github.io/UltraX-26/call/?to=ux-host1&k=k3y2345", url)
        val inv = InviteLinks.parse(url)!!
        assertEquals("ux-host1", inv.to); assertEquals("k3y2345", inv.key)
        assertNull(inv.host); assertNull(inv.port); assertNull(inv.apiKey); assertNull(inv.secure)
    }

    @Test fun `invite links carry custom signaling`() {
        val s = CallSettings(signalingHost = "calls.example.com", signalingPort = 9000, signalingPath = "/sig/", signalingKey = "my key", signalingSecure = false, webClientUrl = "https://calls.example.com/call/?v=2")
        val url = InviteLinks.build(s, "ux-abc", null)
        assertTrue(url.startsWith("https://calls.example.com/call/?v=2&to=ux-abc&"))
        val inv = InviteLinks.parse(url)!!
        assertEquals("ux-abc", inv.to); assertNull(inv.key)
        assertEquals("calls.example.com", inv.host); assertEquals(9000, inv.port); assertEquals("/sig/", inv.path)
        assertEquals("my key", inv.apiKey); assertEquals(false, inv.secure)
        assertFalse(url.contains(' '))
    }

    @Test fun `links without a target are rejected and app scheme is accepted`() {
        assertNull(InviteLinks.parse("https://mossesx.github.io/UltraX-26/call/"))
        assertNull(InviteLinks.parse("https://example.com/?k=abc"))
        val inv = InviteLinks.parse("ultrax://call?to=ux-zed&k=q1&n=Zed#frag")!!
        assertEquals("ux-zed", inv.to); assertEquals("q1", inv.key); assertEquals("Zed", inv.name)
    }

    @Test fun `invite text mentions the link and the name`() {
        val t = InviteLinks.inviteText("https://x/?to=ux-a", "Sam")
        assertTrue(t.startsWith("Sam invites you")); assertTrue(t.endsWith("https://x/?to=ux-a"))
        assertTrue(InviteLinks.inviteText("u", "").startsWith("Join my video call"))
    }

    @Test fun `peerjs media offer payload matches the wire format`() {
        val meta = buildJsonObject { put("name", "Sam"); put("key", "k1") }
        val p = PeerJsCodec.mediaOffer("mc_1", "v=0 offer", meta)
        assertEquals("media", PeerJsCodec.kindOf(p))
        assertEquals("mc_1", PeerJsCodec.connectionIdOf(p))
        assertEquals("v=0 offer", PeerJsCodec.sdpOf(p))
        assertEquals("offer", p["sdp"]!!.let { (it as kotlinx.serialization.json.JsonObject)["type"]!!.jsonPrimitive.content })
        assertEquals("Sam", PeerJsCodec.metadataOf(p)!!["name"]!!.jsonPrimitive.content)
        assertEquals(PeerJsCodec.BROWSER, p["browser"]!!.jsonPrimitive.content)
    }

    @Test fun `peerjs data offer declares json serialization`() {
        val p = PeerJsCodec.dataOffer("dc_9", "sdp", null)
        assertEquals("data", PeerJsCodec.kindOf(p))
        assertEquals("json", p["serialization"]!!.jsonPrimitive.content)
        assertEquals("true", p["reliable"]!!.jsonPrimitive.content)
        assertEquals("dc_9", p["label"]!!.jsonPrimitive.content)
        assertNull(PeerJsCodec.metadataOf(p))
    }

    @Test fun `peerjs answer and candidate round-trip`() {
        val a = PeerJsCodec.answer("mc_1", "media", "v=0 answer")
        assertEquals("v=0 answer", PeerJsCodec.sdpOf(a)); assertEquals("media", PeerJsCodec.kindOf(a))
        val c = PeerJsCodec.candidate("mc_1", "media", "candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0)
        val (cand, mid, idx) = PeerJsCodec.candidateOf(c)!!
        assertEquals("candidate:1 1 udp 1 1.2.3.4 5 typ host", cand); assertEquals("0", mid); assertEquals(0, idx)
        assertNull(PeerJsCodec.candidateOf(buildJsonObject { put("type", "media") }))
        assertNull(PeerJsCodec.sdpOf(buildJsonObject { put("type", "media") }))
    }

    @Test fun `roster for a newcomer excludes itself and the host`() {
        val all = listOf("ux-a", "ux-b", "ux-new")
        assertEquals(listOf("ux-a", "ux-b"), Roster.forNewcomer(all, "ux-new", "ux-host"))
        assertEquals(listOf("ux-b"), Roster.forNewcomer(listOf("ux-host", "ux-b", "ux-new"), "ux-new", "ux-host"))
        assertTrue(Roster.forNewcomer(listOf("ux-new"), "ux-new", "ux-host").isEmpty())
    }

    @Test fun `call settings survive json round trip inside app settings`() {
        val s = AppSettings(calls = CallSettings(displayName = "Sam", myAddress = "sam", iceServers = listOf(IceServerSpec("turn:t:3478", "u", "p")), sendWidth = 1920, sendHeight = 1080))
        val json = UxJson.encodeToString(s)
        val back = UxJson.decodeFromString(AppSettings.serializer(), json)
        assertEquals(s.calls, back.calls)
        // Old settings files without a calls block load with defaults.
        val legacy = UxJson.decodeFromString(AppSettings.serializer(), "{}")
        assertEquals(CallSettings(), legacy.calls)
        assertNotNull(legacy.calls.iceServers.firstOrNull { it.urls.startsWith("turn:") })
        assertTrue(legacy.calls.availableForIncoming)
    }
}
