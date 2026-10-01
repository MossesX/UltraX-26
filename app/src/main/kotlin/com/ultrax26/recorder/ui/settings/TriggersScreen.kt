package com.ultrax26.recorder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.settings.*
import com.ultrax26.recorder.triggers.*
import com.ultrax26.recorder.ui.components.*
import com.ultrax26.recorder.ui.theme.UxColors
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun TriggersScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val armed by graph.triggers.armed.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Rules", "Commands", "Voice", "Tuning", "Monitor")
    val cameraIdForCatalog = settings.capture.cameraId ?: graph.catalog.defaultBackId()
    val commands by produceState(initialValue = CommandCatalog.build(CommandCatalogInput()), cameraIdForCatalog, settings.capture.probeHiddenCameraIds) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { CommandCatalog.build(buildCommandInput(graph, settings, cameraIdForCatalog)) }.getOrDefault(value) }
    }
    CompositionLocalProvider(LocalCommandCatalog provides commands) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeader("Gestures & voice", onBack) {
            FilterChip(selected = armed, onClick = { graph.triggers.setArmed(!armed) }, label = { Text(if (armed) "ARMED" else "Disarmed") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = UxColors.Green, selectedLabelColor = androidx.compose.ui.graphics.Color.Black))
            Spacer(Modifier.width(8.dp))
        }
        TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) } }
        when (tab) {
            0 -> RulesTab(graph, settings.triggers)
            1 -> CommandsTab(graph, settings)
            2 -> Column(Modifier.verticalScroll(rememberScrollState())) { VoiceTab(graph, settings.triggers) }
            3 -> Column(Modifier.verticalScroll(rememberScrollState())) { TuningTab(graph, settings) }
            else -> MonitorTab(graph)
        }
    }
    }
}

/** Every bindable command for this phone (built once per screen; see CommandCatalog). */
private val LocalCommandCatalog = compositionLocalOf<List<CommandSpec>> { emptyList() }

private fun TriggerRule.commandTitle(commands: List<CommandSpec>): String =
    commands.firstOrNull { it.action == action && (it.param == null || it.param.equals(actionParam?.trim(), ignoreCase = true)) }?.title ?: actionLabel

/** Searchable chooser over the whole command catalog (hundreds of entries), used by the rule editor. */
@Composable
private fun CommandPickerDialog(commands: List<CommandSpec>, onPick: (CommandSpec) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, commands) { commands.filter { it.matches(query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose what the trigger does") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search ${commands.size} commands…") },
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
                Text("${filtered.size} shown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    val grouped = filtered.groupBy { it.group }
                    for ((group, cmds) in grouped) {
                        item(key = "g-$group") { Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                        items(cmds, key = { it.id }) { c ->
                            Text(c.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 7.dp))
                        }
                    }
                    if (filtered.isEmpty()) item { Text("Nothing matches “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                }
            }
        },
    )
}

private fun AppGraph.updTriggers(f: (TriggerSettings) -> TriggerSettings) = settings.update { it.copy(triggers = f(it.triggers)) }

// ------------------------------------------------------------------------------------------------
// Rules
// ------------------------------------------------------------------------------------------------

@Composable
private fun RulesTab(graph: AppGraph, t: TriggerSettings) {
    var editing by remember { mutableStateOf<TriggerRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addingVoice by remember { mutableStateOf(false) }
    val hubForEnroll by graph.audioHubState.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalEnroll provides EnrollHandle(hubForEnroll)) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${t.rules.count { it.enabled }} of ${t.rules.size} rules active", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { adding = true }) { Text("Add rule") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { graph.updTriggers { it.copy(rules = DefaultRules.build()) } }) { Text("Defaults") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { addingVoice = true }) { Text("Record a voice trigger") }
                Spacer(Modifier.width(8.dp))
                Text("Say your own word or sound 3–5 times; it becomes a command you can bind to any action.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        val grouped = t.rules.groupBy { it.trigger.category }
        for ((cat, rules) in grouped) {
            item { Text(cat, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            items(rules, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = r.enabled, onCheckedChange = { on -> graph.updTriggers { s -> s.copy(rules = s.rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) } })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.trigger.label, style = MaterialTheme.typography.bodyLarge)
                            Text("→ ${r.action.label} · cooldown ${r.cooldownMs / 1000.0}s${if (!r.onlyWhenArmed) " · works while disarmed" else ""}${r.states?.let { " · only " + it.joinToString("/") { s -> s.name.lowercase() } } ?: ""}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { editing = r }) { Text("Edit") }
                    }
                }
            }
        }
    }
    editing?.let { r ->
        RuleDialog(r, title = "Edit rule", onDismiss = { editing = null },
            onSave = { nr -> graph.updTriggers { s -> s.copy(rules = s.rules.map { if (it.id == r.id) nr else it }) }; editing = null },
            onDelete = { graph.updTriggers { s -> s.copy(rules = s.rules.filter { it.id != r.id }) }; editing = null })
    }
    if (adding) {
        RuleDialog(TriggerRule(UUID.randomUUID().toString(), Trigger.HandGesture(), RecAction.START), title = "New rule", onDismiss = { adding = false },
            onSave = { nr -> graph.updTriggers { s -> s.copy(rules = s.rules + nr) }; adding = false }, onDelete = null)
    }
    if (addingVoice) {
        RuleDialog(TriggerRule(UUID.randomUUID().toString(), Trigger.VoiceCommand(phrase = "", engine = VoiceEngine.KEYWORD), RecAction.TOGGLE_RECORD), title = "New voice trigger", onDismiss = { addingVoice = false },
            onSave = { nr -> graph.updTriggers { s -> s.copy(rules = s.rules + nr) }; addingVoice = false }, onDelete = null)
    }
    }
}

