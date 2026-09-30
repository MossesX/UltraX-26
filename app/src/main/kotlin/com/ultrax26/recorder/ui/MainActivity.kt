package com.ultrax26.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.UltraXApp
import com.ultrax26.recorder.ui.camera.CameraScreen
import com.ultrax26.recorder.ui.settings.AllKeysScreen
import com.ultrax26.recorder.ui.settings.DiagnosticsScreen
import com.ultrax26.recorder.ui.settings.SettingsScreen
import com.ultrax26.recorder.ui.settings.TriggersScreen
import com.ultrax26.recorder.ui.theme.UltraXTheme

sealed class Screen {
    data object Camera : Screen()
    data class Settings(val tab: Int = 0) : Screen()
    data object Triggers : Screen()
    data object AllKeys : Screen()
    data object Diagnostics : Screen()
}

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = UltraXApp.graph(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Root(graph) }
    }

    override fun onStart() { super.onStart(); graph.setForeground(true) }
    override fun onStop() { super.onStop(); graph.setForeground(false) }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateRotation()
        if (graph.settings.current.ui.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    private fun updateRotation() {
        val rot = try { display?.rotation ?: Surface.ROTATION_0 } catch (_: Throwable) { Surface.ROTATION_0 }
        graph.controller.setDisplayRotation(when (rot) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 })
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (graph.volumeKeys.onKeyDown(keyCode, event.repeatCount)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (graph.volumeKeys.onKeyUp(keyCode)) true else super.onKeyUp(keyCode, event)
}

private val REQUIRED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

@Composable
fun Root(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    UltraXTheme(settings.ui.theme) {
        val ctx = LocalContext.current
        var granted by remember { mutableStateOf(REQUIRED.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
        var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            cameraGranted = res[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            granted = REQUIRED.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            graph.onPermissionsChanged()
        }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(REQUIRED) }

        var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
        val back = { screen = Screen.Camera }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!cameraGranted) {
                PermissionGate { launcher.launch(REQUIRED) }
            } else when (val s = screen) {
                Screen.Camera -> CameraScreen(graph) { screen = it }
                is Screen.Settings -> { BackHandler { back() }; SettingsScreen(graph, s.tab, onBack = back) { screen = it } }
                Screen.Triggers -> { BackHandler { back() }; TriggersScreen(graph, onBack = back) }
                Screen.AllKeys -> { BackHandler { screen = Screen.Settings(2) }; AllKeysScreen(graph) { screen = Screen.Settings(2) } }
                Screen.Diagnostics -> { BackHandler { screen = Screen.Settings(6) }; DiagnosticsScreen(graph) { screen = Screen.Settings(6) } }
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("UltraX 26", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Camera and microphone access are required to record video and to hear claps, whistles and voice commands. Notifications keep the recording alive when you switch apps.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}
