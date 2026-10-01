package com.ultrax26.recorder.ui.settings

import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.BuildConfig
import com.ultrax26.recorder.calls.CallSettings
import com.ultrax26.recorder.calls.IceServerSpec
import com.ultrax26.recorder.calls.InviteLinks
import com.ultrax26.recorder.camera.Capabilities
import com.ultrax26.recorder.recording.EncoderCapabilities
import com.ultrax26.recorder.settings.*
import com.ultrax26.recorder.ui.Screen
import com.ultrax26.recorder.ui.components.*
import kotlin.math.roundToInt

@Composable
fun SettingsHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, initialTab: Int, onBack: () -> Unit, nav: (Screen) -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 8)) }
    val tabs = listOf("Video", "Audio", "Camera", "Overlays", "Storage", "Presets", "Diagnostics", "Effects", "Calls", "About")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeader("Settings", onBack)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            when (tab) {
                0 -> VideoTab(graph, settings)
                1 -> AudioTab(graph, settings)
                2 -> CameraTab(graph, settings, nav)
                3 -> OverlaysTab(graph, settings)
                4 -> StorageTab(graph, settings)
                5 -> PresetsTab(graph, settings)
                6 -> DiagnosticsTab(graph, nav)
                7 -> EffectsTab(graph, settings)
                8 -> CallsTab(graph, settings)
                else -> AboutTab()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Video
// ------------------------------------------------------------------------------------------------

@Composable
private fun VideoTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (VideoSettings) -> VideoSettings) = graph.settings.update { it.copy(video = f(it.video)) }
    val v = s.video
    val cameraId = s.capture.cameraId ?: graph.catalog.defaultBackId()
    val chars = remember(cameraId) { runCatching { cameraId?.let { graph.catalog.characteristics(it) } }.getOrNull() }
    val sizes = remember(chars) { chars?.let { Capabilities.videoSizes(it) } ?: emptyList() }
    val hiResOnly = remember(chars) { chars?.let { Capabilities.highResolutionOnlySizes(it) } ?: emptySet() }
    val hsSizes = remember(chars) { chars?.let { Capabilities.highSpeedSizes(it) } ?: emptyList() }
    val cur = Size(v.width, v.height)

    SectionCard("Format", "Only sizes and rates this camera advertises are listed") {
        val list = (if (v.highSpeed) hsSizes else sizes).ifEmpty { listOf(cur) }
        PickerRow("Resolution", list.map { it to "${it.width}×${it.height}  (${resLabel(it)})${if (it in hiResOnly) " · high-res mode" else ""}" }, list.firstOrNull { it == cur } ?: list.first()) { upd { x -> x.copy(width = it.width, height = it.height) } }
        if (!v.highSpeed && sizes.none { it.width >= 7680 }) EightKFinder(graph, s, cameraId)
        val fpsOptions = if (chars == null) listOf(v.fps) else if (v.highSpeed) Capabilities.highSpeedFpsRanges(chars, cur).map { it.upper }.distinct().sorted().ifEmpty { listOf(120, 240) } else Capabilities.selectableFps(chars, cur).ifEmpty { listOf(24, 30, 60) }
        PickerRow("Frame rate", fpsOptions.map { it to "$it fps" }, if (v.fps in fpsOptions) v.fps else fpsOptions.last()) { upd { x -> x.copy(fps = it) } }
        SwitchRow("High-speed capture (120/240 fps)", v.highSpeed, "Constrained high-speed session; gesture camera stream unavailable while active", enabled = hsSizes.isNotEmpty()) { on ->
            upd { x -> if (on) { val sz = hsSizes.firstOrNull() ?: cur; x.copy(highSpeed = true, width = sz.width, height = sz.height, fps = 120) } else x.copy(highSpeed = false, fps = 30) }
        }
        if (v.highSpeed) PickerRow("Slow-motion playback", listOf<Int?>(null, 24, 25, 30, 60).map { it to (it?.let { "$it fps (slow motion, no audio)" } ?: "Real time (keep audio)") }, v.slowMotionPlaybackFps) { upd { x -> x.copy(slowMotionPlaybackFps = it) } }
        NullableIntPickerRow("Orientation lock", listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°"), v.orientationLock, autoLabel = "Follow device") { upd { x -> x.copy(orientationLock = it) } }
        SwitchRow("Mirror front-camera recordings", v.mirrorFrontCamera) { upd { x -> x.copy(mirrorFrontCamera = it) } }
    }

    SectionCard("Codec & quality") {
        val codecs = remember { EncoderCapabilities.availableCodecs().ifEmpty { listOf(VideoCodec.HEVC) } }
        PickerRow("Codec", codecs.map { it to it.label }, if (v.codec in codecs) v.codec else codecs.first()) { upd { x -> x.copy(codec = it, encoderName = null, profile = null) } }
        val encs = remember(v.codec) { EncoderCapabilities.encoders(v.codec.mime) }
        PickerRow("Encoder", listOf<String?>(null).plus(encs.map { it.name }).map { it to (it ?: "Auto (best hardware encoder)") }, v.encoderName) { upd { x -> x.copy(encoderName = it, profile = null) } }
        val enc = encs.firstOrNull { it.name == v.encoderName } ?: encs.firstOrNull()
        val profiles = enc?.profiles?.map { it.profile }?.distinct() ?: emptyList()
        NullableIntPickerRow("Profile", profiles.map { it to EncoderCapabilities.profileLabel(v.codec, it) }, v.profile, autoLabel = "Auto (by HDR mode)") { upd { x -> x.copy(profile = it) } }
        val hdrModes = remember(chars) { chars?.let { Capabilities.supportedHdrModes(it) } ?: listOf(HdrMode.OFF) }
        PickerRow("HDR", hdrModes.map { it to it.label }, if (v.hdr in hdrModes) v.hdr else HdrMode.OFF, "Needs a 10-bit encoder profile; falls back to SDR otherwise") { upd { x -> x.copy(hdr = it) } }
        PickerRow("Bitrate mode", BitrateMode.entries.map { it to it.label }, v.bitrateMode) { upd { x -> x.copy(bitrateMode = it) } }
        val auto = v.bitrateMbps == null
        val autoValue = EncoderCapabilities.autoBitrate(v.width, v.height, v.fps, v.codec, v.hdr) / 1_000_000f
        SwitchRow("Automatic bitrate", auto, "Auto = ${"%.0f".format(autoValue)} Mb/s for this format${enc?.let { " (encoder max ${it.bitrateRange.upper / 1_000_000} Mb/s)" } ?: ""}") { on -> upd { x -> x.copy(bitrateMbps = if (on) null else autoValue) } }
        if (!auto) SliderRow("Bitrate", v.bitrateMbps ?: autoValue, 2f..(enc?.bitrateRange?.upper?.div(1_000_000f) ?: 800f), format = { "${it.roundToInt()} Mb/s" }) { upd { x -> x.copy(bitrateMbps = it) } }
        if (v.bitrateMode == BitrateMode.CQ) IntSliderRow("CQ quality", v.cqQuality, 0, 100) { upd { x -> x.copy(cqQuality = it) } }
        SliderRow("Keyframe interval", v.iFrameIntervalSec, 0.2f..10f, format = { "%.1f s".format(it) }) { upd { x -> x.copy(iFrameIntervalSec = it) } }
        IntSliderRow("B-frames", v.maxBFrames, 0, 4, subtitle = "0 for the lowest latency / best compatibility") { upd { x -> x.copy(maxBFrames = it) } }
        SwitchRow("Full color range", v.fullRange, "Limited (16–235) is the safe default for players") { upd { x -> x.copy(fullRange = it) } }
        PickerRow("Container", Container.entries.map { it to it.label }, v.container) { upd { x -> x.copy(container = it) } }
    }

    SectionCard("Clip management") {
        IntSliderRow("Pre-roll buffer", v.preRollSeconds, 0, 30, suffix = " s", subtitle = "Keeps the last N seconds in RAM while armed so triggered clips start before the gesture") { upd { x -> x.copy(preRollSeconds = it) } }
        IntSliderRow("Split every", v.segmentMinutes, 0, 60, suffix = " min", subtitle = "0 = never (keyframe-aligned parts)") { upd { x -> x.copy(segmentMinutes = it) } }
        PickerRow("Max file size", listOf(0, 1024, 2048, 3900, 8192, 16384, 32768).map { it to (if (it == 0) "Unlimited" else "$it MB") }, v.maxFileSizeMb) { upd { x -> x.copy(maxFileSizeMb = it) } }
        IntSliderRow("Max duration", v.maxDurationSec / 60, 0, 240, suffix = " min", subtitle = "0 = unlimited; auto-stops the take") { upd { x -> x.copy(maxDurationSec = it * 60) } }
        SwitchRow("Stop when the phone is critically hot", v.stopOnThermalCritical) { upd { x -> x.copy(stopOnThermalCritical = it) } }
        SwitchRow("Lower bitrate when thermally severe", v.downgradeOnThermalSevere) { upd { x -> x.copy(downgradeOnThermalSevere = it) } }
        SwitchRow("Write JSON sidecar per clip", s.storage.writeSidecarJson, "Settings, markers and trigger log next to the clip") { on -> graph.settings.update { it.copy(storage = it.storage.copy(writeSidecarJson = on)) } }
    }

    SectionCard("Time-lapse & interval") {
        IntSliderRow("Time-lapse: keep 1 of N frames", v.timelapseFactor, 1, 60, subtitle = "Camera-side decimation; playback speed ×N") { upd { x -> x.copy(timelapseFactor = it) } }
        PickerRow("Interval capture", listOf(0L, 500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L).map { it to (if (it == 0L) "Off" else "every ${it / 1000.0} s") }, v.intervalCaptureMs, "One frame every N seconds, muxed at the selected frame rate") { upd { x -> x.copy(intervalCaptureMs = it) } }
    }
}

