package com.jarvis.assistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.database.MessageEntity
import com.jarvis.assistant.data.database.Role
import com.jarvis.assistant.ui.theme.hudColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageCard(message: MessageEntity, modifier: Modifier = Modifier, showTime: Boolean = false) {
    val colors = hudColors()
    val isUser = message.role == Role.USER
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        accent = if (isUser) colors.textDim else colors.accent,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isUser) "USER" else "JARVIS",
                    color = if (isUser) colors.textDim else colors.accent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                if (showTime) {
                    Text(
                        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                        color = colors.textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                "\"${message.text}\"",
                color = colors.text,
                fontSize = 13.sp,
                maxLines = if (showTime) 12 else 3,
            )
            if (!isUser && !message.subtitle.isNullOrBlank()) {
                Text(
                    message.subtitle,
                    color = colors.accentSoft,
                    fontSize = 12.sp,
                    maxLines = if (showTime) 12 else 2,
                )
            }
        }
    }
}
