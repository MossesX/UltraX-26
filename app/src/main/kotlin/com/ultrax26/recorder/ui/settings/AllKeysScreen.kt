package com.ultrax26.recorder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.camera.GenericKeyCodec
import com.ultrax26.recorder.settings.CustomKeyValue
import com.ultrax26.recorder.settings.KeyType
import com.ultrax26.recorder.ui.components.PickerRow
import com.ultrax26.recorder.ui.components.SwitchRow
import com.ultrax26.recorder.ui.theme.UxColors

/**
 * Every CaptureRequest key the HAL advertises for the current camera — standard and vendor — with a
 * typed override editor, plus the live CaptureResult values.
 */
@Composable
fun AllKeysScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val dump by graph.engine.resultDump.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    val cameraId = settings.capture.cameraId ?: graph.catalog.defaultBackId()
    val keyNames = remember(cameraId) { runCatching { cameraId?.let { graph.catalog.characteristics(it).availableCaptureRequestKeys.map { k -> k.name } } }.getOrNull()?.sorted() ?: emptyList() }
    DisposableEffect(Unit) { graph.engine.dumpResults = true; onDispose { graph.engine.dumpResults = false } }
    LaunchedEffect(Unit) { graph.engine.templateValuesAsync { templates = it } }
    val overrides = settings.capture.customKeys.associateBy { it.keyName }
    val filtered = keyNames.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    val vendorCount = keyNames.count { !it.startsWith("android.") }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeader("All camera keys", onBack) {
            TextButton(onClick = { showResults = !showResults }) { Text(if (showResults) "Request keys" else "Live results") }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search ${keyNames.size} keys ($vendorCount vendor)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (showResults) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                if (dump.isEmpty()) item { Text("Waiting for capture results… (the camera must be running)", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(dump.filter { query.isBlank() || it.first.contains(query, true) }) { (k, v) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = if (k.startsWith("android.")) MaterialTheme.colorScheme.onSurface else UxColors.Orange)
                        Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (overrides.isNotEmpty()) item { Text("${overrides.size} override${if (overrides.size == 1) "" else "s"} active (${overrides.count { it.value.enabled }} enabled)", modifier = Modifier.padding(12.dp), color = UxColors.Amber) }
                items(filtered, key = { it }) { name -> KeyRow(graph, name, templates[name], overrides[name]) }
            }
        }
    }
}

@Composable
private fun KeyRow(graph: AppGraph, name: String, templateValue: Any?, override: CustomKeyValue?) {
    var expanded by remember { mutableStateOf(false) }
    val inferred = GenericKeyCodec.inferType(templateValue)
    var type by remember(name, override) { mutableStateOf(override?.type ?: inferred ?: KeyType.INT) }
    var text by remember(name, override) { mutableStateOf(override?.value ?: (if (templateValue != null) GenericKeyCodec.format(templateValue) else "")) }
    var error by remember { mutableStateOf<String?>(null) }
    val vendor = !name.startsWith("android.")
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = if (vendor) UxColors.Orange else MaterialTheme.colorScheme.onSurface)
                Text("${inferred?.label ?: "type unknown"} · template: ${GenericKeyCodec.format(templateValue)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (override != null) Text(if (override.enabled) "OVERRIDE" else "off", style = MaterialTheme.typography.labelSmall, color = if (override.enabled) UxColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            PickerRow("Value type", KeyType.entries.map { it to it.label }, type) { type = it }
            OutlinedTextField(value = text, onValueChange = { text = it; error = null }, label = { Text("Value") }, singleLine = false, modifier = Modifier.fillMaxWidth(), isError = error != null, supportingText = { Text(error ?: hint(type)) })
            Row {
                Button(onClick = {
                    try {
                        GenericKeyCodec.parse(type, text)
                        graph.settings.update { s -> s.copy(capture = s.capture.copy(customKeys = s.capture.customKeys.filter { it.keyName != name } + CustomKeyValue(name, type, text, true))) }
                        error = null
                    } catch (t: Throwable) { error = t.message }
                }) { Text("Apply override") }
                Spacer(Modifier.width(8.dp))
                if (override != null) {
                    OutlinedButton(onClick = { graph.settings.update { s -> s.copy(capture = s.capture.copy(customKeys = s.capture.customKeys.filter { it.keyName != name })) } }) { Text("Remove") }
                }
            }
            if (override != null) SwitchRow("Enabled", override.enabled) { on -> graph.settings.update { s -> s.copy(capture = s.capture.copy(customKeys = s.capture.customKeys.map { if (it.keyName == name) it.copy(enabled = on) else it })) } }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}

private fun hint(t: KeyType): String = when (t) {
    KeyType.RANGE_INT, KeyType.RANGE_LONG, KeyType.RANGE_FLOAT -> "lower, upper"
    KeyType.SIZE -> "width x height"
    KeyType.RATIONAL -> "numerator/denominator"
    KeyType.RECT -> "left, top, right, bottom"
    KeyType.METERING_RECTANGLES -> "x, y, w, h, weight; x, y, w, h, weight"
    KeyType.RGGB_CHANNEL_VECTOR -> "r, gEven, gOdd, b"
    KeyType.COLOR_SPACE_TRANSFORM -> "9 rationals or decimals, row-major"
    KeyType.TONEMAP_CURVE -> "in,out,in,out… or r: …; g: …; b: …"
    KeyType.INT_ARRAY, KeyType.FLOAT_ARRAY, KeyType.LONG_ARRAY, KeyType.BYTE_ARRAY, KeyType.BOOLEAN_ARRAY, KeyType.DOUBLE_ARRAY -> "comma separated"
    KeyType.BOOLEAN -> "true / false"
    else -> "single value"
}
