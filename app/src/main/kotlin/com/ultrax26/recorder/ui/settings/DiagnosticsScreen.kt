package com.ultrax26.recorder.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.diagnostics.CameraReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen(graph: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fullKeys by remember { mutableStateOf(true) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val r = report
        if (uri != null && r != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(r.toByteArray()) } }
    }
    fun generate() {
        busy = true
        scope.launch {
            val s = graph.settings.current
            report = withContext(Dispatchers.IO) { runCatching { CameraReport.build(ctx, graph.catalog, s.capture.probeHiddenCameraIds, s.capture.hiddenIdProbeMax, fullKeys) }.getOrElse { "Report failed: $it" } }
            busy = false
        }
    }
    LaunchedEffect(Unit) { generate() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsHeader("Device report", onBack) {
            TextButton(onClick = { fullKeys = !fullKeys; generate() }) { Text(if (fullKeys) "Compact" else "Full keys") }
            TextButton(onClick = { report?.let { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UltraX26 report", it)) } }, enabled = report != null) { Text("Copy") }
            TextButton(onClick = { saver.launch("ultrax26-device-report.txt") }, enabled = report != null) { Text("Save") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionContainer {
            Text(report ?: "Generating…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}
