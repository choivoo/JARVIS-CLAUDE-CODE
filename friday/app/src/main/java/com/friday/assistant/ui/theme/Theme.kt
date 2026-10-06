package com.friday.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.friday.assistant.settings.AccentTheme

/** Energy colours of the HUD. */
data class Energy(val primary: Color, val secondary: Color, val tertiary: Color)

val LocalEnergy = compositionLocalOf { energyFor(AccentTheme.VIOLET) }

/** When true the HUD draws static frames (used by UI tests so Compose can reach idle). */
val LocalReduceMotion = compositionLocalOf { false }

fun energyFor(a: AccentTheme) = when (a) {
    AccentTheme.VIOLET -> Energy(Color(0xFFB26BFF), Color(0xFF6A8DFF), Color(0xFFFF4FD8))
    AccentTheme.MAGENTA -> Energy(Color(0xFFFF4FD8), Color(0xFFB26BFF), Color(0xFF6A8DFF))
    AccentTheme.BLUE -> Energy(Color(0xFF5AA9FF), Color(0xFFB26BFF), Color(0xFF3DF2FF))
}

object FridayColors {
    val Amoled = Color(0xFF000000)
    val Dark = Color(0xFF0B0A14)
    val Panel = Color(0x33FFFFFF)
    val PanelEdge = Color(0x44B26BFF)
    val Text = Color(0xFFEDE7FF)
    val TextDim = Color(0xFF9A93B8)
    val Error = Color(0xFFFF5470)
    val Ok = Color(0xFF52E5A3)
}

@Composable
fun FridayTheme(
    accent: AccentTheme = AccentTheme.VIOLET,
    amoled: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val e = energyFor(accent)
    val bg = if (amoled) FridayColors.Amoled else FridayColors.Dark
    val scheme = darkColorScheme(
        primary = e.primary, secondary = e.secondary, tertiary = e.tertiary,
        background = bg, surface = bg, onBackground = FridayColors.Text, onSurface = FridayColors.Text,
        surfaceVariant = Color(0xFF15122A), onSurfaceVariant = FridayColors.TextDim, error = FridayColors.Error,
    )
    CompositionLocalProvider(LocalEnergy provides e, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
