package com.friday.assistant.ui.boot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.core.CoreState
import com.friday.assistant.ui.components.FridayCore
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalEnergy
import kotlinx.coroutines.delay

private val lines = listOf(
    "SYSTEM INITIALIZING",
    "VOICE INTERFACE ONLINE",
    "AI CORE ONLINE",
    "COMMAND SYSTEM READY",
    "FRIDAY ONLINE",
)

/** Short, fast boot sequence (~2 s). */
@Composable
fun BootScreen(onFinished: () -> Unit, stepMs: Long = 340L) {
    var shown by remember { mutableIntStateOf(0) }
    val done by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        repeat(lines.size) { delay(stepMs); shown = it + 1 }
        delay(stepMs)
        done()
    }
    Column(
        Modifier.fillMaxSize().background(FridayColors.Amoled).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FridayCore(
            if (shown >= lines.size) CoreState.SPEAKING else CoreState.THINKING,
            level = { 0.25f }, modifier = Modifier.width(220.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text("FRIDAY", color = LocalEnergy.current.primary, fontSize = 30.sp, letterSpacing = 12.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(18.dp))
        lines.forEachIndexed { i, l ->
            AnimatedVisibility(visible = i < shown, enter = fadeIn()) {
                Text(
                    l, color = if (i == lines.lastIndex) FridayColors.Text else FridayColors.TextDim,
                    fontSize = 12.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
