package com.ultrax26.recorder.triggers

/**
 * One thing the app can do on command: an action plus (optionally) its parameter. The catalog lists
 * every setting, function and action a voice phrase, gesture or sound can be bound to — from "start
 * recording" to "resolution 4K", "select the 5× camera" or "zoom to 2×". Pure Kotlin so it is
 * unit-testable; the UI feeds it the device's cameras, sizes and effect catalog.
 */
data class CommandSpec(
    val id: String,
    val title: String,
    val group: String,
    val action: RecAction,
    val param: String? = null,
    val keywords: String = "",
) {
    /** Suggested spoken phrase. */
    val phrase: String get() = title.lowercase().replace("×", "x").replace(Regex("[^a-z0-9 .+/-]"), "").replace(Regex("\\s+"), " ").trim()

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val hay = "$title $group $keywords ${action.label} ${param ?: ""}".lowercase()
        return q.split(Regex("\\s+")).all { hay.contains(it) }
    }
}

data class CommandCatalogInput(
    val cameras: List<Pair<String, String>> = emptyList(),           // id → name
    val resolutions: List<Pair<Int, Int>> = emptyList(),              // width × height for the current camera
    val fpsOptions: List<Int> = emptyList(),
    val lensPresets: List<Float> = emptyList(),
    val maxZoom: Float = 10f,
    val codecs: List<String> = listOf("HEVC", "AVC"),                 // RecAction.SET_CODEC params
    val hdrModes: List<String> = listOf("OFF", "HLG10"),
    val looks: List<Pair<String, String>> = emptyList(),              // id → name
    val stickers: List<Pair<String, String>> = emptyList(),
    val backgrounds: List<Pair<String, String>> = emptyList(),        // scene id → name
    val faceModes: List<Pair<String, String>> = emptyList(),          // enum name → label
    val funModes: List<Pair<String, String>> = emptyList(),
    val ageModes: List<Pair<String, String>> = emptyList(),
    val colorLooks: List<Pair<String, String>> = emptyList(),
    val tonePresets: List<Pair<String, String>> = emptyList(),
)

object CommandCatalog {
    const val RECORDING = "Recording"
    const val CAMERA = "Camera & lenses"
    const val ZOOM = "Zoom"
    const val FORMAT = "Video format"
    const val EXPOSURE = "Exposure"
    const val FOCUS = "Focus"
    const val COLOR = "White balance & tone"
    const val OVERLAYS = "Overlays"
    const val AUDIO = "Audio"
    const val EFFECTS = "Effects"
    const val BACKGROUNDS = "Backgrounds"
    const val STICKERS = "Stickers & costumes"
    const val TRIGGERS = "Triggers & timers"
    const val CALLS = "Video calls"

