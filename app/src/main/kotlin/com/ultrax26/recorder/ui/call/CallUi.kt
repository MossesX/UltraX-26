package com.ultrax26.recorder.ui.call

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.calls.CallUiState
import com.ultrax26.recorder.calls.IncomingCall
import com.ultrax26.recorder.calls.InviteLinks
import com.ultrax26.recorder.calls.Participant
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.triggers.RecState
import com.ultrax26.recorder.ui.Screen
import com.ultrax26.recorder.ui.components.SwitchRow
import com.ultrax26.recorder.ui.components.TextFieldRow
import com.ultrax26.recorder.ui.theme.UxColors
import com.ultrax26.recorder.util.Clock
import kotlinx.coroutines.delay
import org.webrtc.VideoTrack

/**
 * Everything call-related that sits on top of the camera screen: the in-call overlay (video grid +
 * controls), the incoming-call dialog and the "start a call" sheet with the shareable link + QR code.
 */
@Composable
fun CallLayer(graph: AppGraph, showStart: Boolean, onDismissStart: () -> Unit, nav: (Screen) -> Unit, mirrorSelf: Boolean, onOpenEffects: () -> Unit) {
    val st by graph.calls.state.collectAsStateWithLifecycle()
    val tracks by graph.calls.remoteTracks.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    if (st.inCall) InCallOverlay(graph, st, tracks, settings, mirrorSelf, onOpenEffects)
    st.incoming?.let { inc -> IncomingCallDialog(inc, onAccept = { graph.calls.accept() }, onDecline = { graph.calls.decline() }) }
    if (showStart) CallStartSheet(graph, st, settings, onDismissStart, nav)
}

// ----------------------------------------------------------------------------------------------
// In-call overlay
// ----------------------------------------------------------------------------------------------