/** Lets the rule editor start keyword enrollment without threading the audio hub through every composable. */
private class EnrollHandle(val hub: com.ultrax26.recorder.triggers.audio.AudioTriggerHub?)
private val LocalEnroll = compositionLocalOf { EnrollHandle(null) }

@Composable
private fun VoiceEnrollRow(phrase: String) {
    val handle = LocalEnroll.current
    val hub = handle.hub
    val hud = hub?.hud?.collectAsStateWithLifecycle()?.value
    val cmd = phrase.trim().lowercase()
    val count = hud?.enrolledCounts?.get(cmd) ?: 0
    val listening = hud?.enrolling == cmd
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(when {
            hub == null -> "Microphone not running — the trained engine needs the app in the foreground with microphone access."
            cmd.isEmpty() -> "Type the phrase above, then record it."
            listening -> "RECORDING — say “$cmd” once, then tap Stop & save. Repeat for 3–5 samples."
            else -> "$count recorded sample${if (count == 1) "" else "s"} of “$cmd”. Any word or sound works; the recorder learns your voice."
        }, style = MaterialTheme.typography.bodySmall, color = if (listening) UxColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
        if (listening && hud != null) RecordingMeter(hud)
        hud?.enrollError?.let { if (!listening) Text(it, style = MaterialTheme.typography.bodySmall, color = UxColors.Amber) }
        hud?.enrollSavedCommand?.let { if (!listening && it == cmd) Text("Sample saved ✓", style = MaterialTheme.typography.bodySmall, color = UxColors.Green) }
        Row {
            if (!listening) Button(onClick = { hub?.beginEnrollment(cmd) }, enabled = hub != null && cmd.isNotEmpty() && hud?.enrolling == null) { Text(if (count == 0) "Record this voice trigger" else "Record another sample") }
            else {
                Button(onClick = { hub?.finishEnrollment() }, colors = ButtonDefaults.buttonColors(containerColor = UxColors.Green, contentColor = androidx.compose.ui.graphics.Color.Black)) { Text("Stop & save") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { hub?.cancelEnrollment() }) { Text("Cancel") }
            }
            if (count > 0 && !listening) TextButton(onClick = { hub?.deleteCommand(cmd) }) { Text("Clear samples") }
        }
    }
}

/** Live input level + elapsed time while a voice sample is being recorded. */
@Composable
private fun RecordingMeter(hud: com.ultrax26.recorder.triggers.audio.AudioHudState) {
    val level = ((hud.enrollLevelDbfs + 60f) / 60f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(8.dp), color = if (hud.enrollLevelDbfs > -35f) UxColors.Green else UxColors.Amber)
        Text("${"%.1f".format(hud.enrollMs / 1000f)} s · level ${hud.enrollLevelDbfs.toInt()} dBFS · peak ${hud.enrollMaxDbfs.toInt()} dBFS" + (if (hud.enrollMaxDbfs < -60f && hud.enrollMs > 1500) "  — nothing is being heard" else ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------------------------------------
// Commands: everything the app can do, bindable to a voice phrase, gesture or sound; live-filtered
// ------------------------------------------------------------------------------------------------

@Composable
private fun CommandsTab(graph: AppGraph, s: AppSettings) {
    val catalog = LocalCommandCatalog.current
    var query by remember { mutableStateOf("") }
    var addingRule by remember { mutableStateOf(false) }
    val filtered = remember(query, catalog) { catalog.filter { it.matches(query) } }
    val rules = s.triggers.rules
    fun bindingsFor(c: CommandSpec): List<TriggerRule> = rules.filter { it.action == c.action && (c.param == null || it.actionParam?.trim().equals(c.param, ignoreCase = true)) }
    var voiceFor by remember { mutableStateOf<CommandSpec?>(null) }
    var gestureFor by remember { mutableStateOf<CommandSpec?>(null) }
    val hub by graph.audioHubState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), singleLine = true,
            placeholder = { Text("Search ${catalog.size} commands… (resolution, zoom, camera, look, mute)") },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} commands · Voice records a phrase for one, Gesture binds a hand/face signal, sound or button", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { addingRule = true }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ Add rule") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            val grouped = filtered.groupBy { it.group }
            for ((group, cmds) in grouped) {
                item(key = "h-$group") { Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(cmds, key = { it.id }) { c ->
                    val bound = bindingsFor(c)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.bodyMedium)
                            if (bound.isNotEmpty()) Text(bound.joinToString(" · ") { it.trigger.label }, style = MaterialTheme.typography.labelSmall, color = UxColors.Green)
                        }
                        TextButton(onClick = { voiceFor = c }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (bound.any { it.trigger is Trigger.VoiceCommand }) "Voice ✓" else "Voice") }
                        TextButton(onClick = { gestureFor = c }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (bound.any { it.trigger !is Trigger.VoiceCommand }) "Gesture ✓" else "Gesture") }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("Nothing matches “$query”.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (addingRule) RuleDialog(TriggerRule(UUID.randomUUID().toString(), Trigger.HandGesture(), RecAction.START, onlyWhenArmed = false), title = "New rule", onDismiss = { addingRule = false },
        onSave = { nr -> graph.updTriggers { t -> t.copy(rules = t.rules + nr) }; addingRule = false }, onDelete = null)
    voiceFor?.let { c -> VoiceBindSheet(graph, c, hub, bindingsFor(c).firstOrNull { it.trigger is Trigger.VoiceCommand }) { voiceFor = null } }
    gestureFor?.let { c ->
        val existing = bindingsFor(c).firstOrNull { it.trigger !is Trigger.VoiceCommand }
        RuleDialog(existing ?: TriggerRule(UUID.randomUUID().toString(), Trigger.HandGesture(), c.action, actionParam = c.param, onlyWhenArmed = false), title = c.title, onDismiss = { gestureFor = null },
            onSave = { nr -> graph.updTriggers { t -> t.copy(rules = if (existing != null) t.rules.map { if (it.id == nr.id) nr else it } else t.rules + nr) }; gestureFor = null },
            onDelete = if (existing != null) ({ graph.updTriggers { t -> t.copy(rules = t.rules.filter { it.id != existing.id }) }; gestureFor = null }) else null)
    }
}