    fun build(i: CommandCatalogInput): List<CommandSpec> {
        val out = ArrayList<CommandSpec>(400)
        fun add(id: String, title: String, group: String, action: RecAction, param: String? = null, kw: String = "") { out += CommandSpec(id, title, group, action, param, kw) }

        // ---- recording ----
        add("start", "Start recording", RECORDING, RecAction.START, kw = "record action rolling go")
        add("stop", "Stop recording", RECORDING, RecAction.STOP, kw = "cut end finish")
        add("toggle-record", "Start / stop recording", RECORDING, RecAction.TOGGLE_RECORD)
        add("pause", "Pause recording", RECORDING, RecAction.PAUSE, kw = "hold")
        add("resume", "Resume recording", RECORDING, RecAction.RESUME, kw = "continue")
        add("toggle-pause", "Pause / resume", RECORDING, RecAction.TOGGLE_PAUSE)
        add("snapshot", "Take a photo", RECORDING, RecAction.SNAPSHOT, kw = "snapshot picture frame grab capture cheese")
        add("marker", "Drop a chapter marker", RECORDING, RecAction.MARK, kw = "mark chapter")
        listOf(3, 5, 10, 15, 30, 60).forEach { add("timer-$it", "Start recording in $it seconds", RECORDING, RecAction.START_TIMER, "$it", "timer delay self") }
        listOf(0, 3, 5, 10).forEach { add("countdown-$it", if (it == 0) "No countdown before triggered starts" else "Countdown $it seconds before triggered starts", TRIGGERS, RecAction.SET_COUNTDOWN, "$it") }
        add("arm", "Arm hands-free triggers", TRIGGERS, RecAction.ARM, kw = "standby listen")
        add("disarm", "Disarm hands-free triggers", TRIGGERS, RecAction.DISARM, kw = "stand down")
        add("toggle-arm", "Arm / disarm triggers", TRIGGERS, RecAction.TOGGLE_ARM)
        add("cancel-countdown", "Cancel countdown", TRIGGERS, RecAction.CANCEL_COUNTDOWN)
        add("toggle-preroll", "Pre-roll buffer on / off", TRIGGERS, RecAction.TOGGLE_PREROLL, kw = "pre roll buffer")
        add("toggle-scrub", "Remove trigger sounds on / off", AUDIO, RecAction.TOGGLE_SCRUB, kw = "silence claps commands")

        // ---- cameras & zoom ----
        add("front-camera", "Front camera", CAMERA, RecAction.FRONT_CAMERA, kw = "selfie")
        add("back-camera", "Back camera", CAMERA, RecAction.BACK_CAMERA, kw = "rear main")
        add("flip-camera", "Flip camera", CAMERA, RecAction.FLIP_CAMERA, kw = "switch")
        i.cameras.forEach { (id, name) -> add("camera-$id", "Select camera $name", CAMERA, RecAction.SELECT_CAMERA, id, "lens sensor $id") }
        add("next-lens", "Next lens", ZOOM, RecAction.NEXT_LENS)
        add("prev-lens", "Previous lens", ZOOM, RecAction.PREV_LENS)
        add("zoom-in", "Zoom in", ZOOM, RecAction.ZOOM_IN, kw = "closer tighter")
        add("zoom-out", "Zoom out", ZOOM, RecAction.ZOOM_OUT, kw = "wider")
        val zooms = (i.lensPresets + listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)).filter { it <= i.maxZoom + 0.01f && it >= 0.5f }.map { (it * 10).toInt() / 10f }.distinct().sorted()
        zooms.forEach { z -> val label = if (z == z.toInt().toFloat()) "${z.toInt()}×" else "%.1f×".format(z); add("zoom-$label", "Zoom to $label", ZOOM, RecAction.SET_ZOOM, label.dropLast(1), "zoom $label times") }

        // ---- video format ----
        i.resolutions.forEach { (w, h) -> val name = resName(w, h); add("res-${w}x$h", "Resolution $name ($w×$h)", FORMAT, RecAction.SET_RESOLUTION, "${w}x$h", "size megapixel $name") }
        i.fpsOptions.forEach { f -> add("fps-$f", "Frame rate $f fps", FORMAT, RecAction.SET_FPS, "$f", "frames per second") }
        add("toggle-high-speed", "High-speed (slow motion) capture on / off", FORMAT, RecAction.TOGGLE_HIGH_SPEED, kw = "120 240 slow motion")
        i.codecs.forEach { c -> add("codec-$c", "Codec $c", FORMAT, RecAction.SET_CODEC, c, "encoder h264 h265") }
        i.hdrModes.forEach { h -> add("hdr-$h", "HDR $h", FORMAT, RecAction.SET_HDR, h, "10-bit dynamic range sdr") }
        listOf(0, 20, 50, 100, 200, 400).forEach { add("bitrate-$it", if (it == 0) "Bitrate automatic" else "Bitrate $it Mb/s", FORMAT, RecAction.SET_BITRATE, "$it", "quality megabits") }

