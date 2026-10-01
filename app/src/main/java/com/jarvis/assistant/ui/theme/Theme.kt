package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.core.AssistantPhase
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

/** Core accent per assistant state (blue / cyan family; amber and red only for action and error). */
fun AssistantPhase.accent(light: Boolean): Color = when (this) {
    AssistantPhase.IDLE -> if (light) Color(0xFF0089B0) else Color(0xFF18D8FF)
    AssistantPhase.LISTENING -> if (light) Color(0xFF00A88A) else Color(0xFF3DFFE0)
    AssistantPhase.THINKING -> if (light) Color(0xFF2F5BD8) else Color(0xFF5B8CFF)
    AssistantPhase.SPEAKING -> if (light) Color(0xFF0098C8) else Color(0xFF7DF3FF)
    AssistantPhase.EXECUTING -> if (light) Color(0xFFC77700) else Color(0xFFFFC857)
    AssistantPhase.ERROR -> if (light) Color(0xFFC62828) else Color(0xFFFF4D5E)
}

@Composable
fun JarvisTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val hud = when (mode) {
        ThemeMode.DARK -> DarkHud
        ThemeMode.AMOLED -> AmoledHud
        ThemeMode.LIGHT -> LightHud
    }
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
