package com.ultrax26.recorder.triggers

import java.util.UUID

/** Starter rule set. Users can edit/delete/add any rule in Settings → Triggers. */
object DefaultRules {
    private fun id() = UUID.randomUUID().toString()

    fun build(): List<TriggerRule> = listOf(
        // Hands
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.THUMB_UP, holdMs = 600), RecAction.START,
            states = listOf(RecState.IDLE)),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.THUMB_DOWN, holdMs = 600), RecAction.STOP,
            states = listOf(RecState.RECORDING, RecState.PAUSED, RecState.COUNTDOWN)),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.OPEN_PALM, holdMs = 800), RecAction.TOGGLE_PAUSE,
            states = listOf(RecState.RECORDING, RecState.PAUSED)),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.CLOSED_FIST, holdMs = 800), RecAction.STOP,
            states = listOf(RecState.RECORDING, RecState.PAUSED)),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.VICTORY, holdMs = 600), RecAction.SNAPSHOT),
        TriggerRule(id(), Trigger.Pinch(PinchDirection.OUT), RecAction.ZOOM_IN, onlyWhenArmed = false),
        TriggerRule(id(), Trigger.Pinch(PinchDirection.IN), RecAction.ZOOM_OUT, onlyWhenArmed = false),
        TriggerRule(id(), Trigger.VisualClap(), RecAction.TOGGLE_RECORD, enabled = false),
        TriggerRule(id(), Trigger.HandWave(), RecAction.TOGGLE_RECORD, enabled = false),
        // Face
        TriggerRule(id(), Trigger.Blink(count = 3), RecAction.TOGGLE_RECORD),
        TriggerRule(id(), Trigger.HeadNod(count = 2), RecAction.START, states = listOf(RecState.IDLE), enabled = false),
        TriggerRule(id(), Trigger.HeadShake(count = 2), RecAction.STOP, states = listOf(RecState.RECORDING, RecState.PAUSED), enabled = false),
        TriggerRule(id(), Trigger.Wink(), RecAction.SNAPSHOT, enabled = false),
        // Audio
        TriggerRule(id(), Trigger.Clap(count = 2), RecAction.TOGGLE_RECORD),
        TriggerRule(id(), Trigger.Clap(count = 3), RecAction.SNAPSHOT, enabled = false),
        TriggerRule(id(), Trigger.Whistle(), RecAction.TOGGLE_PAUSE, enabled = false),
        TriggerRule(id(), Trigger.Snap(count = 2), RecAction.TOGGLE_RECORD, enabled = false),
        // Voice
        TriggerRule(id(), Trigger.VoiceCommand("start recording", aliases = listOf("start", "record", "action", "rolling")), RecAction.START),
        TriggerRule(id(), Trigger.VoiceCommand("stop recording", aliases = listOf("stop", "cut", "end recording")), RecAction.STOP),
        TriggerRule(id(), Trigger.VoiceCommand("pause recording", aliases = listOf("pause", "hold")), RecAction.PAUSE),
        TriggerRule(id(), Trigger.VoiceCommand("resume recording", aliases = listOf("resume", "continue")), RecAction.RESUME),
        TriggerRule(id(), Trigger.VoiceCommand("snapshot", aliases = listOf("take a photo", "capture", "cheese")), RecAction.SNAPSHOT),
        TriggerRule(id(), Trigger.VoiceCommand("marker", aliases = listOf("mark", "chapter")), RecAction.MARK),
        TriggerRule(id(), Trigger.VoiceCommand("arm", aliases = listOf("arm triggers", "standby")), RecAction.ARM, onlyWhenArmed = false),
        TriggerRule(id(), Trigger.VoiceCommand("disarm", aliases = listOf("disarm triggers", "stand down")), RecAction.DISARM, onlyWhenArmed = false),
        // Video calls
        TriggerRule(id(), Trigger.VoiceCommand("answer", aliases = listOf("answer call", "pick up")), RecAction.ANSWER_CALL, onlyWhenArmed = false),
        TriggerRule(id(), Trigger.VoiceCommand("hang up", aliases = listOf("end call", "goodbye")), RecAction.HANG_UP, onlyWhenArmed = false),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.THUMB_UP, holdMs = 800), RecAction.ANSWER_CALL, onlyWhenArmed = false, enabled = false),
        TriggerRule(id(), Trigger.HandGesture(HandGestureType.OPEN_PALM, holdMs = 1200), RecAction.HANG_UP, onlyWhenArmed = false, enabled = false),
        // Device
        TriggerRule(id(), Trigger.VolumeKeyPress(VolumeKey.ANY), RecAction.TOGGLE_RECORD, onlyWhenArmed = false, cooldownMs = 800),
        TriggerRule(id(), Trigger.BluetoothButton(), RecAction.TOGGLE_RECORD, onlyWhenArmed = false, cooldownMs = 800),
        TriggerRule(id(), Trigger.Shake(), RecAction.TOGGLE_RECORD, enabled = false),
        TriggerRule(id(), Trigger.ProximityWave(), RecAction.TOGGLE_RECORD, enabled = false),
    )
}
