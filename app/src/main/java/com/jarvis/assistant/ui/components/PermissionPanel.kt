package com.jarvis.assistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.hudColors

@Composable
fun MicPermissionPanel(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    val colors = hudColors()
    GlassPanel(modifier = modifier.fillMaxWidth(), accent = colors.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "MICROPHONE ACCESS REQUIRED",
                color = colors.accentSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                "JARVIS는 \"JARVIS\"라는 호출어와 음성 명령을 듣기 위해 마이크가 필요합니다. 듣고 있는 동안에는 항상 상태가 화면과 알림에 표시되며, 음성은 저장되지 않습니다.",
                color = colors.text,
                fontSize = 13.sp,
                textAlign = TextAlign.Start,
            )
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = androidx.compose.ui.graphics.Color.Black),
            ) { Text("GRANT ACCESS", letterSpacing = 2.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