private fun resLabel(sz: Size): String {
    val aspect = sz.width.toFloat() / sz.height
    val a = when {
        kotlin.math.abs(aspect - 16f / 9f) < 0.02f -> "16:9"; kotlin.math.abs(aspect - 4f / 3f) < 0.02f -> "4:3"; kotlin.math.abs(aspect - 1f) < 0.02f -> "1:1"
        kotlin.math.abs(aspect - 21f / 9f) < 0.05f -> "21:9"; kotlin.math.abs(aspect - 3f / 2f) < 0.02f -> "3:2"; else -> "%.2f:1".format(aspect)
    }
    return "${com.ultrax26.recorder.ui.camera.resName(sz.width, sz.height)} $a"
}

// ------------------------------------------------------------------------------------------------
// Audio
// ------------------------------------------------------------------------------------------------

/**
 * Shown when the selected camera does not list 7680×4320. Scans every camera this phone exposes
 * (public, hidden and the physical sub-cameras of logical ones) and offers to switch to one that does.
 */
@Composable
private fun EightKFinder(graph: AppGraph, s: AppSettings, cameraId: String?) {
    var scanning by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<com.ultrax26.recorder.camera.CameraCatalog.EightKHit>?>(null) }
    LaunchedEffect(scanning) {
        if (scanning) {
            hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { graph.catalog.find8k(true, s.capture.hiddenIdProbeMax) }.getOrDefault(emptyList()) }
            scanning = false
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("8K is not advertised by camera ${cameraId ?: "?"} through Camera2.", style = MaterialTheme.typography.bodyMedium, color = com.ultrax26.recorder.ui.theme.UxColors.Amber)
    Text("Samsung exposes some sizes only on hidden or physical camera IDs, or only in the slower \"high-resolution\" set. Scan to find out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row { TextButton(onClick = { scanning = true }, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan all cameras for 8K") } }
    hits?.let { found ->
        if (found.isEmpty()) {
            Text("No camera on this phone lists 7680×4320 for third-party apps. Samsung keeps 8K for its own camera app; nothing in Camera2 or the vendor keys this app can see unlocks it. Share the Diagnostics report and the vendor tags can be checked by hand.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else found.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.bodyMedium)
                    Text("7680×4320 · up to ${h.maxFps} fps${if (h.highResOnly) " · high-resolution mode" else ""}${if (h.viaLogicalId != null) " · via physical-lens lock" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    graph.settings.update { st ->
                        val cap = if (h.viaLogicalId != null) st.capture.copy(cameraId = h.viaLogicalId, lensMode = LensMode.LOCK_PHYSICAL, lockedPhysicalCameraId = h.cameraId, zoomRatio = 1f)
                                  else st.capture.copy(cameraId = h.cameraId, lensMode = LensMode.AUTO, lockedPhysicalCameraId = null, zoomRatio = 1f)
                        st.copy(capture = cap, video = st.video.copy(width = 7680, height = 4320, fps = minOf(st.video.fps, h.maxFps).coerceAtLeast(1), highSpeed = false))
                    }
                }) { Text("Use") }
            }
        }
    }
}


