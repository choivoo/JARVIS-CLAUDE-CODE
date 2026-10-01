package com.jarvis.assistant.ui.boot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.ui.components.JarvisCore
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State

private val BOOT_LINES = listOf(
    "INITIALIZING SYSTEM...",
    "VOICE SYSTEM ONLINE",
    "AI CORE ONLINE",
    "JARVIS READY",
)

/** Short boot sequence: black -> core fades in -> status lines -> HUD. */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val level: State<Float> = remember { mutableFloatStateOf(0f) }
    var lines by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(700)
        for (i in 1..BOOT_LINES.size) {
            lines = i
            delay(520)
        }
        delay(350)
        onFinished()
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            JarvisCore(
                phase = if (lines >= BOOT_LINES.size) AssistantPhase.IDLE else AssistantPhase.THINKING,
                level = level,
                modifier = Modifier
                    .size(260.dp)
                    .alpha(reveal.value)
                    .scale(0.7f + 0.3f * reveal.value),
            )
            Text(
                text = "J.A.R.V.I.S",
                color = Color(0xFFE6FAFF),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 10.sp,
                modifier = Modifier.padding(top = 18.dp).alpha(reveal.value),
            )
            Column(
                modifier = Modifier.padding(top = 22.dp).height(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BOOT_LINES.take(lines).forEachIndexed { index, line ->
                    Text(
                        text = line,
                        color = if (index == lines - 1) Color(0xFF7DF3FF) else Color(0xFF3A7284),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
