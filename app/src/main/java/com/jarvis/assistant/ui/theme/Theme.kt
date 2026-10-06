package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.data.model.AccentStyle
import com.jarvis.assistant.data.model.ThemeMode

/** Palette used by the custom HUD drawing; Material colours are derived from it. */
data class HudColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val accentSoft: Color,
    val text: Color,
    val textDim: Color,
    val line: Color,
    val isLight: Boolean,
    val style: AccentStyle = AccentStyle.ARC_BLUE,
)

private val DarkHud = HudColors(
    background = Color(0xFF03070B),
    surface = Color(0xFF0A141C),
    accent = Color(0xFF18D8FF),
    accentSoft = Color(0xFF7DF3FF),
    text = Color(0xFFE6FAFF),
    textDim = Color(0xFF7FA6B5),
    line = Color(0xFF1B3A48),
    isLight = false,
)

private val AmoledHud = DarkHud.copy(background = Color(0xFF000000), surface = Color(0xFF050B10))

private val LightHud = HudColors(
    background = Color(0xFFEAF6FA),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF0089B0),
    accentSoft = Color(0xFF00A7D6),
    text = Color(0xFF06222C),
    textDim = Color(0xFF486874),
    line = Color(0xFFB4D5DF),
    isLight = true,
)

val LocalHudColors = staticCompositionLocalOf { AmoledHud }

/** Per-style colours for the five working states (dark / light variants share hue). */
private class Palette(
    val accent: Color, val soft: Color, val listening: Color, val thinking: Color, val speaking: Color, val executing: Color,
    val error: Color,
)

private fun palette(style: AccentStyle): Palette = when (style) {
    AccentStyle.ARC_BLUE -> Palette(
        Color(0xFF18D8FF), Color(0xFF7DF3FF), Color(0xFF3DFFE0), Color(0xFF5B8CFF), Color(0xFF7DF3FF), Color(0xFFFFC857), Color(0xFFFF4D5E),
    )
    AccentStyle.STARK_GOLD -> Palette(
        Color(0xFFFFB627), Color(0xFFFFE08A), Color(0xFFFFD166), Color(0xFFFF7B3D), Color(0xFFFFE9A8), Color(0xFF4DE1FF), Color(0xFFFF4D5E),
    )
    AccentStyle.MATRIX_GREEN -> Palette(
        Color(0xFF2CFF7A), Color(0xFF9BFFC1), Color(0xFF7DFFB0), Color(0xFF2CD6FF), Color(0xFFB8FFD6), Color(0xFFFFE14D), Color(0xFFFF4D5E),
    )
    AccentStyle.CRIMSON -> Palette(
        Color(0xFFFF3B5C), Color(0xFFFF9AAE), Color(0xFFFF6F8A), Color(0xFFB45BFF), Color(0xFFFFB4C2), Color(0xFFFFC857), Color(0xFFFFA23B),
    )
}

private fun Color.forLight(light: Boolean): Color = if (!light) this else
    Color(red * 0.62f, green * 0.62f, blue * 0.62f, alpha)

/** Core accent per assistant state. */
fun AssistantPhase.accent(colors: HudColors): Color {
    val p = palette(colors.style)
    val c = when (this) {
        AssistantPhase.IDLE -> p.accent
        AssistantPhase.LISTENING -> p.listening
        AssistantPhase.THINKING -> p.thinking
        AssistantPhase.SPEAKING -> p.speaking
        AssistantPhase.EXECUTING -> p.executing
        AssistantPhase.ERROR -> p.error
    }
    return c.forLight(colors.isLight)
}

@Composable
fun JarvisTheme(mode: ThemeMode, accentStyle: AccentStyle = AccentStyle.ARC_BLUE, content: @Composable () -> Unit) {
    val base = when (mode) {
        ThemeMode.DARK -> DarkHud
        ThemeMode.AMOLED -> AmoledHud
        ThemeMode.LIGHT -> LightHud
    }
    val p = palette(accentStyle)
    val hud = base.copy(
        accent = p.accent.forLight(base.isLight),
        accentSoft = p.soft.forLight(base.isLight),
        style = accentStyle,
    )
    val scheme = if (hud.isLight) {
        lightColorScheme(
            primary = hud.accent, onPrimary = Color.White, background = hud.background, onBackground = hud.text,
            surface = hud.surface, onSurface = hud.text, outline = hud.line, surfaceVariant = hud.surface,
            onSurfaceVariant = hud.textDim,
        )
    } else {
        darkColorScheme(
            primary = hud.accent, onPrimary = Color.Black, background = hud.background, onBackground = hud.text,
            surface = hud.surface, onSurface = hud.text, outline = hud.line, surfaceVariant = hud.surface,
            onSurfaceVariant = hud.textDim,
        )
    }
    CompositionLocalProvider(LocalHudColors provides hud) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun hudColors(): HudColors = LocalHudColors.current
