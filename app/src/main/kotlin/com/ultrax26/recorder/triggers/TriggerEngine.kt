package com.ultrax26.recorder.triggers

import com.ultrax26.recorder.settings.TriggerSettings
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the engine tells the rest of the app (feedback + UI). */
sealed class EngineEvent {
    data class Fired(val rule: TriggerRule, val event: TriggerEvent, val source: String) : EngineEvent()
    data class Ignored(val rule: TriggerRule, val reason: String) : EngineEvent()
    data class CountdownTick(val secondsLeft: Int, val action: RecAction) : EngineEvent()
    data class CountdownCancelled(val reason: String) : EngineEvent()
    data class Armed(val armed: Boolean) : EngineEvent()
}

data class TriggerLogEntry(val timeMs: Long, val text: String, val fired: Boolean)

/**
 * Matches live [TriggerEvent]s against the user's rules and drives the recorder. Single-threaded via
 * its own executor; safe to call from any thread.
 */
class TriggerEngine(
    private val nowMs: () -> Long,
    private val recorderState: () -> RecState,
    private val perform: (RecAction, TriggerRule?) -> Unit,
    private val notify: (EngineEvent) -> Unit,
) {
    private val tag = "Triggers"
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ux-trigger-engine") }

    val armed = MutableStateFlow(false)
    val countdown = MutableStateFlow<Int?>(null)
    val log = MutableStateFlow<List<TriggerLogEntry>>(emptyList())
    val lastEvent = MutableStateFlow<String>("")

    @Volatile private var settings: TriggerSettings = TriggerSettings()
    private val ruleState = HashMap<String, RuleState>()
    private var lastGlobalFireMs = -100000L
    private val gestureHistory = ArrayList<Pair<HandGestureType, Long>>()
    private var countdownFuture: ScheduledFuture<*>? = null
    private var countdownAction: RecAction? = null
    private var timerFutures = ArrayList<ScheduledFuture<*>>()

    private class RuleState { var lastFiredMs = -100000L; var holdFired = false }

    fun updateSettings(s: TriggerSettings) = exec.execute {
        settings = s
        ruleState.keys.retainAll(s.rules.map { it.id }.toSet())
        rescheduleTimers()
    }

    fun setArmed(on: Boolean) = exec.execute { setArmedInternal(on) }

    private fun setArmedInternal(on: Boolean) {
        if (armed.value == on) return
        armed.value = on
        notify(EngineEvent.Armed(on))
        addLog(if (on) "Triggers armed" else "Triggers disarmed", false)
        rescheduleTimers()
        if (!on) cancelCountdown("disarmed")
    }

    private fun rescheduleTimers() {
        timerFutures.forEach { it.cancel(false) }
        timerFutures.clear()
        if (!armed.value) return
        settings.rules.filter { it.enabled && it.trigger is Trigger.Timer }.forEach { r ->
            val secs = (r.trigger as Trigger.Timer).seconds.coerceAtLeast(1)
            timerFutures += exec.schedule({ handle(TriggerEvent.TimerElapsed(secs, nowMs())) }, secs.toLong(), TimeUnit.SECONDS)
        }
    }

    fun onEvent(e: TriggerEvent) = exec.execute { handle(e) }

    fun cancelCountdownRequest() = exec.execute { cancelCountdown("user") }

    fun shutdown() { exec.shutdownNow() }

    private fun addLog(text: String, fired: Boolean) {
        val entry = TriggerLogEntry(nowMs(), text, fired)
        log.value = (listOf(entry) + log.value).take(60)
        lastEvent.value = text
    }

    private fun describe(e: TriggerEvent): String? = when (e) {
        is TriggerEvent.HandHeld -> if (e.heldMs < 100) "Hand: ${e.gesture.label}" else null
        is TriggerEvent.Wave -> "Wave ×${e.swings}"
        is TriggerEvent.VisualClap -> "Hands clapped ×${e.count}"
        is TriggerEvent.BlinkBurst -> "Blink ×${e.count}"
        is TriggerEvent.Wink -> "Wink ${e.eye.name.lowercase()}"
        is TriggerEvent.HeadNod -> "Nod ×${e.count}"
        is TriggerEvent.HeadShake -> "Shake ×${e.count}"
        is TriggerEvent.ClapBurst -> "Clap ×${e.count}"
        is TriggerEvent.SnapBurst -> "Snap ×${e.count}"
        is TriggerEvent.Pinch -> if (e.direction == PinchDirection.OUT) "Unpinch (spread)" else "Pinch (close)"
        is TriggerEvent.Whistle -> "Whistle ${e.frequencyHz.toInt()} Hz"
        is TriggerEvent.Loud -> "Loud ${e.peakDbfs.toInt()} dB"
        is TriggerEvent.Voice -> "Heard “${e.text}” (${e.engine.name.lowercase()})"
        is TriggerEvent.VolumeKey -> "Volume ${e.key.name.lowercase()}"
        is TriggerEvent.BluetoothKey -> "BT key ${e.keyCode}"
        is TriggerEvent.Shake -> "Shake ${"%.1f".format(e.peakG)}g"
        is TriggerEvent.ProximityWaves -> "Proximity ×${e.count}"
        is TriggerEvent.TimerElapsed -> "Timer ${e.seconds}s"
        is TriggerEvent.FaceAppeared -> "Subject entered"
        else -> null
    }

    private fun handle(e: TriggerEvent) {
        describe(e)?.let { addLog(it, false) }
        // Gesture history for sequences.
        if (e is TriggerEvent.HandReleased && e.heldMs >= 300) {
            gestureHistory += e.gesture to e.timestampMs
            if (gestureHistory.size > 8) gestureHistory.removeAt(0)
        }
        val state = recorderState()
        for (rule in settings.rules) {
            if (!rule.enabled) continue
            val rs = ruleState.getOrPut(rule.id) { RuleState() }
            val m = matches(rule, e, rs)
            if (m == Match.RESET) { rs.holdFired = false; continue }
            if (m != Match.FIRE) continue
            if (rule.onlyWhenArmed && !armed.value) { notify(EngineEvent.Ignored(rule, "not armed")); continue }
            if (rule.states != null && state !in rule.states) { notify(EngineEvent.Ignored(rule, "state $state")); continue }
            val now = nowMs()
            if (now - rs.lastFiredMs < rule.cooldownMs) { notify(EngineEvent.Ignored(rule, "cooldown")); continue }
            if (now - lastGlobalFireMs < settings.globalCooldownMs && rule.action != RecAction.CANCEL_COUNTDOWN) { notify(EngineEvent.Ignored(rule, "global cooldown")); continue }
            rs.lastFiredMs = now
            lastGlobalFireMs = now
            if (rule.trigger.holdStyle()) rs.holdFired = true
            fire(rule, e, state)
        }
    }

    private enum class Match { NONE, FIRE, RESET }

    private fun Trigger.holdStyle() = this is Trigger.HandGesture || this is Trigger.HandsUp || this is Trigger.FingerCount || this is Trigger.Smile || this is Trigger.MouthOpen || this is Trigger.HeadTilt || this is Trigger.FaceDisappears

    private fun matches(rule: TriggerRule, e: TriggerEvent, rs: RuleState): Match {
        val t = rule.trigger
        fun hold(ok: Boolean, heldMs: Long, needMs: Long): Match = if (!ok) Match.NONE else if (heldMs >= needMs && !rs.holdFired) Match.FIRE else Match.NONE
        return when (t) {
            is Trigger.HandGesture -> when (e) {
                is TriggerEvent.HandHeld -> hold(e.gesture == t.gesture && e.score >= t.minScore && (t.hand == null || t.hand == e.handedness), e.heldMs, t.holdMs)
                is TriggerEvent.HandReleased -> if (e.gesture == t.gesture) Match.RESET else Match.NONE
                else -> Match.NONE
            }
            is Trigger.HandWave -> if (e is TriggerEvent.Wave && e.swings >= t.minSwings) Match.FIRE else Match.NONE
            is Trigger.VisualClap -> if (e is TriggerEvent.VisualClap && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.HandsUp -> when (e) { is TriggerEvent.HandsUpHeld -> hold(true, e.heldMs, t.holdMs); is TriggerEvent.HandsUpReleased -> Match.RESET; else -> Match.NONE }
            is Trigger.FingerCount -> when (e) { is TriggerEvent.FingersHeld -> hold(e.fingers == t.fingers, e.heldMs, t.holdMs); is TriggerEvent.FingersReleased -> Match.RESET; else -> Match.NONE }
            is Trigger.GestureSequence -> if (e is TriggerEvent.HandReleased && sequenceMatches(t)) { gestureHistory.clear(); Match.FIRE } else Match.NONE
            is Trigger.Pinch -> if (e is TriggerEvent.Pinch && (t.direction == PinchDirection.ANY || t.direction == e.direction)) Match.FIRE else Match.NONE
            is Trigger.Blink -> if (e is TriggerEvent.BlinkBurst && e.count == t.count && e.durationMs <= t.windowMs) Match.FIRE else Match.NONE
            is Trigger.Wink -> if (e is TriggerEvent.Wink && (t.eye == Eye.ANY || t.eye == e.eye)) Match.FIRE else Match.NONE
            is Trigger.Smile -> when (e) { is TriggerEvent.SmileHeld -> hold(true, e.heldMs, t.holdMs); is TriggerEvent.SmileReleased -> Match.RESET; else -> Match.NONE }
            is Trigger.MouthOpen -> when (e) { is TriggerEvent.MouthOpenHeld -> hold(true, e.heldMs, t.holdMs); is TriggerEvent.MouthOpenReleased -> Match.RESET; else -> Match.NONE }
            is Trigger.HeadNod -> if (e is TriggerEvent.HeadNod && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.HeadShake -> if (e is TriggerEvent.HeadShake && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.HeadTilt -> when (e) {
                is TriggerEvent.HeadTiltHeld -> hold(t.direction == TiltDirection.ANY || t.direction == e.direction, e.heldMs, t.holdMs)
                is TriggerEvent.HeadTiltReleased -> Match.RESET; else -> Match.NONE
            }
            is Trigger.FaceAppears -> if (e is TriggerEvent.FaceAppeared) Match.FIRE else Match.NONE
            is Trigger.FaceDisappears -> when (e) { is TriggerEvent.FaceGone -> hold(true, e.goneMs, t.timeoutMs); is TriggerEvent.FaceAppeared -> Match.RESET; else -> Match.NONE }
            is Trigger.Clap -> if (e is TriggerEvent.ClapBurst && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.Snap -> if (e is TriggerEvent.SnapBurst && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.Whistle -> if (e is TriggerEvent.Whistle && e.durationMs >= t.minDurationMs) Match.FIRE else Match.NONE
            is Trigger.LoudSound -> if (e is TriggerEvent.Loud && e.peakDbfs >= t.thresholdDbfs) Match.FIRE else Match.NONE
            is Trigger.VoiceCommand -> if (e is TriggerEvent.Voice && voiceMatches(t, e)) Match.FIRE else Match.NONE
            is Trigger.VolumeKeyPress -> if (e is TriggerEvent.VolumeKey && (t.key == VolumeKey.ANY || t.key == e.key) && t.longPress == e.longPress) Match.FIRE else Match.NONE
            is Trigger.BluetoothButton -> if (e is TriggerEvent.BluetoothKey && (t.keyCode == null || t.keyCode == e.keyCode)) Match.FIRE else Match.NONE
            is Trigger.Shake -> if (e is TriggerEvent.Shake && e.peakG >= t.thresholdG) Match.FIRE else Match.NONE
            is Trigger.ProximityWave -> if (e is TriggerEvent.ProximityWaves && e.count == t.count) Match.FIRE else Match.NONE
            is Trigger.Timer -> if (e is TriggerEvent.TimerElapsed && e.seconds == t.seconds) Match.FIRE else Match.NONE
        }
    }

    private fun sequenceMatches(t: Trigger.GestureSequence): Boolean {
        val steps = t.steps
        if (steps.isEmpty() || gestureHistory.size < steps.size) return false
        val tail = gestureHistory.takeLast(steps.size)
        for (i in steps.indices) if (tail[i].first != steps[i]) return false
        for (i in 1 until tail.size) if (tail[i].second - tail[i - 1].second > t.stepTimeoutMs) return false
        return true
    }

    internal fun voiceMatches(t: Trigger.VoiceCommand, e: TriggerEvent.Voice): Boolean {
        if (t.engine != VoiceEngine.BOTH && t.engine != e.engine) return false
        val heard = normalize(e.text)
        val phrases = t.allPhrases().map { normalize(it) }
        return if (e.engine == VoiceEngine.KEYWORD) phrases.any { it == heard }
        else phrases.any { p -> p.isNotEmpty() && (heard == p || heard.contains(" $p ") || heard.startsWith("$p ") || heard.endsWith(" $p")) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun fire(rule: TriggerRule, e: TriggerEvent, state: RecState) {
        val cat = rule.trigger.category
        val wantsCountdown = settings.countdownSeconds > 0 &&
            (rule.action == RecAction.START || (rule.action == RecAction.TOGGLE_RECORD && state == RecState.IDLE)) &&
            cat != "Device" && (cat != "Voice" || settings.countdownAppliesToVoice)
        addLog("${rule.trigger.label} → ${rule.action.label}", true)
        notify(EngineEvent.Fired(rule, e, cat))
        if (rule.action == RecAction.CANCEL_COUNTDOWN) { cancelCountdown("gesture"); return }
        if (state == RecState.COUNTDOWN && (rule.action == RecAction.STOP || rule.action == RecAction.TOGGLE_RECORD)) { cancelCountdown("stop gesture"); perform(RecAction.CANCEL_COUNTDOWN, rule); return }
        if (wantsCountdown) startCountdown(RecAction.START, rule) else perform(rule.action, rule)
    }

    private fun startCountdown(action: RecAction, rule: TriggerRule) {
        cancelCountdown("restart")
        countdownAction = action
        var left = settings.countdownSeconds
        countdown.value = left
        notify(EngineEvent.CountdownTick(left, action))
        countdownFuture = exec.scheduleAtFixedRate({
            left--
            if (left <= 0) {
                countdownFuture?.cancel(false); countdownFuture = null
                countdown.value = null
                perform(action, rule)
            } else {
                countdown.value = left
                notify(EngineEvent.CountdownTick(left, action))
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun cancelCountdown(reason: String) {
        val f = countdownFuture ?: return
        f.cancel(false); countdownFuture = null
        countdown.value = null
        notify(EngineEvent.CountdownCancelled(reason))
        UxLog.i(tag, "countdown cancelled: $reason")
    }
}
