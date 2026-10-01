package com.jarvis.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.hudColors

/** Minimal "glass" panel: translucent gradient fill with a hairline accent border (no blur, cheap to draw). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    accent: Color = hudColors().accent,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit,
) {
    val colors = hudColors()
    val fillTop = if (colors.isLight) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.07f)
    val fillBottom = if (colors.isLight) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.02f)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(fillTop, fillBottom)), shape)
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .padding(padding),
    ) { content() }
}
