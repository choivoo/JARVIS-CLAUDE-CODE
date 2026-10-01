package com.jarvis.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.hudColors

/** Static HUD backdrop: faint grid, corner brackets and a centre glow (only redraws on state colour change). */
@Composable
fun HudBackground(accent: Color, modifier: Modifier = Modifier) {
    val colors = hudColors()
    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        drawRect(colors.background)
        // Centre glow
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = if (colors.isLight) 0.10f else 0.14f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.36f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width / 2f, size.height * 0.36f),
        )
        // Grid
        val step = 36.dp.toPx()
        val gridColor = colors.line.copy(alpha = if (colors.isLight) 0.35f else 0.22f)
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
        // Corner brackets
        val m = 14.dp.toPx()
        val l = 26.dp.toPx()
        val c = accent.copy(alpha = 0.55f)
        val w = 1.6f.dp.toPx()
        fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(cx, cy), Offset(cx + dx * l, cy), strokeWidth = w)
            drawLine(c, Offset(cx, cy), Offset(cx, cy + dy * l), strokeWidth = w)
        }
        bracket(m, m, 1f, 1f)
        bracket(size.width - m, m, -1f, 1f)
        bracket(m, size.height - m, 1f, -1f)
        bracket(size.width - m, size.height - m, -1f, -1f)
        // Scanline arc hint
        drawCircle(
            color = accent.copy(alpha = 0.05f),
            radius = size.width * 0.62f,
            center = Offset(size.width / 2f, size.height * 0.36f),
            style = Stroke(width = 1f),
        )
    }
}
