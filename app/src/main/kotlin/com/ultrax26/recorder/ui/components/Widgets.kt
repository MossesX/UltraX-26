package com.ultrax26.recorder.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) }, enabled: Boolean = true, subtitle: String? = null,
    onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled, onValueChangeFinished = onFinished)
    }
}

@Composable
fun IntSliderRow(title: String, value: Int, min: Int, max: Int, step: Int = 1, suffix: String = "", enabled: Boolean = true, subtitle: String? = null, onChange: (Int) -> Unit) {
    val stepsCount = if (step > 0 && (max - min) / step > 1) ((max - min) / step - 1).coerceAtLeast(0) else 0
    SliderRow(title, value.toFloat(), min.toFloat()..max.toFloat(), steps = stepsCount, format = { "${it.roundToInt()}$suffix" }, enabled = enabled, subtitle = subtitle,
        onChange = { onChange((((it - min) / step).roundToInt() * step + min).coerceIn(min, max)) })
}

/** A row that opens a picker dialog with radio options. */
@Composable
fun <T> PickerRow(title: String, options: List<Pair<T, String>>, selected: T, subtitle: String? = null, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected?.toString() ?: "—"
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { (v, l) ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(v); open = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = v == selected, onClick = { onSelect(v); open = false })
                            Spacer(Modifier.width(8.dp))
                            Text(l, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
        )
    }
}

/** Nullable-int picker: first option is "Auto / device default" (null). */
@Composable
fun NullableIntPickerRow(title: String, options: List<Pair<Int, String>>, selected: Int?, autoLabel: String = "Auto (device default)", enabled: Boolean = true, subtitle: String? = null, onSelect: (Int?) -> Unit) {
    val opts: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to autoLabel) + options.map { it.first as Int? to it.second }
    PickerRow(title, opts, selected, subtitle, enabled) { onSelect(it) }
}

@Composable
fun TextFieldRow(title: String, value: String, onChange: (String) -> Unit, subtitle: String? = null, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it; onChange(it) }, label = { Text(title) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), supportingText = if (subtitle != null) ({ Text(subtitle) }) else null)
    }
}

@Composable
fun NumberFieldRow(title: String, value: String, onChange: (String) -> Unit, subtitle: String? = null) =
    TextFieldRow(title, value, onChange, subtitle, keyboard = KeyboardType.Decimal)

@Composable
fun ChipRow(options: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, (label, selected) -> FilterChip(selected = selected, onClick = { onClick(i) }, label = { Text(label) }) }
    }
}

@Composable
fun Sep() { HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline) }

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Runs [block] once after [key] stabilizes (debounce) — used for text-field driven settings. */
@Composable
fun Debounced(key: Any?, delayMs: Long = 400, block: suspend () -> Unit) {
    LaunchedEffect(key) { kotlinx.coroutines.delay(delayMs); block() }
}
