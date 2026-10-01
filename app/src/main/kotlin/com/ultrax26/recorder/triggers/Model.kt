package com.ultrax26.recorder.triggers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a rule does to the recorder when its trigger fires. */
@Serializable
enum class RecAction(val label: String) {
    START("Start recording"),
    STOP("Stop recording"),
    PAUSE("Pause"),
    RESUME("Resume"),
    TOGGLE_RECORD("Start / stop"),
    TOGGLE_PAUSE("Pause / resume"),
    SNAPSHOT("Frame grab (JPEG)"),
    MARK("Drop chapter marker"),
    ARM("Arm triggers"),
    DISARM("Disarm triggers"),
    TOGGLE_ARM("Arm / disarm"),
    CANCEL_COUNTDOWN("Cancel countdown"),
    NEXT_LENS("Next lens"),
    PREV_LENS("Previous lens"),
    ZOOM_IN("Zoom in (step)"),
    ZOOM_OUT("Zoom out (step)"),
    TOGGLE_TORCH("Torch on / off"),
    TOGGLE_AE_LOCK("AE lock on / off"),
    TOGGLE_AF_LOCK("AF lock on / off"),
    ANSWER_CALL("Answer incoming video call"),
    HANG_UP("Hang up video call"),
    TOGGLE_CALL_MIC("Mute / unmute call microphone"),
}

/** Recorder lifecycle states a rule may be restricted to. */
@Serializable
enum class RecState { IDLE, COUNTDOWN, RECORDING, PAUSED, FINALIZING }

/** Hand gestures produced by the MediaPipe gesture recognizer (canned model) + a few derived ones. */
@Serializable
enum class HandGestureType(val label: String, val modelName: String?) {
    THUMB_UP("Thumbs up", "Thumb_Up"),
    THUMB_DOWN("Thumbs down", "Thumb_Down"),
    OPEN_PALM("Open palm", "Open_Palm"),
    CLOSED_FIST("Closed fist", "Closed_Fist"),
    VICTORY("Victory / peace", "Victory"),
    POINTING_UP("Pointing up", "Pointing_Up"),
    I_LOVE_YOU("I love you (ILY)", "ILoveYou"),
    // Derived by the interpreter from landmarks (not from the classifier):
    OK_SIGN("OK sign", null),
    THREE_FINGERS("Three fingers", null),
    FOUR_FINGERS("Four fingers", null),
    PINCH("Pinch (thumb+index)", null),
    ROCK_ON("Rock on (index+pinky)", null),
    CALL_ME("Call me (thumb+pinky)", null);

    companion object {
        fun fromModelName(name: String): HandGestureType? = entries.firstOrNull { it.modelName == name }
    }
}

@Serializable
enum class Handedness { LEFT, RIGHT }

@Serializable
enum class Eye { LEFT, RIGHT, ANY }

@Serializable
enum class TiltDirection { LEFT, RIGHT, ANY }

@Serializable
enum class VoiceEngine(val label: String) {
    SYSTEM("System speech recognizer"),
    KEYWORD("Built-in trained keyword spotter"),
    BOTH("Both"),
}

@Serializable
enum class VolumeKey { UP, DOWN, ANY }

/**
 * A trigger is the *condition* half of a rule. Each variant carries only the parameters a user tunes;
 * live detection state lives in the interpreters and the engine.
 */
@Serializable
enum class PinchDirection(val label: String) { IN("Pinch (fingers close) — zoom out"), OUT("Unpinch (fingers spread) — zoom in"), ANY("Either") }

@Serializable
sealed class Trigger {
    abstract val label: String
    abstract val category: String

    // ---- Hand (MediaPipe) ------------------------------------------------------------------
    @Serializable @SerialName("hand_gesture")
    data class HandGesture(
        val gesture: HandGestureType = HandGestureType.THUMB_UP,
        val holdMs: Long = 600,
        val minScore: Float = 0.6f,
        val hand: Handedness? = null,
    ) : Trigger() {
        override val label get() = "${gesture.label} (hold ${holdMs}ms)"
        override val category get() = "Hand"
    }

    @Serializable @SerialName("hand_wave")
    data class HandWave(val minSwings: Int = 3, val windowMs: Long = 2000) : Trigger() {
        override val label get() = "Wave ($minSwings swings)"
        override val category get() = "Hand"
    }

    @Serializable @SerialName("visual_clap")
    data class VisualClap(val count: Int = 1) : Trigger() {
        override val label get() = "Hands clap together (seen)${if (count > 1) " ×$count" else ""}"
        override val category get() = "Hand"
    }

    @Serializable @SerialName("hands_up")
    data class HandsUp(val holdMs: Long = 800) : Trigger() {
        override val label get() = "Both hands raised (hold ${holdMs}ms)"
        override val category get() = "Hand"
    }