@Composable
private fun AudioTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (AudioSettings) -> AudioSettings) = graph.settings.update { it.copy(audio = f(it.audio)) }
    val a = s.audio
    val ctx = LocalContext.current
    val devices = remember { runCatching { (ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList()) }
    SectionCard("Capture") {
        SwitchRow("Record audio", a.enabled) { upd { x -> x.copy(enabled = it) } }
        PickerRow("Source", listOf(MediaRecorder.AudioSource.CAMCORDER to "Camcorder (tuned for video)", MediaRecorder.AudioSource.MIC to "Microphone", MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed (raw)",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice recognition (flat)", MediaRecorder.AudioSource.VOICE_PERFORMANCE to "Voice performance (low latency)", MediaRecorder.AudioSource.DEFAULT to "Default"), a.source) { upd { x -> x.copy(source = it) } }
        PickerRow("Input device", listOf<Int?>(null).plus(devices.map { it.id }).map { id -> id to (if (id == null) "Automatic" else devices.first { it.id == id }.let { "${it.productName} (type ${it.type})" }) }, a.preferredInputDeviceId, "Bluetooth / USB microphones appear here when connected") { upd { x -> x.copy(preferredInputDeviceId = it) } }
        PickerRow("Sample rate", listOf(44100, 48000, 96000).map { it to "${it / 1000.0} kHz" }, a.sampleRate) { upd { x -> x.copy(sampleRate = it) } }
        PickerRow("Channels", listOf(1 to "Mono", 2 to "Stereo"), a.channels) { upd { x -> x.copy(channels = it) } }
        PickerRow("AAC bitrate", listOf(64, 96, 128, 192, 256, 320, 384, 512).map { it to "$it kb/s" }, a.bitrateKbps) { upd { x -> x.copy(bitrateKbps = it) } }
        PickerRow("Codec", AudioCodec.entries.map { it to it.label }, a.codec) { upd { x -> x.copy(codec = it) } }
    }
    SectionCard("Remove trigger sounds", "Silences the clap, snap, whistle or spoken command that fired a rule — and the confirmation beep — so they are not heard in the recording") {
        SwitchRow("Remove trigger sounds from recordings", a.scrubTriggerSounds, "Holds the audio back briefly before encoding; video and sync are unaffected") { upd { x -> x.copy(scrubTriggerSounds = it) } }
        if (a.scrubTriggerSounds) {
            PickerRow("How", ScrubMode.entries.map { it to it.label }, a.scrubMode) { upd { x -> x.copy(scrubMode = it) } }
            IntSliderRow("Audio hold-back", a.scrubDelayMs / 100, 5, 50, suffix = "00 ms", subtitle = "Must cover the time between the sound and the rule firing: ~1 s for claps, 2.5–3.5 s for voice commands (recognizer latency)") { upd { x -> x.copy(scrubDelayMs = it * 100) } }
            IntSliderRow("Voice command length", a.scrubVoicePhraseMs / 100, 5, 40, suffix = "00 ms", subtitle = "Silence this long before a recognized command") { upd { x -> x.copy(scrubVoicePhraseMs = it * 100) } }
            IntSliderRow("Padding around each sound", a.scrubPadMs / 50, 0, 10, suffix = "×50 ms") { upd { x -> x.copy(scrubPadMs = it * 50) } }
            SwitchRow("Also silence the confirmation beep", a.scrubFeedbackSounds) { upd { x -> x.copy(scrubFeedbackSounds = it) } }
        }
    }
    SectionCard("Processing") {
        SliderRow("Digital gain", a.gainDb, -12f..24f, format = { "%+.0f dB".format(it) }) { upd { x -> x.copy(gainDb = it.roundToInt().toFloat()) } }
        PickerRow("Wind / rumble high-pass", listOf(0, 60, 80, 120, 160, 200).map { it to (if (it == 0) "Off" else "$it Hz") }, a.highPassHz) { upd { x -> x.copy(highPassHz = it) } }
        SwitchRow("Limiter", a.limiter, "Prevents clipping on claps and shouts") { upd { x -> x.copy(limiter = it) } }
        SwitchRow("Mute (record silent track)", a.muteWhileRecording) { upd { x -> x.copy(muteWhileRecording = it) } }
        PickerRow("Privacy-sensitive capture", listOf<Boolean?>(null, true, false).map { it to when (it) { null -> "System default"; true -> "Yes (exclusive microphone)"; false -> "No (lets assistants share the mic)" } }, a.privacySensitive, "Affects whether other apps (system speech recognizer) can hear audio while we record") { upd { x -> x.copy(privacySensitive = it) } }
        SwitchRow("Ignore our own beeps in audio triggers", a.triggerFeedbackDucking) { upd { x -> x.copy(triggerFeedbackDucking = it) } }
    }
}

// ------------------------------------------------------------------------------------------------
// Camera
// ------------------------------------------------------------------------------------------------

