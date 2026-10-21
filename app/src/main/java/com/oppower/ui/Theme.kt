package com.oppower.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF0B1115)
val Surface = Color(0xFF131D22)
val SurfaceRaised = Color(0xFF19272D)
val Cyan = Color(0xFF72F5D2)
val Amber = Color(0xFFFFC66B)
val TextPrimary = Color(0xFFE8F6F2)
val TextSecondary = Color(0xFFA5B9BC)
val Danger = Color(0xFFFF8E86)

private val colors = darkColorScheme(
    primary = Cyan,
    secondary = Amber,
    background = Background,
    surface = Surface,
    onPrimary = Color(0xFF06241D),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun OPPowerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