    @Serializable @SerialName("gesture_sequence")
    data class GestureSequence(
        val steps: List<HandGestureType> = listOf(HandGestureType.OPEN_PALM, HandGestureType.CLOSED_FIST),
        val stepTimeoutMs: Long = 2500,
    ) : Trigger() {
        override val label get() = steps.joinToString(" → ") { it.label }
        override val category get() = "Hand"
    }

    @Serializable @SerialName("finger_count")
    data class FingerCount(val fingers: Int = 3, val holdMs: Long = 700) : Trigger() {
        override val label get() = "$fingers fingers up (hold ${holdMs}ms)"
        override val category get() = "Hand"
    }

    /** Thumb and index finger closing (IN) or spreading apart (OUT) within a short window. */
    @Serializable @SerialName("pinch")
    data class Pinch(val direction: PinchDirection = PinchDirection.OUT) : Trigger() {
        override val label get() = when (direction) { PinchDirection.IN -> "Pinch fingers"; PinchDirection.OUT -> "Unpinch fingers"; PinchDirection.ANY -> "Pinch / unpinch" }
        override val category get() = "Hand"
    }

    // ---- Face (ML Kit) ---------------------------------------------------------------------
    @Serializable @SerialName("blink")
    data class Blink(val count: Int = 3, val windowMs: Long = 2500) : Trigger() {
        override val label get() = "Blink ×$count fast"
        override val category get() = "Face"
    }

    @Serializable @SerialName("wink")
    data class Wink(val eye: Eye = Eye.ANY) : Trigger() {
        override val label get() = "Wink (${eye.name.lowercase()} eye)"
        override val category get() = "Face"
    }

    @Serializable @SerialName("smile")
    data class Smile(val holdMs: Long = 1000) : Trigger() {
        override val label get() = "Smile (hold ${holdMs}ms)"
        override val category get() = "Face"
    }

    @Serializable @SerialName("mouth_open")
    data class MouthOpen(val holdMs: Long = 700) : Trigger() {
        override val label get() = "Mouth open (hold ${holdMs}ms)"
        override val category get() = "Face"
    }

    @Serializable @SerialName("head_nod")
    data class HeadNod(val count: Int = 2, val windowMs: Long = 2500) : Trigger() {
        override val label get() = "Head nod ×$count"
        override val category get() = "Face"
    }

    @Serializable @SerialName("head_shake")
    data class HeadShake(val count: Int = 2, val windowMs: Long = 2500) : Trigger() {
        override val label get() = "Head shake ×$count"
        override val category get() = "Face"
    }

    @Serializable @SerialName("head_tilt")
    data class HeadTilt(val direction: TiltDirection = TiltDirection.ANY, val holdMs: Long = 800) : Trigger() {
        override val label get() = "Head tilt ${direction.name.lowercase()} (hold ${holdMs}ms)"
        override val category get() = "Face"
    }

    @Serializable @SerialName("face_appears")
    data class FaceAppears(val stableMs: Long = 600) : Trigger() {
        override val label get() = "Subject enters frame"
        override val category get() = "Face"
    }

    @Serializable @SerialName("face_disappears")
    data class FaceDisappears(val timeoutMs: Long = 4000) : Trigger() {
        override val label get() = "Subject leaves frame (${timeoutMs / 1000}s)"
        override val category get() = "Face"
    }

    // ---- Audio -----------------------------------------------------------------------------
    @Serializable @SerialName("clap")
    data class Clap(val count: Int = 2) : Trigger() {
        override val label get() = if (count == 1) "Single clap" else "Clap ×$count"
        override val category get() = "Audio"
    }

    @Serializable @SerialName("snap")
    data class Snap(val count: Int = 1) : Trigger() {
        override val label get() = if (count == 1) "Finger snap" else "Finger snap ×$count"
        override val category get() = "Audio"
    }

    @Serializable @SerialName("whistle")
    data class Whistle(val minDurationMs: Long = 400) : Trigger() {
        override val label get() = "Whistle (≥${minDurationMs}ms)"
        override val category get() = "Audio"
    }

    @Serializable @SerialName("loud_sound")
    data class LoudSound(val thresholdDbfs: Float = -8f) : Trigger() {
        override val label get() = "Loud sound (> ${thresholdDbfs.toInt()} dBFS)"
        override val category get() = "Audio"
    }