@Composable
private fun CameraTab(graph: AppGraph, s: AppSettings, nav: (Screen) -> Unit) {
    fun upd(f: (CaptureSettings) -> CaptureSettings) = graph.settings.update { it.copy(capture = f(it.capture)) }
    val c = s.capture
    var infos by remember { mutableStateOf(graph.catalog.allInfos(false, 0)) }
    LaunchedEffect(c.probeHiddenCameraIds, c.hiddenIdProbeMax) {
        infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.catalog.allInfos(c.probeHiddenCameraIds, c.hiddenIdProbeMax) }
    }
    val cameraId = c.cameraId ?: graph.catalog.defaultBackId()
    val chars = remember(cameraId) { runCatching { cameraId?.let { graph.catalog.characteristics(it) } }.getOrNull() }
    val physical = remember(cameraId) { cameraId?.let { graph.catalog.physicalInfos(it) } ?: emptyList() }

    SectionCard("Camera & lens") {
        PickerRow("Camera", infos.map { it.id to "${it.shortName}${it.maxVideoSize?.let { m -> " · ${com.ultrax26.recorder.ui.camera.resName(m.width, m.height)}" } ?: ""}" }, cameraId ?: "") { upd { x -> x.copy(cameraId = it, lockedPhysicalCameraId = null, zoomRatio = 1f) } }
        SwitchRow("Probe hidden camera IDs", c.probeHiddenCameraIds, "Samsung hides per-lens sensors behind IDs such as 20/21/23/50…") { upd { x -> x.copy(probeHiddenCameraIds = it) } }
        PickerRow("Lens mode", LensMode.entries.map { it to it.label }, c.lensMode, if (physical.isEmpty()) "This camera exposes no physical sub-cameras to third-party apps" else null) { upd { x -> x.copy(lensMode = it) } }
        if (c.lensMode == LensMode.LOCK_PHYSICAL && physical.isNotEmpty()) PickerRow("Physical camera", physical.map { it.id to it.shortName }, c.lockedPhysicalCameraId ?: physical.first().id) { upd { x -> x.copy(lockedPhysicalCameraId = it) } }
        SliderRow("Zoom step (gesture / button)", c.zoomStep, 0.1f..2f, format = { "%.1f×".format(it) }) { upd { x -> x.copy(zoomStep = it) } }
        SliderRow("Zoom ramp speed", c.zoomRampSpeed, 0.2f..10f, format = { "%.1f×/s".format(it) }) { upd { x -> x.copy(zoomRampSpeed = it) } }
        Row { Button(onClick = { nav(Screen.AllKeys) }) { Text("All camera keys (incl. vendor tags)") } ; Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { upd { x -> CaptureSettings(cameraId = x.cameraId, probeHiddenCameraIds = x.probeHiddenCameraIds) } }) { Text("Reset") } }
    }
    if (chars == null) { Placeholder("Camera characteristics unavailable"); return }

    SectionCard("Exposure") {
        NullableIntPickerRow("AE mode", Capabilities.aeModes(chars).map { it.value to it.label }, c.aeMode) { upd { x -> x.copy(aeMode = it) } }
        SwitchRow("Manual exposure (ISO + shutter)", c.manualExposure, "Use the on-screen ISO / Shutter controls to set values", enabled = Capabilities.isoRange(chars) != null) { upd { x -> x.copy(manualExposure = it) } }
        val ev = Capabilities.evRange(chars); val step = Capabilities.evStep(chars)
        if (ev.upper > ev.lower) IntSliderRow("Exposure compensation", c.exposureCompensation, ev.lower, ev.upper, suffix = " (${"%+.1f".format(c.exposureCompensation * step)} EV)") { upd { x -> x.copy(exposureCompensation = it) } }
        SwitchRow("AE lock", c.aeLock) { upd { x -> x.copy(aeLock = it) } }
        NullableIntPickerRow("Anti-banding", Capabilities.antibandingModes(chars).map { it.value to it.label }, c.aeAntibanding) { upd { x -> x.copy(aeAntibanding = it) } }
        val ranges = Capabilities.fpsRanges(chars)
        PickerRow("AE target FPS range", listOf<IntRangeS?>(null).plus(ranges.map { IntRangeS(it.lower, it.upper) }).map { it to (it?.let { r -> "[${r.lower}, ${r.upper}]" } ?: "Auto (match video fps)") }, c.targetFpsRange) { upd { x -> x.copy(targetFpsRange = it) } }
        if (Capabilities.flashAvailable(chars)) {
            NullableIntPickerRow("Flash mode", Capabilities.flashModes.map { it.value to it.label }, c.flashMode, autoLabel = "Off") { upd { x -> x.copy(flashMode = it) } }
            SwitchRow("Torch", c.torch) { upd { x -> x.copy(torch = it) } }
            val maxT = Capabilities.torchMaxStrength(chars)
            if (maxT > 1) IntSliderRow("Torch strength", c.flashStrengthLevel ?: maxT, 1, maxT) { upd { x -> x.copy(flashStrengthLevel = it) } }
        }
        Capabilities.postRawBoostRange(chars)?.let { r -> if (r.upper > r.lower) IntSliderRow("Post-RAW sensitivity boost", c.postRawSensitivityBoost ?: 100, r.lower, r.upper, step = 10) { upd { x -> x.copy(postRawSensitivityBoost = it) } } }
    }

    SectionCard("Focus") {
        NullableIntPickerRow("AF mode", Capabilities.afModes(chars).map { it.value to it.label }, c.afMode, autoLabel = "Continuous video (default)") { upd { x -> x.copy(afMode = it) } }
        val minF = Capabilities.minFocusDistance(chars)
        SwitchRow("Manual focus", c.manualFocus, if (minF > 0f) "0 = infinity … ${"%.1f".format(minF)} diopters (${"%.2f".format(1f / minF)} m)" else "Fixed-focus camera", enabled = minF > 0f) { upd { x -> x.copy(manualFocus = it) } }
        if (minF > 0f) {
            SliderRow("Focus distance", c.focusDistance ?: 0f, 0f..minF, format = { if (it <= 0.01f) "∞" else "%.2f m".format(1f / it) }, enabled = c.manualFocus) { upd { x -> x.copy(focusDistance = it) } }
            SliderRow("Rack focus A", c.focusPullFrom, 0f..minF, format = { if (it <= 0.01f) "∞" else "%.2f m".format(1f / it) }) { upd { x -> x.copy(focusPullFrom = it) } }
            SliderRow("Rack focus B", c.focusPullTo, 0f..minF, format = { if (it <= 0.01f) "∞" else "%.2f m".format(1f / it) }) { upd { x -> x.copy(focusPullTo = it) } }
            IntSliderRow("Rack duration", (c.focusPullDurationMs / 100).toInt(), 2, 100, suffix = "00 ms") { upd { x -> x.copy(focusPullDurationMs = it * 100L) } }
        }
    }

    SectionCard("White balance & color") {
        NullableIntPickerRow("AWB mode", Capabilities.awbModes(chars).map { it.value to it.label }, c.awbMode, autoLabel = "Auto") { upd { x -> x.copy(awbMode = it, manualWhiteBalance = false) } }
        SwitchRow("AWB lock", c.awbLock) { upd { x -> x.copy(awbLock = it) } }
        SwitchRow("Manual white balance (Kelvin)", c.manualWhiteBalance) { upd { x -> x.copy(manualWhiteBalance = it) } }
        IntSliderRow("Color temperature", c.whiteBalanceKelvin, 2000, 10000, step = 50, suffix = " K", enabled = c.manualWhiteBalance) { upd { x -> x.copy(whiteBalanceKelvin = it) } }
        SliderRow("Tint (green ↔ magenta)", c.whiteBalanceTint, -1f..1f, format = { "%+.2f".format(it) }, enabled = c.manualWhiteBalance) { upd { x -> x.copy(whiteBalanceTint = it) } }
        NullableIntPickerRow("Color correction mode", listOf(0 to "Transform matrix", 1 to "Fast", 2 to "High quality"), c.colorCorrectionMode) { upd { x -> x.copy(colorCorrectionMode = it) } }
        NullableIntPickerRow("Chromatic aberration correction", Capabilities.aberrationModes(chars).map { it.value to it.label }, c.colorCorrectionAberration) { upd { x -> x.copy(colorCorrectionAberration = it) } }
        TextFieldRow("Manual RGGB gains (4 values, optional)", c.colorCorrectionGains.joinToString(", "), { t -> upd { x -> x.copy(colorCorrectionGains = t.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 4 } ?: emptyList()) } }, "Overrides the Kelvin model when exactly 4 numbers are given")
    }

    SectionCard("Stabilization") {
        NullableIntPickerRow("Video stabilization (EIS)", Capabilities.videoStabilizationModes(chars).map { it.value to it.label }, c.videoStabilization) { upd { x -> x.copy(videoStabilization = it) } }
        NullableIntPickerRow("Optical stabilization (OIS)", Capabilities.oisModes(chars).map { it.value to it.label }, c.opticalStabilization) { upd { x -> x.copy(opticalStabilization = it) } }
    }

    SectionCard("Processing") {
        NullableIntPickerRow("Noise reduction", Capabilities.noiseReductionModes(chars).map { it.value to it.label }, c.noiseReduction) { upd { x -> x.copy(noiseReduction = it) } }
        NullableIntPickerRow("Edge enhancement", Capabilities.edgeModes(chars).map { it.value to it.label }, c.edgeMode) { upd { x -> x.copy(edgeMode = it) } }
        PickerRow("Tone mapping", TonemapPreset.entries.map { it to it.label }, c.tonemapPreset, "Max curve points: ${Capabilities.tonemapMaxPoints(chars)}") { upd { x -> x.copy(tonemapPreset = it) } }
        if (c.tonemapPreset == TonemapPreset.DEVICE) NullableIntPickerRow("Tone-map mode", Capabilities.tonemapModes(chars).map { it.value to it.label }, c.tonemapMode) { upd { x -> x.copy(tonemapMode = it) } }
        if (c.tonemapPreset == TonemapPreset.GAMMA) SliderRow("Gamma", c.tonemapGamma, 0.5f..4f) { upd { x -> x.copy(tonemapGamma = it) } }
        if (c.tonemapPreset == TonemapPreset.CUSTOM) TextFieldRow("Custom curve (in,out pairs)", c.tonemapCustomCurve.joinToString(", "), { t -> upd { x -> x.copy(tonemapCustomCurve = t.split(',').mapNotNull { it.trim().toFloatOrNull() }) } }, "e.g. 0,0, 0.25,0.4, 0.5,0.7, 1,1")
        NullableIntPickerRow("Distortion correction", Capabilities.distortionModes(chars).map { it.value to it.label }, c.distortionCorrection) { upd { x -> x.copy(distortionCorrection = it) } }
        NullableIntPickerRow("Hot pixel correction", Capabilities.hotPixelModes(chars).map { it.value to it.label }, c.hotPixelMode) { upd { x -> x.copy(hotPixelMode = it) } }
        NullableIntPickerRow("Lens shading correction", Capabilities.shadingModes(chars).map { it.value to it.label }, c.shadingMode) { upd { x -> x.copy(shadingMode = it) } }
        NullableIntPickerRow("Scene mode", Capabilities.sceneModes(chars).map { it.value to it.label }, c.sceneMode, autoLabel = "None") { upd { x -> x.copy(sceneMode = it) } }
        NullableIntPickerRow("Effect", Capabilities.effectModes(chars).map { it.value to it.label }, c.effectMode, autoLabel = "None") { upd { x -> x.copy(effectMode = it) } }
        val ext = Capabilities.extendedSceneModes(chars)
        if (ext.size > 1) NullableIntPickerRow("Extended scene mode (bokeh)", ext.map { it.value to it.label }, c.extendedSceneMode, autoLabel = "Off") { upd { x -> x.copy(extendedSceneMode = it) } }
        NullableIntPickerRow("Face detection (HAL)", Capabilities.faceDetectModes(chars).map { it.value to it.label }, c.faceDetectMode) { upd { x -> x.copy(faceDetectMode = it) } }
        NullableIntPickerRow("Control mode", Capabilities.controlModes(chars).map { it.value to it.label }, c.controlMode) { upd { x -> x.copy(controlMode = it) } }
        NullableIntPickerRow("Capture intent", Capabilities.captureIntents.map { it.value to it.label }, c.captureIntent, autoLabel = "Video record") { upd { x -> x.copy(captureIntent = it) } }
        SwitchRow("Black level lock", c.blackLevelLock) { upd { x -> x.copy(blackLevelLock = it) } }
        val ovr = Capabilities.settingsOverrides(chars)
        if (ovr.isNotEmpty()) NullableIntPickerRow("Settings override (low-latency zoom)", ovr.map { it.value to it.label }, c.settingsOverride) { upd { x -> x.copy(settingsOverride = it) } }
        if (Capabilities.autoframingAvailable(chars)) NullableIntPickerRow("Autoframing", listOf(0 to "Off", 1 to "On", 2 to "Auto"), c.autoframing) { upd { x -> x.copy(autoframing = it) } }
        val aps = Capabilities.apertures(chars); if (aps.size > 1) PickerRow("Aperture", listOf<Float?>(null).plus(aps.toList()).map { it to (it?.let { a -> "f/%.1f".format(a) } ?: "Auto") }, c.lensAperture) { upd { x -> x.copy(lensAperture = it) } }
        val nd = Capabilities.filterDensities(chars); if (nd.size > 1) PickerRow("ND filter density", listOf<Float?>(null).plus(nd.toList()).map { it to (it?.let { d -> "%.1f EV".format(d) } ?: "Auto") }, c.lensFilterDensity) { upd { x -> x.copy(lensFilterDensity = it) } }
        val fl = Capabilities.focalLengths(chars); if (fl.size > 1) PickerRow("Focal length", listOf<Float?>(null).plus(fl.toList()).map { it to (it?.let { f -> "%.2f mm".format(f) } ?: "Auto") }, c.lensFocalLength) { upd { x -> x.copy(lensFocalLength = it) } }
        NullableIntPickerRow("Sensor test pattern", Capabilities.testPatternModes(chars).map { it.value to it.label }, c.testPatternMode, autoLabel = "Off") { upd { x -> x.copy(testPatternMode = it) } }
        SwitchRow("Lens shading map statistics", c.statisticsLensShadingMap) { upd { x -> x.copy(statisticsLensShadingMap = it) } }
        SwitchRow("OIS data statistics", c.statisticsOisData) { upd { x -> x.copy(statisticsOisData = it) } }
        SwitchRow("Hot pixel map statistics", c.statisticsHotPixelMap) { upd { x -> x.copy(statisticsHotPixelMap = it) } }
        IntSliderRow("Frame-grab JPEG quality", c.jpegQuality, 50, 100) { upd { x -> x.copy(jpegQuality = it) } }
    }
}

