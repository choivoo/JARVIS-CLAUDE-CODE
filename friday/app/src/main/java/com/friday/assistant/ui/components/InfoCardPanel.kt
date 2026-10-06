package com.friday.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.command.CardKind
import com.friday.assistant.command.InfoCard
import com.friday.assistant.ui.theme.FridayColors

/** Contextual card (weather / calendar / media / notification / device). Shown only while it has something to say. */
@Composable
fun InfoCardPanel(card: InfoCard?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = card != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        card?.let { c ->
            GlassPanel(Modifier.fillMaxWidth().semantics { contentDescription = "${label(c.kind)} card" }) {
                Column {
                    HudLabel(label(c.kind))
                    Text(c.title, color = FridayColors.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                    c.lines.take(4).forEach { Text(it, color = FridayColors.TextDim, fontSize = 13.sp, maxLines = 1) }
                }
            }
        }
    }
}

private fun label(k: CardKind) = when (k) {
    CardKind.WEATHER -> "WEATHER"; CardKind.CALENDAR -> "CALENDAR"; CardKind.MEDIA -> "MEDIA"
    CardKind.NOTIFICATION -> "NOTIFICATIONS"; CardKind.DEVICE -> "DEVICE"
}
