package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.hudColors

/**
 * HUD backdrop: faint grid, corner brackets, edge rulers, a centre glow and a slow vertical
 * scan beam. The static layer only redraws when the accent colour changes; the beam is a second,
 * very small canvas so it costs almost nothing.
 */
@Composable
fun HudBackground(accent: Color, modifier: Modifier = Modifier) {
    val colors = hudColors()
    val transition = rememberInfiniteTransition(label = "scan")
    val beam by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "beam",
    )
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.background)
            val c = Offset(size.width / 2f, size.height * 0.36f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = if (colors.isLight) 0.10f else 0.15f), Color.Transparent),
                    center = c, radius = size.width * 0.95f,
                ),
                radius = size.width * 0.95f, center = c,
            )
            // Grid
            val step = 36.dp.toPx()
            val gridColor = colors.line.copy(alpha = if (colors.isLight) 0.35f else 0.20f)
            var x = 0f
            while (x <= size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.6f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.6f)
                y += step
            }
            // Edge rulers (left and right): short ticks, longer every fifth
            val tick = accent.copy(alpha = 0.35f)
            var ty = 90.dp.toPx()
            var n = 0
            while (ty < size.height - 90.dp.toPx()) {
                val len = if (n % 5 == 0) 10.dp.toPx() else 5.dp.toPx()
                drawLine(tick, Offset(0f, ty), Offset(len, ty), strokeWidth = 1f)
                drawLine(tick, Offset(size.width, ty), Offset(size.width - len, ty), strokeWidth = 1f)
                ty += 14.dp.toPx()
                n++
            }
            // Corner brackets
            val m = 14.dp.toPx()
            val l = 28.dp.toPx()
            val bc = accent.copy(alpha = 0.6f)
            val w = 1.8f.dp.toPx()
            fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(bc, Offset(cx, cy), Offset(cx + dx * l, cy), strokeWidth = w)
                drawLine(bc, Offset(cx, cy), Offset(cx, cy + dy * l), strokeWidth = w)
            }
            bracket(m, m, 1f, 1f)
            bracket(size.width - m, m, -1f, 1f)
            bracket(m, size.height - m, 1f, -1f)
            bracket(size.width - m, size.height - m, -1f, -1f)
        }
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * beam
            val h = 90.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = if (colors.isLight) 0.06f else 0.09f), Color.Transparent),
                    startY = y - h, endY = y + h,
                ),
                topLeft = Offset(0f, y - h),
                size = androidx.compose.ui.geometry.Size(size.width, h * 2),
            )
            drawLine(accent.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            // Vignette keeps the edges deep black on AMOLED
            if (!colors.isLight) {
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        center = Offset(size.width / 2, size.height / 2), radius = size.height * 0.75f,
                    ),
                )
            }
        }
    }
}