// ------------------------------------------------------------------------------------------------
// Overlays
// ------------------------------------------------------------------------------------------------

@Composable
private fun OverlaysTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (OverlaySettings) -> OverlaySettings) = graph.settings.update { it.copy(overlays = f(it.overlays)) }
    val o = s.overlays
    SectionCard("Composition") {
        PickerRow("Grid", GridType.entries.map { it to it.label }, o.grid) { upd { x -> x.copy(grid = it) } }
        PickerRow("Aspect guide", AspectGuide.entries.map { it to it.label }, o.aspectGuide) { upd { x -> x.copy(aspectGuide = it) } }
        SwitchRow("Safe areas", o.safeAreas) { upd { x -> x.copy(safeAreas = it) } }
        SwitchRow("Center marker", o.showCenterMarker) { upd { x -> x.copy(showCenterMarker = it) } }
        SwitchRow("Electronic level", o.level) { upd { x -> x.copy(level = it) } }
    }
    SectionCard("Exposure & focus scopes", "Computed from the analysis stream (~12 fps)") {
        SwitchRow("Histogram", o.histogram) { upd { x -> x.copy(histogram = it) } }
        SwitchRow("Waveform", o.waveform) { upd { x -> x.copy(waveform = it) } }
        SwitchRow("Zebra stripes", o.zebra) { upd { x -> x.copy(zebra = it) } }
        IntSliderRow("Zebra threshold", o.zebraThreshold, 70, 100, suffix = " %", enabled = o.zebra) { upd { x -> x.copy(zebraThreshold = it) } }
        SwitchRow("Focus peaking", o.focusPeaking) { upd { x -> x.copy(focusPeaking = it) } }
        IntSliderRow("Peaking threshold", o.focusPeakingThreshold, 5, 120, enabled = o.focusPeaking) { upd { x -> x.copy(focusPeakingThreshold = it) } }
        SwitchRow("False color", o.falseColor) { upd { x -> x.copy(falseColor = it) } }
    }
    SectionCard("Information") {
        SwitchRow("Exposure info (ISO, shutter, aperture, zoom)", o.exposureInfo) { upd { x -> x.copy(exposureInfo = it) } }
        SwitchRow("Audio meter", o.audioMeter) { upd { x -> x.copy(audioMeter = it) } }
        SwitchRow("Timecode", o.timecode) { upd { x -> x.copy(timecode = it) } }
        SwitchRow("Gesture HUD (hand skeletons, eyes, heard phrases)", o.gestureHud) { upd { x -> x.copy(gestureHud = it) } }
    }
    SectionCard("Appearance") {
        PickerRow("Theme", UiThemeMode.entries.map { it to it.label }, s.ui.theme) { t -> graph.settings.update { it.copy(ui = it.ui.copy(theme = t)) } }
        SwitchRow("Keep screen on", s.ui.keepScreenOn) { on -> graph.settings.update { it.copy(ui = it.ui.copy(keepScreenOn = on)) } }
    }
}

