package com.ultrax26.recorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ultrax26.recorder.settings.UiThemeMode

object UxColors {
    val Orange = Color(0xFFF97316)
    val Sky = Color(0xFF38BDF8)
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Slate = Color(0xFF94A3B8)
    val Ink = Color(0xFF0B0F19)
    val Panel = Color(0xCC111827)
    val PanelSolid = Color(0xFF111827)
    val Black = Color(0xFF000000)
}

@Composable
fun UltraXTheme(mode: UiThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { UiThemeMode.SYSTEM -> isSystemInDarkTheme(); else -> true }
    val scheme = if (dark) darkColorScheme(
        primary = UxColors.Orange,
        onPrimary = Color.Black,
        secondary = UxColors.Sky,
        onSecondary = Color.Black,
        tertiary = UxColors.Green,
        background = if (mode == UiThemeMode.AMOLED) UxColors.Black else UxColors.Ink,
        surface = if (mode == UiThemeMode.AMOLED) Color(0xFF05070C) else UxColors.PanelSolid,
        surfaceVariant = Color(0xFF1F2937),
        onSurface = Color(0xFFE5E7EB),
        onSurfaceVariant = Color(0xFFCBD5E1),
        error = UxColors.Red,
        outline = Color(0xFF334155),
    ) else lightColorScheme(primary = UxColors.Orange, secondary = UxColors.Sky)
    MaterialTheme(colorScheme = scheme, content = content)
}