private fun buildCommandInput(graph: AppGraph, s: AppSettings, cameraId: String?): CommandCatalogInput {
    val chars = cameraId?.let { runCatching { graph.catalog.characteristics(it) }.getOrNull() }
    val infos = graph.catalog.allInfos(s.capture.probeHiddenCameraIds, s.capture.hiddenIdProbeMax)
    val sizes = chars?.let { com.ultrax26.recorder.camera.Capabilities.videoSizes(it) } ?: emptyList()
    val fps = chars?.let { c -> sizes.firstOrNull()?.let { com.ultrax26.recorder.camera.Capabilities.selectableFps(c, it) } } ?: listOf(24, 30, 60)
    val presets = graph.controller.sessionInfo.value?.lensPresets ?: emptyList()
    val maxZoom = infos.firstOrNull { it.id == cameraId }?.zoomRange?.upper ?: 10f
    return CommandCatalogInput(
        cameras = infos.map { it.id to it.shortName },
        resolutions = sizes.map { it.width to it.height }.distinct(),
        fpsOptions = (fps + listOf(24, 25, 30, 50, 60)).distinct().sorted(),
        lensPresets = presets,
        maxZoom = maxZoom,
        codecs = com.ultrax26.recorder.recording.EncoderCapabilities.availableCodecs().map { it.name },
        hdrModes = HdrMode.entries.map { it.name },
        looks = com.ultrax26.recorder.effects.EffectCatalog.looks.map { it.id to it.name },
        stickers = com.ultrax26.recorder.effects.EffectCatalog.stickers.map { it.id to it.name },
        backgrounds = com.ultrax26.recorder.effects.EffectCatalog.parallaxScenes.map { it.id to it.name } + com.ultrax26.recorder.effects.EffectCatalog.proceduralBackgrounds.map { it.id to it.name },
        faceModes = com.ultrax26.recorder.effects.FaceMode.entries.filter { it != com.ultrax26.recorder.effects.FaceMode.NONE }.map { it.name to it.label },
        funModes = com.ultrax26.recorder.effects.FunMode.entries.filter { it != com.ultrax26.recorder.effects.FunMode.NONE }.map { it.name to it.label },
        ageModes = com.ultrax26.recorder.effects.AgeMode.entries.filter { it != com.ultrax26.recorder.effects.AgeMode.NONE }.map { it.name to it.label },
        colorLooks = com.ultrax26.recorder.effects.ColorLook.entries.filter { it != com.ultrax26.recorder.effects.ColorLook.NONE }.map { it.name to it.label },
        tonePresets = TonemapPreset.entries.map { it.name to it.label },
    )
}