// ------------------------------------------------------------------------------------------------
// Storage
// ------------------------------------------------------------------------------------------------

@Composable
private fun StorageTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (StorageSettings) -> StorageSettings) = graph.settings.update { it.copy(storage = f(it.storage)) }
    val st = s.storage
    val ctx = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) { }
            upd { x -> x.copy(location = StorageLocation.SAF_TREE, safTreeUri = uri.toString()) }
        }
    }
    SectionCard("Destination") {
        PickerRow("Location", StorageLocation.entries.map { it to it.label }, st.location) { loc -> if (loc == StorageLocation.SAF_TREE && st.safTreeUri == null) treePicker.launch(null) else upd { x -> x.copy(location = loc) } }
        if (st.location == StorageLocation.SAF_TREE) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.safTreeUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: "No folder chosen", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Choose folder / SD card") }
        }
        TextFieldRow("Subfolder", st.subfolder, { t -> upd { x -> x.copy(subfolder = t.ifBlank { "UltraX26" }) } })
        TextFieldRow("File name template", st.fileNameTemplate, { t -> upd { x -> x.copy(fileNameTemplate = t.ifBlank { "UX26_{date}_{time}" }) } },
            "{date} {time} {res} {resname} {fps} {codec} {hdr} {lens} {seg}")
        IntSliderRow("Minimum free space to start", st.minFreeSpaceMb, 100, 5000, step = 100, suffix = " MB") { upd { x -> x.copy(minFreeSpaceMb = it) } }
        SwitchRow("JSON sidecar per clip", st.writeSidecarJson) { upd { x -> x.copy(writeSidecarJson = it) } }
        val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        SwitchRow("Geotag clips", st.geotag, "Uses the last known location when the location permission is granted") { on ->
            if (on) locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            upd { x -> x.copy(geotag = on) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Presets
// ------------------------------------------------------------------------------------------------

@Composable
private fun PresetsTab(graph: AppGraph, s: AppSettings) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(graph.settings.listPresets()) }
    var confirmReset by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(graph.settings.exportJson().toByteArray()) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { text -> graph.settings.importJson(text)?.let { graph.settings.replace(it) } }
    }
    SectionCard("Save current settings as a preset") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (name.isNotBlank()) { graph.settings.savePreset(name); presets = graph.settings.listPresets(); name = "" } }) { Text("Save") }
        }
    }
    SectionCard("Presets") {
        if (presets.isEmpty()) Text("No presets yet. Ideas: “8K cinematic”, “Vlog 4K60 HDR”, “Slow-mo 240”, “Interview (lock everything)”.", style = MaterialTheme.typography.bodySmall)
        presets.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p, modifier = Modifier.weight(1f))
                TextButton(onClick = { graph.settings.loadPreset(p)?.let { graph.settings.replace(it.copy(ui = it.ui.copy(lastPresetName = p))) } }) { Text("Load") }
                TextButton(onClick = { graph.settings.deletePreset(p); presets = graph.settings.listPresets() }) { Text("Delete") }
            }
        }
    }
    SectionCard("Import / export / reset") {
        Row {
            OutlinedButton(onClick = { exporter.launch("ultrax26-settings.json") }) { Text("Export JSON") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*")) }) { Text("Import JSON") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmReset = true }) { Text("Reset all") }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset all settings?") }, text = { Text("Rules, camera controls and presets selection return to defaults. Saved presets are kept.") },
        confirmButton = { TextButton(onClick = { graph.settings.resetToDefaults(); confirmReset = false }) { Text("Reset") } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------------
// Diagnostics / About
// ------------------------------------------------------------------------------------------------

@Composable
private fun DiagnosticsTab(graph: AppGraph, nav: (Screen) -> Unit) {
    val thermal by graph.thermal.state.collectAsStateWithLifecycle()
    val session by graph.controller.sessionInfo.collectAsStateWithLifecycle()
    val report by graph.engine.applyReport.collectAsStateWithLifecycle()
    SectionCard("Live") {
        KeyValue("Thermal", "${thermal.label}${if (!thermal.headroom.isNaN()) " · headroom ${(thermal.headroom * 100).roundToInt()}%" else ""}")
        session?.let { s ->
            KeyValue("Camera", "${s.cameraId} ${s.cameraInfo?.shortName ?: ""}")
            KeyValue("Recording format", "${s.recordSize.width}×${s.recordSize.height} @ ${s.fps} · ${s.codec.name} · ${s.hdr.name} · ${s.bitrate / 1_000_000} Mb/s")
            KeyValue("Encoder", s.encoderName)
            KeyValue("Gesture stream", if (s.analysis) "active" else "off")
            s.notes.forEach { KeyValue("Note", it) }
        }
        report?.let { KeyValue("Request keys", "applied ${it.applied.size} · skipped ${it.skipped.size} · errors ${it.errors.size}"); it.errors.take(5).forEach { e -> KeyValue("Error", e) } }
    }
    SectionCard("Device report", "Every camera characteristic, encoder and audio input this phone exposes") {
        Button(onClick = { nav(Screen.Diagnostics) }) { Text("Generate report") }
    }
}

@Composable
private fun EffectsTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (com.ultrax26.recorder.effects.EffectsSettings) -> com.ultrax26.recorder.effects.EffectsSettings) = graph.settings.update { it.copy(effects = f(it.effects)) }
    val fx = s.effects
    SectionCard("Effects pipeline", "The GL compositor sits between the camera and the encoder while any effect is active") {
        PickerRow("Render resolution", com.ultrax26.recorder.effects.RenderRes.entries.map { it to it.label }, fx.renderRes, "Recording is capped at this size while effects are on; HDR records as SDR") { upd { x -> x.copy(renderRes = it) } }
        SwitchRow("Keep pipeline on even with no effect", fx.forcePipeline, "Lets you toggle effects instantly mid-recording; costs battery") { upd { x -> x.copy(forcePipeline = it) } }
        SwitchRow("Head-tracked parallax on backgrounds", fx.headParallax) { upd { x -> x.copy(headParallax = it) } }
        SwitchRow("Multi-class segmentation (hair / face / clothes)", fx.useMultiClassSegmenter, "Needed for gray hair in the age looks; slower than the single-class model") { upd { x -> x.copy(useMultiClassSegmenter = it) } }
        SwitchRow("Body pose tracking for costume pieces", fx.poseTracking, "Off: shoulders/chest are estimated from the face") { upd { x -> x.copy(poseTracking = it) } }
    }
    SectionCard("Troubleshooting", "If stickers or masks appear flipped or offset on your phone, toggle these") {
        SwitchRow("Show face mask overlay", fx.debugMesh) { upd { x -> x.copy(debugMesh = it) } }
        SwitchRow("Flip camera texture vertically", fx.flipCameraY) { upd { x -> x.copy(flipCameraY = it) } }
        SwitchRow("Flip segmentation mask vertically", fx.flipMaskY) { upd { x -> x.copy(flipMaskY = it) } }
        SwitchRow("Invert head yaw for 3D stickers", fx.invertYaw) { upd { x -> x.copy(invertYaw = it) } }
        SwitchRow("Invert head pitch for 3D stickers", fx.invertPitch) { upd { x -> x.copy(invertPitch = it) } }
    }
}

