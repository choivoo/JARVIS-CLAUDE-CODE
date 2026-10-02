package com.jarvis.assistant.ui.boot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.audio.UiSounds
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.JarvisCore
import com.jarvis.assistant.ui.theme.hudColors
import kotlinx.coroutines.delay

private val BOOT_STEPS = listOf(
    "INITIALIZING SYSTEM..." to "KERNEL",
    "VOICE SYSTEM ONLINE" to "VOICE",
    "NEURAL LINK ESTABLISHED" to "LINK",
    "SENSOR ARRAY CALIBRATED" to "SENSORS",
    "AI CORE ONLINE" to "AI CORE",
    "JARVIS READY" to "READY",
)

/** Boot sequence: core powers up, diagnostics tick off with a progress bar and a synthesized chime. */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    val colors = hudColors()
    val context = LocalContext.current
    val reveal = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val level: State<Float> = remember { mutableFloatStateOf(0f) }
    var lines by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        (context.applicationContext as? JarvisApp)?.container?.uiSounds?.play(UiSounds.Kind.BOOT)
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(BOOT_STEPS.size * 430, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        delay(600)
        for (i in 1..BOOT_STEPS.size) {
            lines = i
            delay(430)
        }
        delay(450)
        onFinished()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HudBackground(accent = colors.accent)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            JarvisCore(
                phase = if (lines >= BOOT_STEPS.size) AssistantPhase.IDLE else AssistantPhase.THINKING,
                level = level,
                modifier = Modifier.size(250.dp).alpha(reveal.value).scale(0.7f + 0.3f * reveal.value),
                battery = progress.value,
            )
            Text(
                "J.A.R.V.I.S", color = Color(0xFFE6FAFF), fontSize = 30.sp, fontWeight = FontWeight.Light,
                letterSpacing = 10.sp, modifier = Modifier.padding(top = 16.dp).alpha(reveal.value),
            )
            Text(
                "JUST A RATHER VERY INTELLIGENT SYSTEM", color = colors.textDim, fontSize = 8.sp,
                letterSpacing = 3.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.alpha(reveal.value),
            )
            Column(Modifier.padding(top = 18.dp).fillMaxWidth().height(116.dp)) {
                BOOT_STEPS.take(lines).forEachIndexed { index, (line, _) ->
                    val last = index == lines - 1
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            line, color = if (last) colors.accentSoft else colors.textDim, fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, letterSpacing = 2.sp,
                        )
                        Text(
                            if (last && index < BOOT_STEPS.size - 1) "[ .. ]" else "[ OK ]",
                            color = if (last) colors.accent else colors.textDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        )
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
                drawLine(colors.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
                drawLine(colors.accent, Offset(0f, size.height / 2), Offset(size.width * progress.value, size.height / 2), strokeWidth = 4f)
            }
        }
    }
}