        // ---- exposure ----
        add("auto-exposure", "Auto exposure", EXPOSURE, RecAction.AUTO_EXPOSURE, kw = "ae reset")
        add("ae-lock", "Exposure lock on / off", EXPOSURE, RecAction.TOGGLE_AE_LOCK, kw = "ae lock")
        listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f).forEach { ev -> val l = if (ev > 0) "+$ev" else "$ev"; add("ev-$l", "Exposure $l EV", EXPOSURE, RecAction.SET_EV, "$ev", "brighter darker compensation") }
        listOf(50, 100, 200, 400, 800, 1600, 3200, 6400).forEach { add("iso-$it", "ISO $it", EXPOSURE, RecAction.SET_ISO, "$it", "sensitivity gain") }
        listOf(30, 48, 50, 60, 100, 120, 250, 500, 1000, 2000).forEach { add("shutter-$it", "Shutter 1/$it s", EXPOSURE, RecAction.SET_SHUTTER, "$it", "exposure time speed") }
        add("torch", "Torch on / off", EXPOSURE, RecAction.TOGGLE_TORCH, kw = "flashlight light led")
        add("toggle-stabilization", "Stabilization on / off", CAMERA, RecAction.TOGGLE_STABILIZATION, kw = "eis ois steady")

        // ---- focus ----
        add("auto-focus", "Auto focus", FOCUS, RecAction.AUTO_FOCUS, kw = "af continuous")
        add("af-lock", "Focus lock on / off", FOCUS, RecAction.TOGGLE_AF_LOCK, kw = "af lock")
        add("focus-infinity", "Focus to infinity", FOCUS, RecAction.FOCUS_INFINITY, kw = "far landscape")
        add("focus-nearest", "Focus to the nearest distance", FOCUS, RecAction.FOCUS_NEAREST, kw = "macro close")
        add("rack-focus", "Rack focus A → B", FOCUS, RecAction.RACK_FOCUS, kw = "pull transition")

        // ---- white balance & tone ----
        listOf("auto" to "Auto white balance", "daylight" to "White balance daylight", "cloudy" to "White balance cloudy", "shade" to "White balance shade", "tungsten" to "White balance tungsten", "fluorescent" to "White balance fluorescent")
            .forEach { (p, t) -> add("wb-$p", t, COLOR, RecAction.SET_WB, p, "awb color temperature kelvin") }
        listOf(2800, 3200, 4000, 5000, 5600, 6500, 7500).forEach { k -> add("wb-$k", "White balance $k K", COLOR, RecAction.SET_WB, "$k", "kelvin color temperature") }
        i.tonePresets.forEach { (p, l) -> add("tone-$p", "Tone $l", COLOR, RecAction.SET_TONEMAP, p, "curve gamma log") }

        // ---- overlays ----
        listOf("grid" to "Grid", "level" to "Level", "histogram" to "Histogram", "waveform" to "Waveform", "zebra" to "Zebra stripes", "peaking" to "Focus peaking", "falsecolor" to "False color",
            "safeareas" to "Safe areas", "hud" to "Gesture HUD", "audiometer" to "Audio meter", "timecode" to "Timecode", "exposure" to "Exposure info", "center" to "Center marker")
            .forEach { (p, t) -> add("overlay-$p", "$t on / off", OVERLAYS, RecAction.TOGGLE_OVERLAY, p, "overlay monitor display") }

        // ---- audio ----
        add("toggle-audio", "Audio recording on / off", AUDIO, RecAction.TOGGLE_AUDIO, kw = "mute sound microphone")

        // ---- effects ----
        add("clear-effects", "Clear all effects", EFFECTS, RecAction.CLEAR_EFFECTS, kw = "reset filters off none")
        add("toggle-beauty", "Beauty on / off", EFFECTS, RecAction.TOGGLE_BEAUTY, kw = "smooth skin retouch")
        i.looks.forEach { (id, name) -> add("look-$id", "Look: $name", EFFECTS, RecAction.SET_LOOK, id, "filter preset costume") }
        i.faceModes.forEach { (p, l) -> add("face-$p", "Face mode $l", EFFECTS, RecAction.SET_FACE_MODE, p, "mask creature") }
        i.funModes.forEach { (p, l) -> add("fun-$p", "Fun effect $l", EFFECTS, RecAction.SET_FUN_MODE, p, "distortion stylize") }
        i.ageModes.forEach { (p, l) -> add("age-$p", "Age look $l", EFFECTS, RecAction.SET_AGE, p, "older younger") }
        i.colorLooks.forEach { (p, l) -> add("color-$p", "Color look $l", EFFECTS, RecAction.SET_COLOR_LOOK, p, "grade lut tint") }
        add("bg-none", "Real background", BACKGROUNDS, RecAction.SET_BACKGROUND, "none", "background off")
        add("bg-blur", "Blur background", BACKGROUNDS, RecAction.SET_BACKGROUND, "blur", "bokeh portrait")
        add("bg-color", "Solid color background", BACKGROUNDS, RecAction.SET_BACKGROUND, "color", "green screen")
        i.backgrounds.forEach { (id, name) -> add("bg-$id", "Background: $name", BACKGROUNDS, RecAction.SET_BACKGROUND, id, "scene virtual 3d") }
        i.stickers.forEach { (id, name) -> add("sticker-$id", "Sticker: $name", STICKERS, RecAction.TOGGLE_STICKER, id, "accessory costume ar") }

        // ---- calls ----
        add("answer", "Answer video call", CALLS, RecAction.ANSWER_CALL, kw = "pick up accept")
        add("hang-up", "Hang up video call", CALLS, RecAction.HANG_UP, kw = "end call decline")
        add("call-mic", "Call microphone mute / unmute", CALLS, RecAction.TOGGLE_CALL_MIC)
        return out
    }

    fun resName(w: Int, h: Int): String = when {
        w >= 7680 -> "8K"; w >= 3840 -> "4K"; w >= 2560 -> "1440p"; w >= 1920 -> "1080p"; w >= 1280 -> "720p"; else -> "${h}p"
    }
}