@Composable
private fun CallsTab(graph: AppGraph, s: AppSettings) {
    fun upd(f: (CallSettings) -> CallSettings) = graph.settings.update { it.copy(calls = f(it.calls)) }
    val c = s.calls
    val st by graph.calls.state.collectAsStateWithLifecycle()
    SectionCard("Identity", "How you appear to the people you call") {
        TextFieldRow("Display name", c.displayName, { upd { x -> x.copy(displayName = it) } })
        TextFieldRow("My address", c.myAddress, { upd { x -> x.copy(myAddress = InviteLinks.sanitizeAddress(it)) } },
            subtitle = "Letters, digits and dashes. Others reach you at ux-<address> whenever the app is open. Blank = a random id per session. Takes effect on the next reconnect.")
        KeyValue("Online as", st.myPeerId ?: "—")
        KeyValue("Status", st.error ?: st.status.ifBlank { "offline" })
        SwitchRow("Reachable for incoming calls", c.availableForIncoming, "Keeps a signaling connection open while the app is on screen") { on -> upd { x -> x.copy(availableForIncoming = on) }; if (on) graph.calls.goOnline() else graph.calls.goOffline() }
        SwitchRow("Ring on incoming calls", c.ringtone) { upd { x -> x.copy(ringtone = it) } }
        Row { TextButton(onClick = { graph.calls.goOffline(); graph.calls.goOnline() }) { Text("Reconnect now") } }
    }
    SectionCard("Quality", "What you send; what you receive is up to the other side") {
        PickerRow("Send resolution", listOf(640 to 360, 960 to 540, 1280 to 720, 1920 to 1080).map { it to "${it.first}×${it.second}" }, c.sendWidth to c.sendHeight, "Applied when the next call starts") { upd { x -> x.copy(sendWidth = it.first, sendHeight = it.second) } }
        PickerRow("Send frame rate", listOf(15, 24, 30, 60).map { it to "$it fps" }, c.sendFps) { upd { x -> x.copy(sendFps = it) } }
        IntSliderRow("Max video bitrate", c.maxBitrateKbps, 300, 8000, 100, " kb/s") { upd { x -> x.copy(maxBitrateKbps = it) } }
        IntSliderRow("Max participants", c.maxParticipants, 2, 8, subtitle = "Group calls are peer-to-peer meshes; each extra person costs upload bandwidth") { upd { x -> x.copy(maxParticipants = it) } }
        SwitchRow("Start calls on speakerphone", c.speakerphone) { upd { x -> x.copy(speakerphone = it) } }
        SwitchRow("Apply effects and backgrounds to my call video", c.effectsInCalls, "Off: the call gets the plain camera image while recordings keep the effects") { upd { x -> x.copy(effectsInCalls = it) } }
    }
    SectionCard("Servers", "Defaults are free public services: PeerJS Cloud for signaling, Google STUN and Open Relay TURN for connectivity. Run your own with server/README.md.") {
        TextFieldRow("Web client URL", c.webClientUrl, { upd { x -> x.copy(webClientUrl = it) } }, subtitle = "The page your invite links open (web/call, published by GitHub Pages)")
        TextFieldRow("Signaling host", c.signalingHost, { upd { x -> x.copy(signalingHost = it.trim()) } })
        NumberFieldRow("Signaling port", c.signalingPort.toString(), { v -> v.trim().toIntOrNull()?.let { p -> upd { x -> x.copy(signalingPort = p) } } })
        TextFieldRow("Signaling path", c.signalingPath, { upd { x -> x.copy(signalingPath = it.trim().ifEmpty { "/" }) } })
        TextFieldRow("Signaling key", c.signalingKey, { upd { x -> x.copy(signalingKey = it.trim()) } })
        SwitchRow("Secure (wss)", c.signalingSecure) { upd { x -> x.copy(signalingSecure = it) } }
        Spacer(Modifier.height(6.dp))
        Text("ICE servers", style = MaterialTheme.typography.bodyLarge)
        Text("One per line: url [username credential]. STUN finds a direct path; TURN relays when a direct path is blocked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var iceText by remember(c.iceServers) { mutableStateOf(c.iceServers.joinToString("\n") { listOf(it.urls, it.username, it.credential).filter { p -> p.isNotBlank() }.joinToString(" ") }) }
        OutlinedTextField(iceText, { iceText = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        Row {
            TextButton(onClick = {
                val list = iceText.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line -> val p = line.split(Regex("\\s+")); IceServerSpec(p[0], p.getOrNull(1) ?: "", p.getOrNull(2) ?: "") }
                upd { x -> x.copy(iceServers = list) }
            }) { Text("Apply ICE servers") }
            TextButton(onClick = { val d = CallSettings(); upd { x -> x.copy(signalingHost = d.signalingHost, signalingPort = d.signalingPort, signalingPath = d.signalingPath, signalingKey = d.signalingKey, signalingSecure = d.signalingSecure, iceServers = d.iceServers, webClientUrl = d.webClientUrl) } }) { Text("Reset servers") }
        }
    }
    SectionCard("How it works") {
        Text("Create a link and send it by any messenger; the other person opens it in a browser (phone or computer) and is in the call — no account, no app. Two UltraX phones can also call each other by address. Video and audio travel directly between the devices (or through a TURN relay when a network blocks direct paths), encrypted with DTLS-SRTP; the signaling server only passes the connection handshake and never sees the media.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AboutTab() {
    SectionCard("UltraX 26", "Version ${BuildConfig.VERSION_NAME}") {
        Text("Pro video recorder for the Galaxy S26 Ultra with hands-free control: gestures (MediaPipe), face signals (ML Kit), claps / whistles / voice (built-in DSP and the system recognizer).", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text("Built on Camera2, MediaCodec and Jetpack Compose. No cloud services; all detection runs on the phone.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Third-party: MediaPipe Tasks (Apache-2.0), ML Kit Face Detection, AndroidX (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
    }
}
