package com.friday.assistant.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.assistant.core.CoreState
import com.friday.assistant.ui.HomeViewModel
import com.friday.assistant.ui.components.FridayCore
import com.friday.assistant.command.InfoCard
import com.friday.assistant.ui.components.GlassPanel
import com.friday.assistant.ui.components.InfoCardPanel
import com.friday.assistant.ui.components.HudLabel
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalEnergy

@Composable
fun HomeScreen(vm: HomeViewModel, onNeedMic: (then: () -> Unit) -> Unit, onAmbient: () -> Unit) {
    val core by vm.core.collectAsStateWithLifecycle()
    val sub by vm.subtitle.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val service by vm.serviceRunning.collectAsStateWithLifecycle()
    val mic by vm.micLevel.collectAsStateWithLifecycle()
    val amp by vm.amplitude.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val korean by vm.koreanVoice.collectAsStateWithLifecycle()
    val ctx by vm.contextSize.collectAsStateWithLifecycle()
    HomeContent(
        core = core, user = if (core == CoreState.LISTENING && partial.isNotBlank()) partial else sub.user,
        subtitle = sub.subtitle, background = service, wakeWord = settings.wakeWord,
        level = { if (core == CoreState.LISTENING) mic else amp },
        onMic = { onNeedMic { vm.listen() } }, onStop = vm::stop,
        koreanVoice = korean, card = card, subtitleScale = settings.subtitleScale, contextLevel = ctx / 4f, onAmbient = onAmbient,
    )
}

@Composable
fun HomeContent(
    core: CoreState, user: String, subtitle: String, background: Boolean, wakeWord: String,
    level: () -> Float, onMic: () -> Unit, onStop: () -> Unit,
    koreanVoice: Boolean = false, card: InfoCard? = null, subtitleScale: Float = 1f, contextLevel: Float = 0f, onAmbient: () -> Unit = {},
) {
    val e = LocalEnergy.current
    val active = core != CoreState.IDLE && core != CoreState.OFFLINE
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("FRIDAY", color = e.primary, fontSize = 18.sp, letterSpacing = 8.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudLabel(if (background) "● BACKGROUND ON" else "○ BACKGROUND OFF", color = if (background) FridayColors.Ok else FridayColors.TextDim)
                IconButton(onClick = onAmbient) {
                    Icon(Icons.Filled.Bedtime, contentDescription = "Ambient mode", tint = FridayColors.TextDim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            FridayCore(core, level, Modifier.fillMaxWidth(0.82f), contextLevel)
        }
        HudLabel(stateLabel(core, wakeWord) + if (koreanVoice) " · 한국어 음성" else "", color = if (core == CoreState.ERROR) FridayColors.Error else e.secondary)
        InfoCardPanel(card, Modifier.padding(bottom = 8.dp))
        Spacer(Modifier.height(4.dp))
        GlassPanel(Modifier.fillMaxWidth().height(132.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (user.isNotBlank()) Text("“$user”", color = FridayColors.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, color = FridayColors.Text, fontSize = (20 * subtitleScale).sp, textAlign = TextAlign.Center, maxLines = 4, modifier = Modifier.semantics { contentDescription = "subtitle" })
                } else if (user.isBlank()) {
                    Text("“$wakeWord”라고 부르거나 마이크를 누르세요", color = FridayColors.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        IconButton(
            onClick = if (active) onStop else onMic,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(e.primary.copy(alpha = 0.22f)),
        ) {
            Icon(
                if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (active) "Stop" else "Talk to FRIDAY",
                tint = e.primary, modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun stateLabel(s: CoreState, wake: String) = when (s) {
    CoreState.IDLE -> "SYSTEM READY · SAY “${wake.uppercase()}”"
    CoreState.LISTENING -> "LISTENING"
    CoreState.THINKING -> "THINKING"
    CoreState.SPEAKING -> "SPEAKING"
    CoreState.EXECUTING -> "EXECUTING"
    CoreState.OFFLINE -> "OFFLINE · LOCAL COMMANDS ONLY"
    CoreState.ERROR -> "ERROR"
}