/** Record a spoken phrase for one command (tap to record, tap to stop & save), then bind it as a rule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceBindSheet(graph: AppGraph, c: CommandSpec, hub: com.ultrax26.recorder.triggers.audio.AudioTriggerHub?, existing: TriggerRule?, onDismiss: () -> Unit) {
    val hud = hub?.hud?.collectAsStateWithLifecycle()?.value
    var phrase by remember { mutableStateOf((existing?.trigger as? Trigger.VoiceCommand)?.phrase ?: c.phrase) }
    var alsoSystem by remember { mutableStateOf(((existing?.trigger as? Trigger.VoiceCommand)?.engine ?: VoiceEngine.BOTH) != VoiceEngine.KEYWORD) }
    val cmd = phrase.trim().lowercase()
    val count = hud?.enrolledCounts?.get(cmd) ?: 0
    val recording = hud?.enrolling == cmd
    ModalBottomSheet(onDismissRequest = { if (recording) hub?.cancelEnrollment(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(c.title, style = MaterialTheme.typography.headlineSmall)
            Text("${c.group} · ${c.action.label}${c.param?.let { " $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text("Spoken phrase") }, singleLine = true, enabled = !recording,
                supportingText = { Text("Any word or sound. The trained recognizer matches your voice; samples are stored under this phrase.") })
            Spacer(Modifier.height(10.dp))
            if (hub == null) Text("Microphone not running — keep the app in the foreground with microphone access.", color = UxColors.Amber, style = MaterialTheme.typography.bodySmall)
            Text(if (recording) "RECORDING — say “$cmd” once, then tap Stop & save" else "$count sample${if (count == 1) "" else "s"} recorded. Record 3–5 for reliable matching.",
                style = MaterialTheme.typography.bodyMedium, color = if (recording) UxColors.Green else MaterialTheme.colorScheme.onSurface)
            if (recording && hud != null) RecordingMeter(hud)
            hud?.enrollError?.let { if (!recording) Text(it, style = MaterialTheme.typography.bodySmall, color = UxColors.Amber) }
            hud?.enrollSavedCommand?.let { if (!recording && it == cmd) Text("Sample saved ✓", style = MaterialTheme.typography.bodySmall, color = UxColors.Green) }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!recording) Button(onClick = { hub?.beginEnrollment(cmd) }, enabled = hub != null && cmd.isNotEmpty() && hud?.enrolling == null, modifier = Modifier.weight(1f)) { Text(if (count == 0) "● Record sample" else "● Record another") }
                else Button(onClick = { hub?.finishEnrollment() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = UxColors.Green, contentColor = androidx.compose.ui.graphics.Color.Black)) { Text("■ Stop & save") }
                Spacer(Modifier.width(8.dp))
                if (recording) OutlinedButton(onClick = { hub?.cancelEnrollment() }) { Text("Cancel") }
                else if (count > 0) OutlinedButton(onClick = { hub?.deleteCommand(cmd) }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Also match with the system speech recognizer", alsoSystem, "Understands the words without training, but may pause while recording") { alsoSystem = it }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (cmd.isEmpty()) return@Button
                    val engine = if (alsoSystem) VoiceEngine.BOTH else VoiceEngine.KEYWORD
                    val trig = Trigger.VoiceCommand(cmd, engine = engine)
                    graph.updTriggers { t ->
                        val rule = existing?.copy(trigger = trig, action = c.action, actionParam = c.param) ?: TriggerRule(UUID.randomUUID().toString(), trig, c.action, actionParam = c.param, onlyWhenArmed = false, cooldownMs = 1000)
                        t.copy(rules = if (existing != null) t.rules.map { if (it.id == rule.id) rule else it } else t.rules + rule)
                    }
                    onDismiss()
                }, enabled = cmd.isNotEmpty() && !recording, modifier = Modifier.weight(1f)) { Text(if (existing != null) "Save binding" else "Bind phrase to command") }
                if (existing != null) { Spacer(Modifier.width(8.dp)); TextButton(onClick = { graph.updTriggers { t -> t.copy(rules = t.rules.filter { it.id != existing.id }) }; onDismiss() }) { Text("Remove", color = UxColors.Red) } }
            }
            Text("Binding creates a rule (Rules tab) that fires “${c.title}” when the phrase is heard, whether or not triggers are armed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val TRIGGER_KINDS: List<Pair<String, () -> Trigger>> = listOf(
    "Hand gesture" to { Trigger.HandGesture() }, "Pinch / unpinch" to { Trigger.Pinch() }, "Finger count" to { Trigger.FingerCount() }, "Wave" to { Trigger.HandWave() }, "Hands up" to { Trigger.HandsUp() },
    "Visual clap" to { Trigger.VisualClap() }, "Gesture sequence" to { Trigger.GestureSequence() },
    "Blink ×N" to { Trigger.Blink() }, "Wink" to { Trigger.Wink() }, "Smile" to { Trigger.Smile() }, "Mouth open" to { Trigger.MouthOpen() },
    "Head nod" to { Trigger.HeadNod() }, "Head shake" to { Trigger.HeadShake() }, "Head tilt" to { Trigger.HeadTilt() },
    "Subject enters frame" to { Trigger.FaceAppears() }, "Subject leaves frame" to { Trigger.FaceDisappears() },
    "Clap ×N" to { Trigger.Clap() }, "Finger snap ×N" to { Trigger.Snap() }, "Whistle" to { Trigger.Whistle() }, "Loud sound" to { Trigger.LoudSound() },
    "Voice command" to { Trigger.VoiceCommand() },
    "Volume key" to { Trigger.VolumeKeyPress() }, "Bluetooth button" to { Trigger.BluetoothButton() }, "Shake phone" to { Trigger.Shake() }, "Proximity wave" to { Trigger.ProximityWave() }, "Timer" to { Trigger.Timer() },
)

private fun kindOf(t: Trigger): String = when (t) {
    is Trigger.HandGesture -> "Hand gesture"; is Trigger.Pinch -> "Pinch / unpinch"; is Trigger.FingerCount -> "Finger count"; is Trigger.HandWave -> "Wave"; is Trigger.HandsUp -> "Hands up"
    is Trigger.VisualClap -> "Visual clap"; is Trigger.GestureSequence -> "Gesture sequence"; is Trigger.Blink -> "Blink ×N"; is Trigger.Wink -> "Wink"
    is Trigger.Smile -> "Smile"; is Trigger.MouthOpen -> "Mouth open"; is Trigger.HeadNod -> "Head nod"; is Trigger.HeadShake -> "Head shake"; is Trigger.HeadTilt -> "Head tilt"
    is Trigger.FaceAppears -> "Subject enters frame"; is Trigger.FaceDisappears -> "Subject leaves frame"; is Trigger.Clap -> "Clap ×N"; is Trigger.Snap -> "Finger snap ×N"
    is Trigger.Whistle -> "Whistle"; is Trigger.LoudSound -> "Loud sound"; is Trigger.VoiceCommand -> "Voice command"; is Trigger.VolumeKeyPress -> "Volume key"
    is Trigger.BluetoothButton -> "Bluetooth button"; is Trigger.Shake -> "Shake phone"; is Trigger.ProximityWave -> "Proximity wave"; is Trigger.Timer -> "Timer"
}

@Composable
private fun RuleDialog(initial: TriggerRule, title: String, onDismiss: () -> Unit, onSave: (TriggerRule) -> Unit, onDelete: (() -> Unit)?) {
    var rule by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onSave(rule) }) { Text("Save") } },
        dismissButton = { Row { if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = UxColors.Red) }; TextButton(onClick = onDismiss) { Text("Cancel") } } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 520.dp)) {
                PickerRow("Trigger", TRIGGER_KINDS.map { it.first to it.first }, kindOf(rule.trigger)) { k -> rule = rule.copy(trigger = TRIGGER_KINDS.first { it.first == k }.second()) }
                TriggerEditor(rule.trigger) { rule = rule.copy(trigger = it) }
                Sep()
                val commands = LocalCommandCatalog.current
                var choosing by remember { mutableStateOf(false) }
                var advanced by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Does", style = MaterialTheme.typography.bodyLarge)
                        Text(rule.commandTitle(commands), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick = { choosing = true }) { Text("Choose…") }
                }
                if (choosing) CommandPickerDialog(commands, onPick = { c -> rule = rule.copy(action = c.action, actionParam = c.param); choosing = false }, onDismiss = { choosing = false })
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide raw action" else "Raw action & parameter…") }
                if (advanced) {
                    PickerRow("Action", RecAction.entries.map { it to it.label }, rule.action) { rule = rule.copy(action = it) }
                    rule.action.paramHint?.let { hint -> TextFieldRow("Parameter", rule.actionParam ?: "", { rule = rule.copy(actionParam = it) }, hint) }
                }
                IntSliderRow("Cooldown", (rule.cooldownMs / 500).toInt(), 0, 20, suffix = " ×0.5 s") { rule = rule.copy(cooldownMs = it * 500L) }
                SwitchRow("Only while armed", rule.onlyWhenArmed) { rule = rule.copy(onlyWhenArmed = it) }
                val states = listOf(RecState.IDLE, RecState.COUNTDOWN, RecState.RECORDING, RecState.PAUSED)
                Text("Allowed recorder states (none selected = any)", style = MaterialTheme.typography.bodySmall)
                Row { states.forEach { st -> val sel = rule.states?.contains(st) == true
                    FilterChip(selected = sel, onClick = { val cur = rule.states ?: emptyList(); val next = if (sel) cur - st else cur + st; rule = rule.copy(states = next.ifEmpty { null }) }, label = { Text(st.name.lowercase()) }, modifier = Modifier.padding(end = 4.dp)) } }
                TextFieldRow("Name (optional)", rule.name, { rule = rule.copy(name = it) })
            }
        },
    )
}

@Composable
private fun TriggerEditor(t: Trigger, onChange: (Trigger) -> Unit) {
    when (t) {
        is Trigger.HandGesture -> {
            PickerRow("Gesture", HandGestureType.entries.map { it to it.label }, t.gesture) { onChange(t.copy(gesture = it)) }
            IntSliderRow("Hold", (t.holdMs / 100).toInt(), 1, 30, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) }
            SliderRow("Min classifier score", t.minScore, 0.3f..0.95f) { onChange(t.copy(minScore = it)) }
            PickerRow("Hand", listOf<Handedness?>(null, Handedness.LEFT, Handedness.RIGHT).map { it to (it?.name?.lowercase() ?: "either") }, t.hand) { onChange(t.copy(hand = it)) }
        }
        is Trigger.Pinch -> {
            PickerRow("Movement", PinchDirection.entries.map { it to it.label }, t.direction) { onChange(t.copy(direction = it)) }
            Text("Thumb and index finger: spread them apart to zoom in, bring them together to zoom out. Each movement fires once (zoom step in Camera settings). For a live, continuous zoom hold the pinch for a moment, then open or close — enable it under Tuning ▸ Hands.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is Trigger.FingerCount -> { IntSliderRow("Fingers", t.fingers, 1, 5) { onChange(t.copy(fingers = it)) }; IntSliderRow("Hold", (t.holdMs / 100).toInt(), 1, 30, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) } }
        is Trigger.HandWave -> IntSliderRow("Minimum swings", t.minSwings, 2, 8) { onChange(t.copy(minSwings = it)) }
        is Trigger.HandsUp -> IntSliderRow("Hold", (t.holdMs / 100).toInt(), 1, 30, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) }
        is Trigger.VisualClap -> IntSliderRow("Count", t.count, 1, 4) { onChange(t.copy(count = it)) }
        is Trigger.GestureSequence -> {
            Text("Steps: ${t.steps.joinToString(" → ") { it.label }}", style = MaterialTheme.typography.bodySmall)
            Row { PickerRow("Add step", HandGestureType.entries.map { it to it.label }, t.steps.lastOrNull() ?: HandGestureType.OPEN_PALM) { onChange(t.copy(steps = t.steps + it)) } }
            Row { TextButton(onClick = { if (t.steps.size > 1) onChange(t.copy(steps = t.steps.dropLast(1))) }) { Text("Remove last") } }
            IntSliderRow("Step timeout", (t.stepTimeoutMs / 500).toInt(), 1, 10, suffix = " ×0.5 s") { onChange(t.copy(stepTimeoutMs = it * 500L)) }
        }
        is Trigger.Blink -> { IntSliderRow("Blink count", t.count, 2, 6) { onChange(t.copy(count = it)) }; IntSliderRow("Within", (t.windowMs / 500).toInt(), 2, 10, suffix = " ×0.5 s") { onChange(t.copy(windowMs = it * 500L)) } }
        is Trigger.Wink -> PickerRow("Eye", Eye.entries.map { it to it.name.lowercase() }, t.eye) { onChange(t.copy(eye = it)) }
        is Trigger.Smile -> IntSliderRow("Hold", (t.holdMs / 100).toInt(), 2, 40, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) }
        is Trigger.MouthOpen -> IntSliderRow("Hold", (t.holdMs / 100).toInt(), 2, 40, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) }
        is Trigger.HeadNod -> IntSliderRow("Nods", t.count, 1, 5) { onChange(t.copy(count = it)) }
        is Trigger.HeadShake -> IntSliderRow("Shakes", t.count, 1, 5) { onChange(t.copy(count = it)) }
        is Trigger.HeadTilt -> { PickerRow("Direction", TiltDirection.entries.map { it to it.name.lowercase() }, t.direction) { onChange(t.copy(direction = it)) }; IntSliderRow("Hold", (t.holdMs / 100).toInt(), 2, 40, suffix = "00 ms") { onChange(t.copy(holdMs = it * 100L)) } }
        is Trigger.FaceAppears -> IntSliderRow("Stable for", (t.stableMs / 100).toInt(), 2, 30, suffix = "00 ms") { onChange(t.copy(stableMs = it * 100L)) }
        is Trigger.FaceDisappears -> IntSliderRow("Gone for", (t.timeoutMs / 1000).toInt(), 1, 60, suffix = " s") { onChange(t.copy(timeoutMs = it * 1000L)) }
        is Trigger.Clap -> IntSliderRow("Claps", t.count, 1, 5) { onChange(t.copy(count = it)) }
        is Trigger.Snap -> IntSliderRow("Snaps", t.count, 1, 5) { onChange(t.copy(count = it)) }
        is Trigger.Whistle -> IntSliderRow("Minimum duration", (t.minDurationMs / 100).toInt(), 2, 20, suffix = "00 ms") { onChange(t.copy(minDurationMs = it * 100L)) }
        is Trigger.LoudSound -> SliderRow("Threshold", t.thresholdDbfs, -40f..0f, format = { "${it.roundToInt()} dBFS" }) { onChange(t.copy(thresholdDbfs = it)) }
        is Trigger.VoiceCommand -> {
            TextFieldRow("Phrase", t.phrase, { onChange(t.copy(phrase = it)) }, "For the trained engine this must equal an enrolled command name")
            TextFieldRow("Aliases (comma separated)", t.aliases.joinToString(", "), { onChange(t.copy(aliases = it.split(',').map { a -> a.trim() }.filter { a -> a.isNotEmpty() })) })
            PickerRow("Engine", VoiceEngine.entries.map { it to it.label }, t.engine) { onChange(t.copy(engine = it)) }
            VoiceEnrollRow(t.phrase)
        }
        is Trigger.VolumeKeyPress -> { PickerRow("Key", VolumeKey.entries.map { it to it.name.lowercase() }, t.key) { onChange(t.copy(key = it)) }; SwitchRow("Long press", t.longPress) { onChange(t.copy(longPress = it)) } }
        is Trigger.BluetoothButton -> TextFieldRow("Key code (blank = any)", t.keyCode?.toString() ?: "", { onChange(t.copy(keyCode = it.trim().toIntOrNull())) }, "Press the button on the Monitor tab to see its code")
        is Trigger.Shake -> SliderRow("Threshold", t.thresholdG, 1.5f..4f, format = { "%.1f g".format(it) }) { onChange(t.copy(thresholdG = it)) }
        is Trigger.ProximityWave -> IntSliderRow("Waves", t.count, 1, 4) { onChange(t.copy(count = it)) }
        is Trigger.Timer -> IntSliderRow("Seconds after arming", t.seconds, 1, 120, suffix = " s") { onChange(t.copy(seconds = it)) }
    }
}

// ------------------------------------------------------------------------------------------------
// Voice
// ------------------------------------------------------------------------------------------------

@Composable
private fun VoiceTab(graph: AppGraph, t: TriggerSettings) {
    val v = t.voice
    fun upd(f: (VoiceConfig) -> VoiceConfig) = graph.updTriggers { it.copy(voice = f(it.voice)) }
    val hub by graph.audioHubState.collectAsStateWithLifecycle()
    val hud = hub?.hud?.collectAsStateWithLifecycle()?.value
    val speech by graph.speechState.collectAsStateWithLifecycle()
    val speechStatus = speech?.status?.collectAsStateWithLifecycle()?.value
    val heard = speech?.lastHeard?.collectAsStateWithLifecycle()?.value
    var newCommand by remember { mutableStateOf("") }

    SectionCard("Engines") {
        SwitchRow("Voice triggers", v.enabled) { upd { x -> x.copy(enabled = it) } }
        SwitchRow("System speech recognizer", v.systemRecognizer, "Free-form phrases; may go quiet while recording (mic arbitration). Status: ${speechStatus ?: "off"}${if (!heard.isNullOrBlank()) " · heard “$heard”" else ""}") { upd { x -> x.copy(systemRecognizer = it) } }
        SwitchRow("Prefer on-device recognition", v.preferOffline) { upd { x -> x.copy(preferOffline = it) } }
        TextFieldRow("Language tag (blank = device)", v.systemLanguageTag, { upd { x -> x.copy(systemLanguageTag = it.trim()) } }, "e.g. en-US, de-DE")
        SwitchRow("Built-in trained keyword spotter", v.keywordSpotter, "Runs on the recording's own microphone stream; always works while recording") { upd { x -> x.copy(keywordSpotter = it) } }
        SliderRow("Keyword sensitivity", v.keywordSensitivity, 0f..1f, subtitle = "Higher accepts looser matches (threshold ${"%.2f".format(com.ultrax26.recorder.triggers.audio.KeywordSpotter.thresholdFor(v.keywordSensitivity))})") { upd { x -> x.copy(keywordSensitivity = it) } }
        SliderRow("Rejection margin", v.keywordMargin, 0f..0.4f, subtitle = "Second-best command must be this much worse (relative)") { upd { x -> x.copy(keywordMargin = it) } }
        SwitchRow("Countdown also applies to voice", t.countdownAppliesToVoice) { on -> graph.updTriggers { it.copy(countdownAppliesToVoice = on) } }
    }
    SectionCard("Trained commands", "Tap Record, say the phrase once, tap Stop & save; repeat for 3–5 samples. The phrase must match the rule's phrase exactly. The Commands tab lists everything you can bind.") {
        if (hub == null) Text("Microphone not running — grant the microphone permission and keep the app in the foreground.", color = UxColors.Amber)
        val counts = hud?.enrolledCounts ?: emptyMap()
        val ruleCommands = t.rules.mapNotNull { (it.trigger as? Trigger.VoiceCommand)?.phrase?.trim()?.lowercase() }.distinct()
        (counts.keys + ruleCommands).distinct().sorted().forEach { cmd ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("“$cmd”")
                    Text("${counts[cmd] ?: 0} sample${if ((counts[cmd] ?: 0) == 1) "" else "s"}${if (hud?.enrolling == cmd) " · RECORDING — say it, then Stop & save" else ""}", style = MaterialTheme.typography.bodySmall,
                        color = if (hud?.enrolling == cmd) UxColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hud?.enrolling == cmd) RecordingMeter(hud)
                }
                if (hud?.enrolling == cmd) Button(onClick = { hub?.finishEnrollment() }, colors = ButtonDefaults.buttonColors(containerColor = UxColors.Green, contentColor = androidx.compose.ui.graphics.Color.Black)) { Text("Stop & save") }
                else Button(onClick = { hub?.beginEnrollment(cmd) }, enabled = hub != null && hud?.enrolling == null) { Text("Record") }
                if ((counts[cmd] ?: 0) > 0 && hud?.enrolling != cmd) TextButton(onClick = { hub?.deleteCommand(cmd) }) { Text("Clear") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = newCommand, onValueChange = { newCommand = it }, label = { Text("New command phrase") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { val c = newCommand.trim().lowercase(); if (c.isNotEmpty()) { hub?.beginEnrollment(c); newCommand = "" } }, enabled = hub != null && newCommand.isNotBlank()) { Text("Enroll") }
        }
        if (hud?.enrolling != null) TextButton(onClick = { hub?.cancelEnrollment() }) { Text("Cancel recording") }
        hud?.enrollError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = UxColors.Amber) }
        hud?.let { h ->
            Sep()
            KeyValue("Last heard", h.lastKeyword ?: "—")
            KeyValue("Score / threshold", "${"%.2f".format(h.lastKeywordScore)} / ${"%.2f".format(h.keywordThreshold)}${if (h.lastKeywordSecond < 1e6f) "  (2nd ${"%.2f".format(h.lastKeywordSecond)})" else ""}")
            KeyValue("Speech activity", if (h.speechActive) "speaking" else "silence")
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Tuning
// ------------------------------------------------------------------------------------------------

@Composable
private fun TuningTab(graph: AppGraph, s: AppSettings) {
    val t = s.triggers
    SectionCard("General") {
        SwitchRow("Armed when the app starts", t.armedByDefault) { on -> graph.updTriggers { it.copy(armedByDefault = on) } }
        IntSliderRow("Countdown before a triggered start", t.countdownSeconds, 0, 10, suffix = " s") { v -> graph.updTriggers { it.copy(countdownSeconds = v) } }
        IntSliderRow("Global cooldown", (t.globalCooldownMs / 100).toInt(), 0, 50, suffix = "00 ms") { v -> graph.updTriggers { it.copy(globalCooldownMs = v * 100L) } }
        PickerRow("Gesture camera", GestureCameraSource.entries.map { it to it.label }, t.gestureCamera, "Using the other camera needs concurrent-streaming support") { v -> graph.updTriggers { it.copy(gestureCamera = v) } }
        PickerRow("Analysis size", listOf(Pair(480, 360) to "480×360 (fast)", Pair(640, 360) to "640×360", Pair(640, 480) to "640×480 (default)", Pair(960, 540) to "960×540", Pair(1280, 720) to "1280×720 (longer range)"), Pair(s.analysis.width, s.analysis.height)) { (w, h) -> graph.settings.update { it.copy(analysis = it.analysis.copy(width = w, height = h)) } }
        IntSliderRow("Analysis rate", s.analysis.targetFps, 4, 30, suffix = " fps") { v -> graph.settings.update { it.copy(analysis = it.analysis.copy(targetFps = v)) } }
    }
    SectionCard("Hands (MediaPipe)") {
        val h = t.hand
        fun upd(f: (HandGestureConfig) -> HandGestureConfig) = graph.updTriggers { it.copy(hand = f(it.hand)) }
        SwitchRow("Pinch / unpinch zoom gestures", h.pinchZoom, "Thumb and index finger: spread = zoom in, close = zoom out (one zoom step per movement)") { upd { x -> x.copy(pinchZoom = it) } }
        SwitchRow("Continuous pinch zoom", h.continuousPinchZoom, "Hold the pinch for a moment, then open or close the fingers to drive the zoom live", enabled = h.pinchZoom) { upd { x -> x.copy(continuousPinchZoom = it) } }
        if (h.continuousPinchZoom) SliderRow("Pinch zoom gain", h.pinchZoomGain, 0.5f..3f, format = { "%.1f×".format(it) }) { upd { x -> x.copy(pinchZoomGain = it) } }
        SwitchRow("Hand gestures", h.enabled) { upd { x -> x.copy(enabled = it) } }
        PickerRow("Compute delegate", MlDelegate.entries.map { it to it.label }, h.delegate, "Restart the camera screen after changing") { upd { x -> x.copy(delegate = it) } }
        IntSliderRow("Max hands", h.maxHands, 1, 2) { upd { x -> x.copy(maxHands = it) } }
        SliderRow("Detection confidence", h.minDetectionConfidence, 0.2f..0.9f) { upd { x -> x.copy(minDetectionConfidence = it) } }
        SliderRow("Presence confidence", h.minPresenceConfidence, 0.2f..0.9f) { upd { x -> x.copy(minPresenceConfidence = it) } }
        SliderRow("Tracking confidence", h.minTrackingConfidence, 0.2f..0.9f) { upd { x -> x.copy(minTrackingConfidence = it) } }
        IntSliderRow("Stability frames before a gesture counts", h.stabilityFrames, 1, 8) { upd { x -> x.copy(stabilityFrames = it) } }
        IntSliderRow("Release grace", (h.releaseGraceMs / 50).toInt(), 1, 20, suffix = " ×50 ms") { upd { x -> x.copy(releaseGraceMs = it * 50L) } }
    }
    SectionCard("Face (ML Kit)") {
        val f = t.face
        fun upd(fn: (FaceGestureConfig) -> FaceGestureConfig) = graph.updTriggers { it.copy(face = fn(it.face)) }
        SwitchRow("Face signals", f.enabled) { upd { x -> x.copy(enabled = it) } }
        SliderRow("Eye closed below", f.eyeClosedThreshold, 0.05f..0.5f) { upd { x -> x.copy(eyeClosedThreshold = it) } }
        SliderRow("Eye open above", f.eyeOpenThreshold, 0.4f..0.95f) { upd { x -> x.copy(eyeOpenThreshold = it) } }
        IntSliderRow("Blink max duration", (f.blinkMaxMs / 50).toInt(), 2, 20, suffix = " ×50 ms") { upd { x -> x.copy(blinkMaxMs = it * 50L) } }
        IntSliderRow("Blink burst gap", (f.blinkBurstGapMs / 100).toInt(), 3, 15, suffix = "00 ms") { upd { x -> x.copy(blinkBurstGapMs = it * 100L) } }
        SliderRow("Smile threshold", f.smileThreshold, 0.5f..0.95f) { upd { x -> x.copy(smileThreshold = it) } }
        SliderRow("Mouth-open ratio", f.mouthOpenRatio, 0.3f..0.6f) { upd { x -> x.copy(mouthOpenRatio = it) } }
        SliderRow("Nod angle", f.nodDegrees, 4f..20f, format = { "${it.roundToInt()}°" }) { upd { x -> x.copy(nodDegrees = it) } }
        SliderRow("Shake angle", f.shakeDegrees, 4f..25f, format = { "${it.roundToInt()}°" }) { upd { x -> x.copy(shakeDegrees = it) } }
        SliderRow("Tilt angle", f.tiltDegrees, 8f..35f, format = { "${it.roundToInt()}°" }) { upd { x -> x.copy(tiltDegrees = it) } }
        SliderRow("Minimum face size", f.minFaceSize, 0.05f..0.3f, subtitle = "Fraction of the frame; smaller finds distant faces but costs CPU") { upd { x -> x.copy(minFaceSize = it) } }
    }
    SectionCard("Sounds") {
        val a = t.audio
        fun upd(fn: (AudioTriggerConfig) -> AudioTriggerConfig) = graph.updTriggers { it.copy(audio = fn(it.audio)) }
        SwitchRow("Sound triggers (clap, snap, whistle, loud)", a.enabled) { upd { x -> x.copy(enabled = it) } }
        SliderRow("Clap sensitivity", a.clapSensitivity, 0f..1f) { upd { x -> x.copy(clapSensitivity = it) } }
        IntSliderRow("Max gap between claps", (a.clapMaxGapMs / 100).toInt(), 3, 12, suffix = "00 ms") { upd { x -> x.copy(clapMaxGapMs = it * 100L) } }
        SliderRow("Snap sensitivity", a.snapSensitivity, 0f..1f) { upd { x -> x.copy(snapSensitivity = it) } }
        SliderRow("Whistle sensitivity", a.whistleSensitivity, 0f..1f) { upd { x -> x.copy(whistleSensitivity = it) } }
        SliderRow("Loud-sound threshold", a.loudThresholdDbfs, -40f..0f, format = { "${it.roundToInt()} dBFS" }) { upd { x -> x.copy(loudThresholdDbfs = it) } }
    }
    SectionCard("Device") {
        val d = t.device
        fun upd(fn: (DeviceTriggerConfig) -> DeviceTriggerConfig) = graph.updTriggers { it.copy(device = fn(it.device)) }
        SwitchRow("Volume keys", d.volumeKeys) { upd { x -> x.copy(volumeKeys = it) } }
        SwitchRow("Bluetooth remote / headset buttons", d.bluetoothButtons, "Holds a media session while the app is open") { upd { x -> x.copy(bluetoothButtons = it) } }
        SwitchRow("Shake", d.shake) { upd { x -> x.copy(shake = it) } }
        SwitchRow("Proximity sensor wave", d.proximity) { upd { x -> x.copy(proximity = it) } }
    }
    SectionCard("Confirmation feedback") {
        val f = t.feedback
        fun upd(fn: (FeedbackConfig) -> FeedbackConfig) = graph.updTriggers { it.copy(feedback = fn(it.feedback)) }
        SwitchRow("Haptics", f.haptic) { upd { x -> x.copy(haptic = it) } }
        SwitchRow("Beep", f.beep, "Beeps are picked up by the microphone; disable for silent sets") { upd { x -> x.copy(beep = it) } }
        SliderRow("Beep volume", f.beepVolume, 0f..1f, enabled = f.beep) { upd { x -> x.copy(beepVolume = it) } }
        SwitchRow("Screen flash", f.screenFlash) { upd { x -> x.copy(screenFlash = it) } }
        SwitchRow("Spoken announcements (TTS)", f.speak) { upd { x -> x.copy(speak = it) } }
    }
}

// ------------------------------------------------------------------------------------------------
// Monitor
// ------------------------------------------------------------------------------------------------

@Composable
private fun MonitorTab(graph: AppGraph) {
    val log by graph.triggers.log.collectAsStateWithLifecycle()
    val vision by graph.dispatcher.hud.collectAsStateWithLifecycle()
    val hub by graph.audioHubState.collectAsStateWithLifecycle()
    val hud = hub?.hud?.collectAsStateWithLifecycle()?.value
    val state by graph.controller.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionCard("Now") {
                KeyValue("Recorder", state.name)
                KeyValue("Hands", if (!vision.handsAvailable) "model not loaded" else "${vision.hands.size} detected · ${vision.heldGesture ?: "—"} ${if (vision.heldMs > 0) "${vision.heldMs} ms" else ""}")
                KeyValue("Faces", if (!vision.facesAvailable) "model not loaded" else "${vision.faces.size} · blinks in burst ${vision.blinkCount}")
                KeyValue("Vision", "${vision.analysisFps.roundToInt()} fps · ${vision.inferenceMs.roundToInt()} ms · ${vision.frameWidth}×${vision.frameHeight}")
                vision.lastError?.let { KeyValue("Vision error", it) }
                if (hud != null) {
                    KeyValue("Audio level", "${hud.levelDbfs.roundToInt()} dBFS (background ${hud.backgroundDbfs.roundToInt()})")
                    KeyValue("Claps in burst", "${hud.clapsInBurst} (last burst ${hud.lastClapBurst})")
                    KeyValue("Whistle", if (hud.whistleHz > 0f) "${hud.whistleHz.roundToInt()} Hz" else "—")
                    KeyValue("Keyword", hud.lastKeyword ?: "—")
                } else KeyValue("Audio", "microphone not running")
            }
        }
        item { Text("Event log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        items(log) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(if (e.fired) "▶ " else "· ", color = if (e.fired) UxColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
