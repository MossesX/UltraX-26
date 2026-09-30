package com.ultrax26.recorder.triggers.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ultrax26.recorder.settings.VoiceConfig
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.VoiceEngine
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Continuous free-form recognition through the platform SpeechRecognizer (on-device when available).
 * Works great while idle/armed; during recording Android may hand the microphone to only one client,
 * which is why the trained [KeywordSpotter] exists as a second engine.
 */
class SystemSpeechRecognizer(private val context: Context, private val cfg: VoiceConfig, private val sink: (TriggerEvent) -> Unit) {
    private val tag = "SysSpeech"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var restartDelayMs = 300L
    val status = MutableStateFlow("off")
    val lastHeard = MutableStateFlow("")

    fun start() = main.post {
        if (running) return@post
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status.value = "unavailable"; return@post }
        running = true
        recognizer = try {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (t: Throwable) { UxLog.w(tag, "create failed: ${t.message}"); SpeechRecognizer.createSpeechRecognizer(context) }
        recognizer?.setRecognitionListener(listener)
        listen()
    }

    fun stop() = main.post {
        running = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Throwable) { }
        recognizer = null
        status.value = "off"
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, cfg.preferOffline)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        if (cfg.systemLanguageTag.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, cfg.systemLanguageTag)
    }

    private fun listen() {
        if (!running) return
        try { recognizer?.startListening(intent()); status.value = "listening" } catch (t: Throwable) { UxLog.w(tag, "startListening: ${t.message}"); scheduleRestart(1500) }
    }

    private fun scheduleRestart(delay: Long = restartDelayMs) { if (running) main.postDelayed({ listen() }, delay) }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.value = "listening" }
        override fun onBeginningOfSpeech() { status.value = "hearing…" }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { status.value = "processing" }
        override fun onError(error: Int) {
            status.value = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "listening"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "listening"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
                SpeechRecognizer.ERROR_AUDIO -> "mic busy"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
                else -> "error $error"
            }
            restartDelayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> 1500L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 10_000L
                else -> 250L
            }
            scheduleRestart()
        }
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            if (!list.isNullOrEmpty()) {
                val text = list[0]
                lastHeard.value = text
                sink(TriggerEvent.Voice(text.lowercase(), scores?.getOrNull(0) ?: 0.5f, VoiceEngine.SYSTEM, Clock.bootMs()))
            }
            restartDelayMs = 200L
            scheduleRestart()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { lastHeard.value = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) { }
    }
}
