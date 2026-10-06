package com.friday.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalEnergy

/** Translucent HUD panel with a thin technical outline. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val e = LocalEnergy.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .background(Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x0AFFFFFF))), shape)
            .border(BorderStroke(1.dp, Brush.linearGradient(listOf(e.primary.copy(alpha = 0.7f), e.secondary.copy(alpha = 0.15f)))), shape)
            .padding(14.dp),
    ) { content() }
}

@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, color: Color = FridayColors.TextDim) {
    Text(text, modifier, color = color, fontSize = 11.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), Modifier.padding(top = 18.dp, bottom = 6.dp),
        color = LocalEnergy.current.primary, fontSize = 12.sp, letterSpacing = 3.sp,
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge,
    )
}
