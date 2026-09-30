package com.ultrax26.recorder.ui.camera

import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.camera.CameraEngine
import com.ultrax26.recorder.camera.Capabilities
import com.ultrax26.recorder.camera.RegionMapper
import com.ultrax26.recorder.settings.TonemapPreset
import com.ultrax26.recorder.triggers.RecState
import com.ultrax26.recorder.ui.Screen
import com.ultrax26.recorder.ui.theme.UxColors
import com.ultrax26.recorder.util.Maths
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(graph: AppGraph, nav: (Screen) -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val state by graph.controller.state.collectAsStateWithLifecycle()
    val session by graph.controller.sessionInfo.collectAsStateWithLifecycle()
    val frame by graph.engine.frameInfo.collectAsStateWithLifecycle()
    val status by graph.engine.status.collectAsStateWithLifecycle()
    val vision by graph.dispatcher.hud.collectAsStateWithLifecycle()
    val scopes by graph.scopes.result.collectAsStateWithLifecycle()
    val armed by graph.triggers.armed.collectAsStateWithLifecycle()
    val countdown by graph.triggers.countdown.collectAsStateWithLifecycle()
    val elapsed by graph.controller.elapsedMs.collectAsStateWithLifecycle()
    val bytes by graph.controller.bytesWritten.collectAsStateWithLifecycle()
    val segs by graph.controller.segments.collectAsStateWithLifecycle()
    val message by graph.controller.message.collectAsStateWithLifecycle()
    val flash by graph.feedback.flash.collectAsStateWithLifecycle()
    val thermal by graph.thermal.state.collectAsStateWithLifecycle()
    val lastEvent by graph.triggers.lastEvent.collectAsStateWithLifecycle()
    val preRoll by graph.controller.preRollBuffered.collectAsStateWithLifecycle()
    val free by graph.controller.freeBytes.collectAsStateWithLifecycle()
    val hub by graph.audioHubState.collectAsStateWithLifecycle()
    val audioHud = hub?.hud?.collectAsStateWithLifecycle()?.value
    val speech by graph.speechState.collectAsStateWithLifecycle()
    val speechStatus = speech?.status?.collectAsStateWithLifecycle()?.value

    DisposableEffect(Unit) { graph.setCameraScreenVisible(true); onDispose { graph.setCameraScreenVisible(false) } }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val recordAspect = (session?.recordSize?.let { it.width.toFloat() / it.height } ?: (settings.video.width.toFloat() / settings.video.height)).coerceAtLeast(0.1f)
    val displayAspect = if (landscape) recordAspect else 1f / recordAspect
    val info = session?.cameraInfo
    val front = info?.facing == CameraCharacteristics.LENS_FACING_FRONT
    val sensorOrientation = info?.sensorOrientation ?: 90
    val displayRotation = remember(landscape) { if (landscape) 90 else 0 } // refined by activity via controller
    val totalRotation = if (front) (sensorOrientation + displayRotation) % 360 else (sensorOrientation - displayRotation + 360) % 360
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    var zoomLive by remember { mutableStateOf(settings.capture.zoomRatio) }
    var showNotes by remember { mutableStateOf(false) }
    var activeControl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(frame.zoom) { frame.zoom?.let { zoomLive = it } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------- Preview + overlays ----------------
        Box(Modifier.align(Alignment.Center).aspectRatio(displayAspect)) {
            PreviewSurface(graph.controller, recordAspect, Modifier.fillMaxSize())
            if (settings.overlays.zebra) RotatedBitmapOverlay(scopes.zebra, totalRotation, front, 1f, Modifier.fillMaxSize())
            if (settings.overlays.focusPeaking) RotatedBitmapOverlay(scopes.peaking, totalRotation, front, 1f, Modifier.fillMaxSize())
            if (settings.overlays.falseColor) RotatedBitmapOverlay(scopes.falseColor, totalRotation, front, 0.85f, Modifier.fillMaxSize())
            GridOverlay(settings.overlays.grid, settings.overlays.aspectGuide, settings.overlays.safeAreas, settings.overlays.showCenterMarker, Modifier.fillMaxSize())
            if (settings.overlays.gestureHud) GestureHud(vision, front, displayAspect, Modifier.fillMaxSize())
            // Gesture input layer
            Box(Modifier.fillMaxSize()
                .pointerInput(totalRotation, front) {
                    detectTapGestures(
                        onTap = { pos ->
                            val (sx, sy) = RegionMapper.viewToSensor(pos.x / size.width, pos.y / size.height, totalRotation, front)
                            graph.engine.tapFocusMeter(sx, sy)
                            focusRing = pos
                        },
                        onDoubleTap = { graph.engine.setZoom(1f, animate = true, rampPerSec = settings.capture.zoomRampSpeed) },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            val z = (graph.engine.currentZoom() * zoom)
                            graph.engine.setZoom(z, animate = false)
                            zoomLive = z
                        }
                    }
                })
            focusRing?.let { p ->
                LaunchedEffect(p) { delay(1200); focusRing = null }
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((p.x - 40).roundToInt(), (p.y - 40).roundToInt()) }.size(80.dp).border(2.dp, UxColors.Amber, CircleShape))
            }
            if (settings.overlays.level) LevelOverlay(displayRotation, Modifier.align(Alignment.Center).padding(bottom = 90.dp))
            if (settings.overlays.waveform && scopes.waveform != null) RotatedBitmapOverlay(scopes.waveform, 0, false, 0.9f, Modifier.align(Alignment.BottomStart).padding(12.dp).size(220.dp, 70.dp))
        }

        // Flash confirmation
        var flashVisible by remember { mutableStateOf(false) }
        LaunchedEffect(flash.first) { if (flash.first > 0) { flashVisible = true; delay(160); flashVisible = false } }
        if (flashVisible) Box(Modifier.fillMaxSize().background(Color(flash.second).copy(alpha = 0.35f)))

        // ---------------- Top bar ----------------
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav(Screen.Settings(0)) }, colors = IconButtonDefaults.iconButtonColors(containerColor = UxColors.Panel)) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = armed, onClick = { graph.triggers.setArmed(!armed) },
                label = { Text(if (armed) "ARMED" else "Disarmed") },
                leadingIcon = { Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = UxColors.Green, selectedLabelColor = Color.Black, selectedLeadingIconColor = Color.Black))
            Spacer(Modifier.width(6.dp))
            AssistChip(onClick = { nav(Screen.Triggers) }, label = { Text("Gestures") }, leadingIcon = { Icon(Icons.Default.Face, null, Modifier.size(16.dp)) })
            Spacer(Modifier.weight(1f))
            session?.let { s ->
                val hdr = if (s.hdr == com.ultrax26.recorder.settings.HdrMode.OFF) "SDR" else s.hdr.name.replace('_', '+')
                val label = "${resName(s.recordSize.width, s.recordSize.height)} ${s.fps}fps · ${s.codec.name} · $hdr · ${s.bitrate / 1_000_000} Mb/s"
                AssistChip(onClick = { showNotes = true }, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = if (s.notes.isNotEmpty()) ({ Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = UxColors.Amber) }) else null)
            }
            Spacer(Modifier.width(6.dp))
            Chip("${thermal.label}${if (!thermal.headroom.isNaN()) " ${(thermal.headroom * 100).roundToInt()}%" else ""}", if (thermal.severeOrWorse) UxColors.Red else if (thermal.status >= 2) UxColors.Amber else UxColors.Slate)
        }

        // ---------------- Info column (left) ----------------
        Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 64.dp)) {
            if (settings.overlays.exposureInfo) {
                val exp = buildString {
                    frame.iso?.let { append("ISO $it  ") }
                    frame.exposureNs?.let { append(Maths.shutterLabel(it)); append("  ") }
                    frame.aperture?.let { append("f/%.1f  ".format(it)) }
                    frame.focalLength?.let { append("%.1fmm  ".format(it)) }
                    append("%.1f×".format(frame.zoom ?: zoomLive))
                    frame.activePhysicalId?.let { append("  [$it]") }
                    if (frame.fpsEstimate > 0f) append("  %.0f fps".format(frame.fpsEstimate))
                }
                Chip(exp, Color.White.copy(alpha = 0.9f))
                val locks = buildString {
                    if (graph.controller.aeLocked) append("AE-L ")
                    if (graph.controller.afLocked) append("AF-L ")
                    if (settings.capture.manualExposure) append("M-EXP ")
                    if (settings.capture.manualFocus) append("MF ")
                    if (settings.capture.manualWhiteBalance) append("${settings.capture.whiteBalanceKelvin}K ")
                    if (graph.controller.torchOn) append("TORCH ")
                }
                if (locks.isNotBlank()) Chip(locks.trim(), UxColors.Amber)
            }
            if (settings.overlays.histogram) HistogramView(scopes.histogram, Modifier.padding(top = 4.dp).size(160.dp, 60.dp))
            if (settings.overlays.audioMeter && audioHud != null) AudioMeter(audioHud.levelDbfs, audioHud.levelDbfs, Modifier.padding(top = 4.dp).size(160.dp, 10.dp))
            if (settings.overlays.gestureHud) {
                GestureLabels(vision, if (vision.blinkCount > 0) "blinks ${vision.blinkCount}" else null)
                if (audioHud != null) {
                    if (audioHud.clapsInBurst > 0) Chip("clap ${audioHud.clapsInBurst}", UxColors.Amber)
                    if (audioHud.speechActive) Chip("hearing…", UxColors.Sky)
                    audioHud.lastKeyword?.let { Chip("kw: $it", UxColors.Slate) }
                }
                if (speechStatus != null && speechStatus != "off") Chip("voice: $speechStatus", UxColors.Slate)
                if (lastEvent.isNotBlank()) Chip(lastEvent, Color(0xFFCBD5E1))
                if (vision.analysisFps > 0f) Chip("vision ${vision.analysisFps.roundToInt()} fps · ${vision.inferenceMs.roundToInt()} ms", UxColors.Slate)
            }
            if (preRoll > 0f) Chip("pre-roll ${"%.1f".format(preRoll)}s", UxColors.Sky)
        }

        // ---------------- Timecode ----------------
        if (state == RecState.RECORDING || state == RecState.PAUSED) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(if (state == RecState.PAUSED) UxColors.Amber else UxColors.Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(timecode(elapsed, session?.fps ?: 30), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text("${"%.1f".format(bytes / 1e6)} MB${if (segs > 1) " · part $segs" else ""}${if (state == RecState.PAUSED) " · PAUSED" else ""}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            }
        } else if (free >= 0) {
            val mins = session?.let { (free * 8.0 / it.bitrate / 60.0).roundToInt() }
            Chip("${"%.1f".format(free / 1e9)} GB free${if (mins != null) " ≈ $mins min" else ""}", UxColors.Slate, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        }

        // ---------------- Countdown ----------------
        countdown?.let { n ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", color = UxColors.Amber, fontSize = 120.sp, fontWeight = FontWeight.Black)
                Button(onClick = { graph.triggers.cancelCountdownRequest() }, colors = ButtonDefaults.buttonColors(containerColor = UxColors.Red)) { Text("Cancel") }
            }
        }

        // ---------------- Camera error ----------------
        (status as? CameraEngine.Status.Error)?.let { err ->
            Column(Modifier.align(Alignment.Center).background(UxColors.Panel, RoundedCornerShape(12.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err.message, color = UxColors.Red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row { Button(onClick = { graph.controller.rebuild() }) { Text("Retry") }; Spacer(Modifier.width(8.dp)); TextButton(onClick = { nav(Screen.Settings(2)) }) { Text("Camera settings") } }
            }
        }
        if (status is CameraEngine.Status.Opening) CircularProgressIndicator(Modifier.align(Alignment.Center), color = UxColors.Orange)

        // ---------------- Record controls ----------------
        val controls: @Composable () -> Unit = {
            RecordControls(state = state,
                onRecord = { graph.controller.toggle() },
                onPause = { if (state == RecState.RECORDING) graph.controller.pause() else graph.controller.resume() },
                onSnapshot = { graph.controller.perform(com.ultrax26.recorder.triggers.RecAction.SNAPSHOT, null) },
                onMarker = { graph.controller.marker() },
                vertical = landscape)
        }
        if (landscape) Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)) { controls() }
        else Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) { controls() }

        // ---------------- Bottom: lenses + pro controls ----------------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp, start = 12.dp, end = if (landscape) 110.dp else 12.dp)) {
            val chars = graph.engine.characteristics
            if (activeControl != null && chars != null) {
                ProControlPanel(graph, activeControl!!, chars, zoomLive, session?.fps ?: 30) { activeControl = null }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                session?.lensPresets?.forEach { z ->
                    val sel = kotlin.math.abs((frame.zoom ?: zoomLive) - z) < 0.06f
                    FilterChip(selected = sel, onClick = { graph.engine.setZoom(z, animate = true, rampPerSec = settings.capture.zoomRampSpeed) },
                        label = { Text(if (z < 1f) "%.1f×".format(z) else if (z == z.roundToInt().toFloat()) "${z.roundToInt()}×" else "%.1f×".format(z)) },
                        modifier = Modifier.padding(end = 4.dp))
                }
                Spacer(Modifier.width(10.dp))
                listOf("ISO", "Shutter", "EV", "WB", "Focus", "Zoom", "Tone").forEach { c ->
                    val on = activeControl == c
                    FilterChip(selected = on, onClick = { activeControl = if (on) null else c }, label = { Text(c) }, modifier = Modifier.padding(end = 4.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = UxColors.Orange, selectedLabelColor = Color.Black))
                }
            }
        }

        // ---------------- Toast ----------------
        message?.let { (ts, text) ->
            var show by remember(ts) { mutableStateOf(true) }
            LaunchedEffect(ts) { delay(2600); show = false }
            if (show) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (landscape) 70.dp else 250.dp).background(UxColors.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) { Text(text, color = Color.White) }
        }

        if (showNotes) {
            AlertDialog(onDismissRequest = { showNotes = false }, confirmButton = { TextButton(onClick = { showNotes = false }) { Text("OK") } },
                title = { Text("Session") },
                text = {
                    Column {
                        session?.let { s ->
                            Text("Camera ${s.cameraId} · ${s.cameraInfo?.shortName ?: ""}")
                            Text("${s.recordSize.width}×${s.recordSize.height} @ ${s.fps} fps${if (s.highSpeed) " (high-speed)" else ""}")
                            Text("${s.codec.label} · ${s.encoderName} · ${s.bitrate / 1_000_000} Mb/s · ${s.hdr.label}")
                            Text("Audio: ${if (s.audio) "on" else "off"} · Gesture stream: ${if (s.analysis) "on" else "off"}")
                            if (s.notes.isNotEmpty()) { Spacer(Modifier.height(8.dp)); s.notes.forEach { Text("• $it", color = UxColors.Amber) } }
                        }
                    }
                })
        }
    }
}