private data class Tile(val self: Boolean, val participant: Participant?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InCallOverlay(graph: AppGraph, st: CallUiState, tracks: Map<String, VideoTrack>, settings: AppSettings, mirrorSelf: Boolean, onOpenEffects: () -> Unit) {
    val core = graph.calls.core
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val recState by graph.controller.state.collectAsStateWithLifecycle()
    var showChat by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Clock.bootMs()) }
    LaunchedEffect(st.startedAtMs) { while (true) { now = Clock.bootMs(); delay(1000) } }
    LaunchedEffect(showChat, st.chat.size) { if (showChat) graph.calls.markChatRead() }
    val elapsed = if (st.startedAtMs > 0L) now - st.startedAtMs else 0L
    val tiles = listOf(Tile(true, null)) + st.participants.map { Tile(false, it) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- video grid (self is a tile like everyone else, Meet-style; no overlapping surfaces) ----
        Column(Modifier.fillMaxSize().padding(top = 58.dp, bottom = if (landscape) 96.dp else 112.dp, start = 4.dp, end = 4.dp)) {
            val n = tiles.size
            val cols = when { n <= 1 -> 1; n == 2 -> if (landscape) 2 else 1; else -> 2 }
            val rows = (n + cols - 1) / cols
            for (r in 0 until rows) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (i < n) Box(Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(14.dp)).background(UxColors.PanelSolid)) {
                        val t = tiles[i]
                        val name = if (t.self) (settings.calls.displayName.ifBlank { "You" }) else t.participant!!.name
                        val track = if (t.self) core?.videoTrack else tracks[t.participant!!.peerId]
                        val showVideo = core != null && track != null && !(t.self && st.cameraOff)
                        if (showVideo) VideoTile(core!!, track, mirror = t.self && mirrorSelf, Modifier.fillMaxSize())
                        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(64.dp).background(UxColors.Orange, CircleShape), contentAlignment = Alignment.Center) {
                                Text(name.take(1).uppercase(), color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text(when { t.self -> "Camera off"; t.participant?.connected == true -> "Camera off"; else -> "Connecting…" }, color = UxColors.Slate, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(name + (if (t.self && st.micMuted) " · muted" else ""), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }

        // ---- top bar ----
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (st.startedAtMs > 0L) fmtElapsed(elapsed) else st.status, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(if (st.startedAtMs > 0L) "${st.status} · ${st.participants.size + 1} in call" else "Video call", color = UxColors.Slate, style = MaterialTheme.typography.bodySmall)
            }
            val recording = recState == RecState.RECORDING || recState == RecState.PAUSED
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (recording) UxColors.Red else Color.White.copy(alpha = 0.18f)).clickable { graph.controller.perform(RecAction.TOGGLE_RECORD, null) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (recording) Color.White else UxColors.Red, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(if (recording) "REC" else "Record", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        // ---- controls ----
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            CallButton(Glyph.MIC, if (st.micMuted) "Unmute" else "Mute", off = st.micMuted, bg = if (st.micMuted) Color.White else Color.White.copy(alpha = 0.18f), fg = if (st.micMuted) Color.Black else Color.White) { graph.calls.toggleMic() }
            CallButton(Glyph.CAMERA, if (st.cameraOff) "Camera on" else "Camera", off = st.cameraOff, bg = if (st.cameraOff) Color.White else Color.White.copy(alpha = 0.18f), fg = if (st.cameraOff) Color.Black else Color.White) { graph.calls.toggleCamera() }
            CallButton(Glyph.SPEAKER, if (st.speaker) "Speaker" else "Earpiece", off = !st.speaker) { graph.calls.toggleSpeaker() }
            CallButton(Glyph.EFFECTS, "Effects", bg = if (settings.effects.isActive()) UxColors.Orange else Color.White.copy(alpha = 0.18f), fg = if (settings.effects.isActive()) Color.Black else Color.White) { onOpenEffects() }
            CallButton(Glyph.CHAT, "Chat", badge = st.unreadChat) { showChat = true }
            if (st.inviteUrl != null && st.roomKey != null) CallButton(Glyph.INVITE, "Invite") { showInvite = true }
            CallButton(Glyph.END, "End", bg = UxColors.Red) { graph.calls.hangUp() }
        }

        st.error?.let { e -> Text(e, color = UxColors.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) }
    }

    if (showChat) ModalBottomSheet(onDismissRequest = { showChat = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Chat", style = MaterialTheme.typography.titleLarge)
            Text("Messages go to everyone in the call (browser guests too).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 340.dp)) {
                items(st.chat) { m ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(m.from, color = if (m.from == "You") UxColors.Orange else UxColors.Sky, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            var text by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, singleLine = true)
                IconButton(onClick = { if (text.isNotBlank()) { graph.calls.sendChat(text.trim()); text = "" } }) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }

    if (showInvite && st.inviteUrl != null) AlertDialog(
        onDismissRequest = { showInvite = false },
        confirmButton = { TextButton(onClick = { showInvite = false }) { Text("Done") } },
        title = { Text("Invite people") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { InviteCard(st.inviteUrl, settings.calls.displayName) } },
    )
}

private fun fmtElapsed(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

// ----------------------------------------------------------------------------------------------
// Incoming call
// ----------------------------------------------------------------------------------------------

@Composable
private fun IncomingCallDialog(inc: IncomingCall, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Incoming video call") },
        text = {
            Column {
                Text(inc.name, style = MaterialTheme.typography.headlineSmall)
                Text(inc.peerId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("Hands-free: say “answer” or “hang up”. Gesture rules for calls (thumbs up = answer, open palm = hang up) can be enabled under Gestures.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = UxColors.Green, contentColor = Color.Black)) {
                Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Answer")
            }
        },
        dismissButton = { TextButton(onClick = onDecline, colors = ButtonDefaults.textButtonColors(contentColor = UxColors.Red)) { Text("Decline") } },
    )
}

// ----------------------------------------------------------------------------------------------
// Start sheet
// ----------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallStartSheet(graph: AppGraph, st: CallUiState, settings: AppSettings, onDismiss: () -> Unit, nav: (Screen) -> Unit) {
    val cs = settings.calls
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Video call", style = MaterialTheme.typography.headlineSmall)
            Text("Share a link — the other person joins from any browser, no app needed. Your filters and backgrounds apply in calls too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val statusColor = when { st.error != null -> UxColors.Red; st.available -> UxColors.Green; else -> UxColors.Slate }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(st.error ?: st.status.ifBlank { if (cs.availableForIncoming) "Offline" else "Not reachable — incoming calls are off" }, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            TextFieldRow("Your name", cs.displayName, { v -> graph.settings.update { it.copy(calls = it.calls.copy(displayName = v)) } }, subtitle = "Shown to the people you call")
            Spacer(Modifier.height(6.dp))
            if (st.inviteUrl != null && st.roomKey != null) InviteCard(st.inviteUrl, cs.displayName)
            else Button(onClick = { graph.calls.hostCall() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Create call link") }
            Spacer(Modifier.height(18.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
            Text("Call someone directly", style = MaterialTheme.typography.titleMedium)
            var target by remember { mutableStateOf("") }
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Address or call link") }, singleLine = true,
                supportingText = { Text("Another UltraX phone's address (ux-name), or a link someone sent you") })
            Spacer(Modifier.height(6.dp))
            Button(onClick = { if (target.isNotBlank()) { graph.calls.callPeer(target.trim()); onDismiss() } }, enabled = target.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Call")
            }
            Spacer(Modifier.height(18.dp)); HorizontalDivider(); Spacer(Modifier.height(6.dp))
            SwitchRow("Reachable for incoming calls", cs.availableForIncoming, "Your address: ${st.myPeerId ?: "—"}") { on ->
                graph.settings.update { it.copy(calls = it.calls.copy(availableForIncoming = on)) }
                if (on) graph.calls.goOnline() else graph.calls.goOffline()
            }
            TextButton(onClick = { onDismiss(); nav(Screen.Settings(8)) }) { Text("Call settings — permanent address, quality, servers") }
        }
    }
}

@Composable
private fun InviteCard(url: String, name: String) {
    val ctx = LocalContext.current
    val qr = remember(url) { qrBitmap(url, 512) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Your call link", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            Spacer(Modifier.height(10.dp))
            qr?.let { bmp ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), "QR code of the call link", Modifier.size(190.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareInvite(ctx, url, name) }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                OutlinedButton(onClick = { smsInvite(ctx, url, name) }, Modifier.weight(1f)) { Text("Text") }
                OutlinedButton(onClick = { copyInvite(ctx, url) }, Modifier.weight(1f)) { Text("Copy") }
            }
            Spacer(Modifier.height(6.dp))
            Text("Anyone with the link can join while the call is open. A fresh link is made for every call.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
        }
    }
}

private fun shareInvite(ctx: Context, url: String, name: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Video call invite"); putExtra(Intent.EXTRA_TEXT, InviteLinks.inviteText(url, name)) }
    try { ctx.startActivity(Intent.createChooser(i, "Send call link")) } catch (_: ActivityNotFoundException) { copyInvite(ctx, url) }
}

private fun smsInvite(ctx: Context, url: String, name: String) {
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply { putExtra("sms_body", InviteLinks.inviteText(url, name)) }
    try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { shareInvite(ctx, url, name) }
}

private fun copyInvite(ctx: Context, url: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Call link", url))
}

private fun qrBitmap(text: String, size: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (_: Throwable) { null }

// ----------------------------------------------------------------------------------------------
// Round control buttons with vector glyphs (no extended icon pack needed)
// ----------------------------------------------------------------------------------------------

private enum class Glyph { MIC, CAMERA, SPEAKER, CHAT, EFFECTS, INVITE, END }

@Composable
private fun CallButton(glyph: Glyph, label: String, off: Boolean = false, bg: Color = Color.White.copy(alpha = 0.18f), fg: Color = Color.White, badge: Int = 0, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) { drawGlyph(glyph, fg, off) }
            if (badge > 0) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).background(UxColors.Orange, CircleShape), contentAlignment = Alignment.Center) {
                Text(if (badge > 9) "9+" else "$badge", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color, off: Boolean) {
    val w = size.width; val h = size.height; val sw = w * 0.09f
    when (g) {
        Glyph.MIC -> {
            drawRoundRect(c, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.52f), CornerRadius(w * 0.14f))
            drawArc(c, 0f, 180f, false, Offset(w * 0.22f, h * 0.18f), Size(w * 0.56f, h * 0.56f), style = Stroke(sw))
            drawLine(c, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.9f), sw)
            drawLine(c, Offset(w * 0.34f, h * 0.9f), Offset(w * 0.66f, h * 0.9f), sw)
        }
        Glyph.CAMERA -> {
            drawRoundRect(c, Offset(w * 0.08f, h * 0.26f), Size(w * 0.58f, h * 0.48f), CornerRadius(w * 0.08f))
            drawPath(Path().apply { moveTo(w * 0.68f, h * 0.45f); lineTo(w * 0.92f, h * 0.3f); lineTo(w * 0.92f, h * 0.7f); lineTo(w * 0.68f, h * 0.55f); close() }, c)
        }
        Glyph.SPEAKER -> {
            drawPath(Path().apply { moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.28f, h * 0.38f); lineTo(w * 0.48f, h * 0.18f); lineTo(w * 0.48f, h * 0.82f); lineTo(w * 0.28f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close() }, c)
            drawArc(c, -50f, 100f, false, Offset(w * 0.33f, h * 0.3f), Size(w * 0.4f, h * 0.4f), style = Stroke(sw))
            drawArc(c, -50f, 100f, false, Offset(w * 0.28f, h * 0.14f), Size(w * 0.66f, h * 0.72f), style = Stroke(sw))
        }
        Glyph.CHAT -> {
            drawRoundRect(c, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.56f), CornerRadius(w * 0.16f))
            drawPath(Path().apply { moveTo(w * 0.24f, h * 0.66f); lineTo(w * 0.18f, h * 0.9f); lineTo(w * 0.46f, h * 0.7f); close() }, c)
        }
        Glyph.EFFECTS -> {
            drawCircle(c, w * 0.4f, Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
            drawCircle(c, w * 0.055f, Offset(w * 0.37f, h * 0.42f)); drawCircle(c, w * 0.055f, Offset(w * 0.63f, h * 0.42f))
            drawArc(c, 25f, 130f, false, Offset(w * 0.3f, h * 0.36f), Size(w * 0.4f, h * 0.36f), style = Stroke(sw))
            drawLine(c, Offset(w * 0.2f, h * 0.3f), Offset(w * 0.8f, h * 0.3f), sw * 0.8f)
        }
        Glyph.INVITE -> {
            drawCircle(c, w * 0.14f, Offset(w * 0.4f, h * 0.3f))
            drawArc(c, 180f, 180f, true, Offset(w * 0.12f, h * 0.5f), Size(w * 0.56f, h * 0.6f))
            drawLine(c, Offset(w * 0.8f, h * 0.28f), Offset(w * 0.8f, h * 0.56f), sw)
            drawLine(c, Offset(w * 0.66f, h * 0.42f), Offset(w * 0.94f, h * 0.42f), sw)
        }
        Glyph.END -> drawPath(Path().apply {
            moveTo(w * 0.08f, h * 0.56f); cubicTo(w * 0.3f, h * 0.32f, w * 0.7f, h * 0.32f, w * 0.92f, h * 0.56f)
            lineTo(w * 0.78f, h * 0.72f); cubicTo(w * 0.62f, h * 0.58f, w * 0.38f, h * 0.58f, w * 0.22f, h * 0.72f); close()
        }, c)
    }
    if (off) drawLine(UxColors.Red, Offset(w * 0.12f, h * 0.1f), Offset(w * 0.88f, h * 0.9f), sw * 1.2f, cap = StrokeCap.Round)
}
