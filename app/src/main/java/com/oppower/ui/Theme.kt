package com.oppower.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.oppower.model.RuntimeSettings
import com.oppower.model.ThemeMode
import com.oppower.model.ThemePalette

data class UiColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val accent: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val danger: Color,
    val outline: Color,
    val accentSurface: Color,
    val secondarySurface: Color,
)

private val darkColors = UiColors(
    background = Color(0xFF0A232A),
    surface = Color(0xFF15353C),
    surfaceRaised = Color(0xFF1A3F46),
    accent = Color(0xFF79E6D1),
    secondary = Color(0xFFE6C184),
    textPrimary = Color(0xFFEDF6F4),
    textSecondary = Color(0xFF9FB2B4),
    danger = Color(0xFFFF8880),
    outline = Color(0xFF2C4A50),
    accentSurface = Color(0xFF143338),
    secondarySurface = Color(0xFF30291D),
)

private val lightColors = UiColors(
    background = Color(0xFFEDF4F2),
    surface = Color(0xFFF9FCFB),
    surfaceRaised = Color(0xFFE7EFED),
    accent = Color(0xFF247A6B),
    secondary = Color(0xFF7B6540),
    textPrimary = Color(0xFF142022),
    textSecondary = Color(0xFF5B6A6D),
    danger = Color(0xFFB84D46),
    outline = Color(0xFFCDDAD7),
    accentSurface = Color(0xFFDDEDE8),
    secondarySurface = Color(0xFFF1E9DB),
)

private fun paletteColors(palette: ThemePalette, dark: Boolean): UiColors {
    val base = if (dark) darkColors else lightColors
    val accents = when (palette) {
        ThemePalette.TEAL -> base.accent to base.accentSurface
        ThemePalette.OCEAN -> if (dark) Color(0xFF79C9F2) to Color(0xFF172A35) else Color(0xFF16668E) to Color(0xFFE1F1F8)
        ThemePalette.FOREST -> if (dark) Color(0xFF98D88E) to Color(0xFF1D2D20) else Color(0xFF3B7134) to Color(0xFFE8F4E5)
        ThemePalette.AMBER -> if (dark) Color(0xFFE5C079) to Color(0xFF30291D) else Color(0xFF8A6129) to Color(0xFFF8ECD8)
        ThemePalette.ROSE -> if (dark) Color(0xFFF1A8B5) to Color(0xFF342329) else Color(0xFF9C4658) to Color(0xFFF9E7EB)
        ThemePalette.VIOLET -> if (dark) Color(0xFFC7B2F4) to Color(0xFF29243A) else Color(0xFF674B9A) to Color(0xFFEEE8FA)
    }
    return base.copy(accent = accents.first, accentSurface = accents.second)
}

private var activeColors = darkColors

val Background: Color get() = activeColors.background
val Surface: Color get() = activeColors.surface
val SurfaceRaised: Color get() = activeColors.surfaceRaised
val Cyan: Color get() = activeColors.accent
val Amber: Color get() = activeColors.secondary
val TextPrimary: Color get() = activeColors.textPrimary
val TextSecondary: Color get() = activeColors.textSecondary
val Danger: Color get() = activeColors.danger
val Outline: Color get() = activeColors.outline
val AccentSurface: Color get() = activeColors.accentSurface
val AmberSurface: Color get() = activeColors.secondarySurface

@Composable
fun OPPowerTheme(settings: RuntimeSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val density = LocalDensity.current
    val colors = paletteColors(settings.themePalette, dark).let {
        it.copy(
            surface = it.surface.copy(alpha = settings.cardOpacity.coerceIn(0.65f, 1f)),
            surfaceRaised = it.surfaceRaised.copy(alpha = settings.cardOpacity.coerceIn(0.65f, 1f)),
        )
    }
    activeColors = colors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = Color(0xFF06241D),
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = Color.White,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    }
    // Theme changes must update the existing composition in place. Re-keying this
    // subtree discarded the current settings page and sent the user back home.
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * settings.uiScale.coerceIn(0.85f, 1.15f),
            fontScale = density.fontScale * settings.fontScale.coerceIn(0.85f, 1.2f),
        ),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