@Composable
private fun RecordControls(state: RecState, onRecord: () -> Unit, onPause: () -> Unit, onSnapshot: () -> Unit, onMarker: () -> Unit, vertical: Boolean) {
    val recording = state == RecState.RECORDING || state == RecState.PAUSED
    val items: List<@Composable () -> Unit> = listOf(
        {
            Box(Modifier.size(78.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)).clickable { onRecord() }, contentAlignment = Alignment.Center) {
                if (recording) Box(Modifier.size(30.dp).background(UxColors.Red, RoundedCornerShape(4.dp)))
                else Box(Modifier.size(62.dp).background(UxColors.Red, CircleShape))
            }
        },
        {
            if (recording) SmallCtl(if (state == RecState.PAUSED) "▶" else "❚❚", onPause)
            else SmallCtl("📷", onSnapshot)
        },
        {
            if (recording) SmallCtl("⚑", onMarker) else Spacer(Modifier.size(52.dp))
        },
    )
    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { items.forEach { it() } }
    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) { items[1](); items[0](); items[2]() }
}

@Composable
private fun SmallCtl(label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(UxColors.Panel).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 18.sp) }
}

@Composable
private fun ProControlPanel(graph: AppGraph, control: String, chars: CameraCharacteristics, zoomLive: Float, fps: Int, onClose: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val c = settings.capture
    fun upd(f: (com.ultrax26.recorder.settings.CaptureSettings) -> com.ultrax26.recorder.settings.CaptureSettings) = graph.settings.update { it.copy(capture = f(it.capture)) }
    Column(Modifier.fillMaxWidth().background(UxColors.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control, color = UxColors.Orange, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        when (control) {
            "ISO" -> {
                val range = Capabilities.isoRange(chars)
                if (range == null) Text("Manual ISO not exposed by this camera", color = Color.White)
                else {
                    ManualToggle(c.manualExposure) { upd { s -> s.copy(manualExposure = it) } }
                    val cur = (c.iso ?: range.lower * 2).coerceIn(range.lower, range.upper)
                    Slider(value = Maths.rangeToLog(cur.toDouble(), range.lower.toDouble(), range.upper.toDouble()), onValueChange = { t -> upd { s -> s.copy(manualExposure = true, iso = Maths.logToRange(t, range.lower.toDouble(), range.upper.toDouble()).roundToInt()) } })
                    Text("ISO $cur  (${range.lower}–${range.upper})", color = Color.White)
                }
            }
            "Shutter" -> {
                val range = Capabilities.exposureRange(chars)
                if (range == null) Text("Manual shutter not exposed by this camera", color = Color.White)
                else {
                    ManualToggle(c.manualExposure) { upd { s -> s.copy(manualExposure = it) } }
                    val maxNs = minOf(range.upper, 1_000_000_000L / fps.coerceAtLeast(1))
                    val minNs = maxOf(range.lower, 1_000_000_000L / 16000)
                    val cur = (c.exposureTimeNs ?: (1_000_000_000L / (fps * 2))).coerceIn(minNs, maxNs)
                    Slider(value = Maths.rangeToLog(cur.toDouble(), minNs.toDouble(), maxNs.toDouble()), onValueChange = { t -> upd { s -> s.copy(manualExposure = true, exposureTimeNs = Maths.logToRange(t, minNs.toDouble(), maxNs.toDouble()).toLong()) } })
                    Row { listOf(1.0 / (fps * 2), 1.0 / 100, 1.0 / 250, 1.0 / 1000).forEach { sec -> val ns = (sec * 1e9).toLong(); if (ns in minNs..maxNs) AssistChip(onClick = { upd { s -> s.copy(manualExposure = true, exposureTimeNs = ns) } }, label = { Text(Maths.shutterLabel(ns)) }, modifier = Modifier.padding(end = 4.dp)) } }
                    Text("${Maths.shutterLabel(cur)}  (180° shutter = ${Maths.shutterLabel(1_000_000_000L / (fps * 2))})", color = Color.White)
                }
            }
            "EV" -> {
                val r = Capabilities.evRange(chars); val step = Capabilities.evStep(chars)
                Slider(value = c.exposureCompensation.toFloat(), onValueChange = { upd { s -> s.copy(exposureCompensation = it.roundToInt()) } }, valueRange = r.lower.toFloat()..r.upper.toFloat(), steps = (r.upper - r.lower - 1).coerceAtLeast(0))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%+.1f EV".format(c.exposureCompensation * step), color = Color.White, modifier = Modifier.weight(1f))
                    FilterChip(selected = graph.controller.aeLocked, onClick = { graph.controller.perform(com.ultrax26.recorder.triggers.RecAction.TOGGLE_AE_LOCK, null) }, label = { Text("AE lock") })
                }
            }
            "WB" -> {
                ManualToggle(c.manualWhiteBalance, "Manual Kelvin") { upd { s -> s.copy(manualWhiteBalance = it) } }
                Slider(value = c.whiteBalanceKelvin.toFloat(), onValueChange = { upd { s -> s.copy(manualWhiteBalance = true, whiteBalanceKelvin = (it / 50).roundToInt() * 50) } }, valueRange = 2000f..10000f)
                Slider(value = c.whiteBalanceTint, onValueChange = { upd { s -> s.copy(manualWhiteBalance = true, whiteBalanceTint = it) } }, valueRange = -1f..1f)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(2800 to "Tungsten", 3400 to "Halogen", 4000 to "Fluor", 5000 to "Flash", 5600 to "Daylight", 6500 to "Cloudy", 7500 to "Shade").forEach { (k, l) ->
                        AssistChip(onClick = { upd { s -> s.copy(manualWhiteBalance = true, whiteBalanceKelvin = k) } }, label = { Text("$l ${k}K") }, modifier = Modifier.padding(end = 4.dp))
                    }
                    Capabilities.awbModes(chars).filter { it.value != 0 }.forEach { o -> AssistChip(onClick = { upd { s -> s.copy(manualWhiteBalance = false, awbMode = o.value) } }, label = { Text(o.label) }, modifier = Modifier.padding(end = 4.dp)) }
                }
                Text("${c.whiteBalanceKelvin}K · tint %+.2f".format(c.whiteBalanceTint), color = Color.White)
            }
            "Focus" -> {
                val minF = Capabilities.minFocusDistance(chars)
                if (minF <= 0f) Text("Manual focus not exposed by this camera", color = Color.White)
                else {
                    ManualToggle(c.manualFocus) { upd { s -> s.copy(manualFocus = it) } }
                    val cur = (c.focusDistance ?: 0f).coerceIn(0f, minF)
                    Slider(value = cur, onValueChange = { upd { s -> s.copy(manualFocus = true, focusDistance = it) } }, valueRange = 0f..minF)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (cur <= 0.01f) "∞" else "%.2f m".format(1f / cur), color = Color.White, modifier = Modifier.weight(1f))
                        FilterChip(selected = graph.controller.afLocked, onClick = { graph.controller.perform(com.ultrax26.recorder.triggers.RecAction.TOGGLE_AF_LOCK, null) }, label = { Text("AF lock") })
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = { graph.engine.startFocusPull(c.focusPullFrom, c.focusPullTo, c.focusPullDurationMs) }, label = { Text("Pull A→B") })
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = { upd { s -> s.copy(focusPullFrom = cur) } }, label = { Text("Set A") })
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = { upd { s -> s.copy(focusPullTo = cur) } }, label = { Text("Set B") })
                    }
                }
            }
            "Zoom" -> {
                val zr = Capabilities.zoomRange(chars)
                Slider(value = Maths.rangeToLog(zoomLive.toDouble(), zr.lower.toDouble(), zr.upper.toDouble()), onValueChange = { t -> graph.engine.setZoom(Maths.logToRange(t, zr.lower.toDouble(), zr.upper.toDouble()).toFloat(), animate = false) },
                    onValueChangeFinished = { upd { s -> s.copy(zoomRatio = graph.engine.currentZoom()) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.2f× (${zr.lower}–${zr.upper}×)".format(zoomLive), color = Color.White, modifier = Modifier.weight(1f))
                    Text("Ramp", color = Color.White.copy(alpha = 0.7f)); Spacer(Modifier.width(6.dp))
                    Slider(value = c.zoomRampSpeed, onValueChange = { upd { s -> s.copy(zoomRampSpeed = it) } }, valueRange = 0.2f..10f, modifier = Modifier.width(140.dp))
                    Text("%.1f×/s".format(c.zoomRampSpeed), color = Color.White)
                }
            }
            "Tone" -> {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TonemapPreset.entries.forEach { p -> FilterChip(selected = c.tonemapPreset == p, onClick = { upd { s -> s.copy(tonemapPreset = p) } }, label = { Text(p.label) }, modifier = Modifier.padding(end = 4.dp)) }
                }
                if (c.tonemapPreset == TonemapPreset.GAMMA) Slider(value = c.tonemapGamma, onValueChange = { upd { s -> s.copy(tonemapGamma = it) } }, valueRange = 0.5f..4f)
                Text(if (Capabilities.tonemapModes(chars).isEmpty()) "Tone-mapping control not exposed by this camera" else "Max curve points: ${Capabilities.tonemapMaxPoints(chars)}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ManualToggle(manual: Boolean, label: String = "Manual", onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = !manual, onClick = { onChange(false) }, label = { Text("Auto") }, modifier = Modifier.padding(end = 6.dp))
        FilterChip(selected = manual, onClick = { onChange(true) }, label = { Text(label) })
    }
}

fun resName(w: Int, h: Int): String = when {
    w >= 7680 -> "8K"; w >= 3840 -> "4K"; w >= 2560 -> "1440p"; w >= 1920 -> "1080p"; w >= 1280 -> "720p"; else -> "${h}p"
}

fun timecode(ms: Long, fps: Int): String {
    val totalS = ms / 1000
    val frames = ((ms % 1000) * fps.coerceAtLeast(1) / 1000).toInt()
    return "%02d:%02d:%02d:%02d".format(totalS / 3600, (totalS / 60) % 60, totalS % 60, frames)
}