    @Serializable @SerialName("voice")
    data class VoiceCommand(
        val phrase: String = "start recording",
        val engine: VoiceEngine = VoiceEngine.BOTH,
        val aliases: List<String> = emptyList(),
    ) : Trigger() {
        override val label get() = "Say “$phrase”"
        override val category get() = "Voice"
        fun allPhrases(): List<String> = (listOf(phrase) + aliases).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    // ---- Device ----------------------------------------------------------------------------
    @Serializable @SerialName("volume_key")
    data class VolumeKeyPress(val key: VolumeKey = VolumeKey.ANY, val longPress: Boolean = false) : Trigger() {
        override val label get() = "Volume ${key.name.lowercase()}${if (longPress) " long-press" else ""}"
        override val category get() = "Device"
    }

    @Serializable @SerialName("bt_button")
    data class BluetoothButton(val keyCode: Int? = null) : Trigger() {
        override val label get() = "Bluetooth remote / headset button"
        override val category get() = "Device"
    }

    @Serializable @SerialName("shake")
    data class Shake(val thresholdG: Float = 2.2f) : Trigger() {
        override val label get() = "Shake the phone"
        override val category get() = "Device"
    }

    @Serializable @SerialName("proximity_wave")
    data class ProximityWave(val count: Int = 2, val windowMs: Long = 1500) : Trigger() {
        override val label get() = "Wave over proximity sensor ×$count"
        override val category get() = "Device"
    }

    @Serializable @SerialName("timer")
    data class Timer(val seconds: Int = 10) : Trigger() {
        override val label get() = "Timer (${seconds}s after arming)"
        override val category get() = "Device"
    }
}

/**
 * A live observation produced by a detector/interpreter. The engine matches these against rules.
 * `Held` style events are emitted repeatedly while the condition persists (with growing heldMs);
 * discrete events are emitted once per occurrence.
 */
sealed class TriggerEvent {
    abstract val timestampMs: Long

    data class HandHeld(
        val gesture: HandGestureType, val handedness: Handedness?, val score: Float, val heldMs: Long,
        override val timestampMs: Long,
    ) : TriggerEvent()
    data class HandReleased(val gesture: HandGestureType, val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class Wave(val swings: Int, override val timestampMs: Long) : TriggerEvent()
    data class VisualClap(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class HandsUpHeld(val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class HandsUpReleased(override val timestampMs: Long) : TriggerEvent()
    data class FingersHeld(val fingers: Int, val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class FingersReleased(override val timestampMs: Long) : TriggerEvent()
    /** Discrete pinch: fingers went from spread to touching (IN) or touching to spread (OUT). */
    data class Pinch(val direction: PinchDirection, val ratio: Float, override val timestampMs: Long) : TriggerEvent()
    /** Continuous pinch-zoom: spread relative to where the pinch-hold started (1.0 = unchanged). */
    data class PinchScale(val scale: Float, val start: Boolean, override val timestampMs: Long) : TriggerEvent()

    data class BlinkBurst(val count: Int, val durationMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class Wink(val eye: Eye, override val timestampMs: Long) : TriggerEvent()
    data class SmileHeld(val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class SmileReleased(override val timestampMs: Long) : TriggerEvent()
    data class MouthOpenHeld(val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class MouthOpenReleased(override val timestampMs: Long) : TriggerEvent()
    data class HeadNod(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class HeadShake(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class HeadTiltHeld(val direction: TiltDirection, val heldMs: Long, override val timestampMs: Long) : TriggerEvent()
    data class HeadTiltReleased(override val timestampMs: Long) : TriggerEvent()
    data class FaceAppeared(override val timestampMs: Long) : TriggerEvent()
    data class FaceGone(val goneMs: Long, override val timestampMs: Long) : TriggerEvent()

    data class ClapBurst(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class SnapBurst(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class Whistle(val durationMs: Long, val frequencyHz: Float, override val timestampMs: Long) : TriggerEvent()
    data class Loud(val peakDbfs: Float, override val timestampMs: Long) : TriggerEvent()
    data class Voice(val text: String, val confidence: Float, val engine: VoiceEngine, override val timestampMs: Long) : TriggerEvent()

    data class VolumeKey(val key: com.ultrax26.recorder.triggers.VolumeKey, val longPress: Boolean, override val timestampMs: Long) : TriggerEvent()
    data class BluetoothKey(val keyCode: Int, override val timestampMs: Long) : TriggerEvent()
    data class Shake(val peakG: Float, override val timestampMs: Long) : TriggerEvent()
    data class ProximityWaves(val count: Int, override val timestampMs: Long) : TriggerEvent()
    data class TimerElapsed(val seconds: Int, override val timestampMs: Long) : TriggerEvent()
}

/** One user-configured rule: when [trigger] fires, run [action]. */
@Serializable
data class TriggerRule(
    val id: String,
    val trigger: Trigger,
    val action: RecAction,
    val enabled: Boolean = true,
    val cooldownMs: Long = 2000,
    val onlyWhenArmed: Boolean = true,
    /** If non-null the rule only applies while the recorder is in one of these states. */
    val states: List<RecState>? = null,
    val name: String = "",
) {
    val displayName: String get() = name.ifBlank { "${trigger.label} → ${action.label}" }
}
