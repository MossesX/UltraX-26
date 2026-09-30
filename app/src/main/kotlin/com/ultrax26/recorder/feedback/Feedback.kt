package com.ultrax26.recorder.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.ultrax26.recorder.settings.FeedbackConfig
import com.ultrax26.recorder.triggers.EngineEvent
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow

/** Haptic / tone / TTS / screen-flash confirmation that a trigger was recognized. */
class Feedback(context: Context) {
    private val tag = "Feedback"
    private val vibrator: Vibrator? = try {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } catch (_: Throwable) { null }
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile var config = FeedbackConfig()
    /** UI observes this to flash the screen; value = boot time of the last flash request + color hint. */
    val flash = MutableStateFlow<Pair<Long, Int>>(0L to 0)
    /** Audio detectors read this to ignore our own beeps. Boot-time ms until which to ignore. */
    @Volatile var suppressAudioUntilMs = 0L

    init {
        tts = try { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } } catch (_: Throwable) { null }
    }

    private fun toneGen(): ToneGenerator? {
        if (tone == null) tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, (config.beepVolume.coerceIn(0f, 1f) * 100).toInt()) } catch (_: Throwable) { null }
        return tone
    }

    fun onEngineEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Fired -> when (e.rule.action) {
                RecAction.START, RecAction.TOGGLE_RECORD -> confirm(strong = true, toneType = ToneGenerator.TONE_PROP_ACK, ms = 160, say = null, color = 0xFFEF4444.toInt())
                RecAction.STOP -> confirm(strong = true, toneType = ToneGenerator.TONE_PROP_NACK, ms = 220, say = if (config.speak) "Stopped" else null, color = 0xFFFFFFFF.toInt())
                RecAction.PAUSE, RecAction.TOGGLE_PAUSE, RecAction.RESUME -> confirm(false, ToneGenerator.TONE_PROP_BEEP2, 120, null, 0xFFF59E0B.toInt())
                RecAction.SNAPSHOT -> confirm(false, ToneGenerator.TONE_PROP_BEEP, 80, null, 0xFFFFFFFF.toInt())
                RecAction.ARM -> confirm(false, ToneGenerator.TONE_PROP_BEEP, 80, if (config.speak) "Armed" else null, 0xFF22C55E.toInt())
                RecAction.DISARM -> confirm(false, ToneGenerator.TONE_PROP_NACK, 120, if (config.speak) "Disarmed" else null, 0xFF64748B.toInt())
                else -> confirm(false, ToneGenerator.TONE_PROP_BEEP, 60, null, 0)
            }
            is EngineEvent.CountdownTick -> {
                if (config.beep) beep(if (e.secondsLeft <= 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 70)
                if (config.haptic) vibrate(false)
                if (config.speak && e.secondsLeft <= 3) speak(e.secondsLeft.toString())
                if (config.screenFlash) flash.value = Clock.bootMs() to 0xFFF59E0B.toInt()
            }
            is EngineEvent.CountdownCancelled -> confirm(false, ToneGenerator.TONE_PROP_NACK, 150, null, 0)
            is EngineEvent.Armed -> { }
            is EngineEvent.Ignored -> { }
        }
    }

    fun recordingStarted() { if (config.speak) speak("Recording") }

    private fun confirm(strong: Boolean, toneType: Int, ms: Int, say: String?, color: Int) {
        if (config.haptic) vibrate(strong)
        if (config.beep) beep(toneType, ms)
        if (say != null && config.speak) speak(say)
        if (config.screenFlash && color != 0) flash.value = Clock.bootMs() to color
    }

    private fun vibrate(strong: Boolean) {
        try {
            val eff = if (strong) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator?.vibrate(eff)
        } catch (t: Throwable) { UxLog.w(tag, "vibrate: ${t.message}") }
    }

    private fun beep(type: Int, ms: Int) {
        try {
            suppressAudioUntilMs = Clock.bootMs() + ms + 250
            toneGen()?.startTone(type, ms)
        } catch (t: Throwable) { UxLog.w(tag, "tone: ${t.message}") }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try {
            suppressAudioUntilMs = Clock.bootMs() + 1500
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ux-$text")
        } catch (_: Throwable) { }
    }

    fun release() {
        try { tone?.release() } catch (_: Throwable) { }
        tone = null
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
    }
}
