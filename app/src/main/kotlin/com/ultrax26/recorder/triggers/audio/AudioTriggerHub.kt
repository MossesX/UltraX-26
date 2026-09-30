package com.ultrax26.recorder.triggers.audio

import com.ultrax26.recorder.recording.AudioCapture
import com.ultrax26.recorder.recording.PcmChunk
import com.ultrax26.recorder.settings.AudioTriggerConfig
import com.ultrax26.recorder.settings.VoiceConfig
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.VoiceEngine
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue

/** Live audio-trigger state for the HUD / trigger monitor. */
data class AudioHudState(
    val levelDbfs: Float = -120f,
    val backgroundDbfs: Float = -60f,
    val clapsInBurst: Int = 0,
    val lastClapBurst: Int = 0,
    val whistleHz: Float = 0f,
    val speechActive: Boolean = false,
    val lastKeyword: String? = null,
    val lastKeywordScore: Float = 0f,
    val lastKeywordSecond: Float = 0f,
    val keywordThreshold: Float = 0f,
    val enrolling: String? = null,
    val enrolledCounts: Map<String, Int> = emptyMap(),
    val muted: Boolean = false,
)

/**
 * Runs every audio detector on a dedicated thread fed by [AudioCapture]. Emits [TriggerEvent]s.
 */
class AudioTriggerHub(
    private val sampleRate: Int,
    audioCfg: AudioTriggerConfig,
    voiceCfg: VoiceConfig,
    initialTemplates: List<KeywordTemplate>,
    private val sink: (TriggerEvent) -> Unit,
) : AudioCapture.Listener {
    private val tag = "AudioHub"
    private val queue = LinkedBlockingQueue<PcmChunk>(64)
    private var thread: Thread? = null
    @Volatile private var running = false
    private val mono = FloatArray(sampleRate) // 1 s max chunk
    val hud = MutableStateFlow(AudioHudState())
    @Volatile var suppressUntilMs = 0L   // ignore detections while feedback tones play
    @Volatile var audioTriggersEnabled = audioCfg.enabled
    @Volatile var keywordEnabled = voiceCfg.enabled && voiceCfg.keywordSpotter
    var onTemplatesChanged: ((List<KeywordTemplate>) -> Unit)? = null

    private var clapCount = 0
    private val clapBurst = BurstCounter(audioCfg.clapMaxGapMs) { count, endMs ->
        clapCount = 0
        hud.value = hud.value.copy(lastClapBurst = count, clapsInBurst = 0)
        emit(TriggerEvent.ClapBurst(count, endMs))
    }
    private val snapBurst = BurstCounter(600) { count, endMs -> emit(TriggerEvent.SnapBurst(count, endMs)) }
    private val clap = TransientDetector(sampleRate, TransientDetector.Profile.clap(audioCfg.clapSensitivity)) { t, _ ->
        if (t >= suppressUntilMs) { clapCount++; hud.value = hud.value.copy(clapsInBurst = clapCount); clapBurst.hit(t) }
    }
    private val snap = TransientDetector(sampleRate, TransientDetector.Profile.snap(audioCfg.snapSensitivity)) { t, _ -> if (t >= suppressUntilMs) snapBurst.hit(t) }
    private val whistle = WhistleDetector(sampleRate, audioCfg.whistleSensitivity, 250) { t, dur, hz -> if (t >= suppressUntilMs) emit(TriggerEvent.Whistle(dur, hz, t)) }
    private val loud = LoudnessDetector(audioCfg.loudThresholdDbfs) { t, peak -> if (t >= suppressUntilMs) emit(TriggerEvent.Loud(peak, t)) }
    val spotter: KeywordSpotter = KeywordSpotter(sampleRate, voiceCfg.keywordSensitivity, voiceCfg.keywordMargin, voiceCfg.minUtteranceMs, voiceCfg.maxUtteranceMs, voiceCfg.silenceMs) { r ->
        if (r.enrolled) {
            onTemplatesChanged?.invoke(spotter.templates)
            hud.value = hud.value.copy(enrolling = null, enrolledCounts = counts(), lastKeyword = "enrolled: ${r.command}")
        } else {
            hud.value = hud.value.copy(lastKeyword = r.command ?: "(no match ${"%.2f".format(r.score)})", lastKeywordScore = r.score, lastKeywordSecond = r.secondScore, keywordThreshold = KeywordSpotter.thresholdFor(spotter.sensitivity))
            if (r.command != null && r.timeMs >= suppressUntilMs) emit(TriggerEvent.Voice(r.command, 1f - (r.score / KeywordSpotter.thresholdFor(spotter.sensitivity)).coerceIn(0f, 1f), VoiceEngine.KEYWORD, r.timeMs))
        }
    }.also { it.templates = initialTemplates }

    private fun counts(): Map<String, Int> = spotter.templates.groupingBy { it.command }.eachCount()

    private fun emit(e: TriggerEvent) { try { sink(e) } catch (t: Throwable) { UxLog.w(tag, "sink: ${t.message}") } }

    fun start() {
        if (running) return
        running = true
        hud.value = hud.value.copy(enrolledCounts = counts(), keywordThreshold = KeywordSpotter.thresholdFor(spotter.sensitivity))
        thread = Thread({ loop() }, "ux-audio-triggers").also { it.start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null; queue.clear() }

    override fun onPcm(chunk: PcmChunk) {
        if (!running) return
        if (!queue.offer(chunk)) { queue.poll(); queue.offer(chunk) }
    }

    fun beginEnrollment(command: String) { spotter.enrollingCommand = command; hud.value = hud.value.copy(enrolling = command) }
    fun cancelEnrollment() { spotter.enrollingCommand = null; hud.value = hud.value.copy(enrolling = null) }
    fun deleteCommand(command: String) { spotter.templates = spotter.templates.filter { it.command != command }; onTemplatesChanged?.invoke(spotter.templates); hud.value = hud.value.copy(enrolledCounts = counts()) }
    fun setTemplates(t: List<KeywordTemplate>) { spotter.templates = t; hud.value = hud.value.copy(enrolledCounts = counts()) }
    fun setKeywordSensitivity(s: Float, margin: Float) { spotter.sensitivity = s; spotter.relativeMargin = margin; hud.value = hud.value.copy(keywordThreshold = KeywordSpotter.thresholdFor(s)) }

    private fun loop() {
        while (running) {
            val chunk = try { queue.take() } catch (_: InterruptedException) { break }
            try {
                val n = minOf(chunk.frames, mono.size)
                Dsp.toMono(chunk.samples, n, chunk.channels, mono)
                val startMs = chunk.ptsNs / 1_000_000
                val nowMs = Clock.bootMs()
                if (audioTriggersEnabled) {
                    clap.process(mono, n, startMs)
                    snap.process(mono, n, startMs)
                    whistle.process(mono, n, startMs)
                    loud.process(Dsp.dbfs(chunk.peak.toDouble()), startMs)
                    clapBurst.tick(startMs + n * 1000L / sampleRate)
                    snapBurst.tick(startMs + n * 1000L / sampleRate)
                }
                if (keywordEnabled) spotter.process(mono, n, startMs)
                if (nowMs % 100 < 25) {
                    hud.value = hud.value.copy(levelDbfs = Dsp.dbfs(Dsp.rms(mono, n)), backgroundDbfs = clap.backgroundDb, whistleHz = whistle.currentFreq, speechActive = spotter.speechActive)
                }
            } catch (t: Throwable) { UxLog.w(tag, "detector failure: ${t.message}") }
        }
    }
}
