package com.jarvis.assistant.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.core.HudState
import com.jarvis.assistant.data.database.MessageEntity
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.JarvisCore
import com.jarvis.assistant.ui.components.MessageCard
import com.jarvis.assistant.ui.components.MicPermissionPanel
import com.jarvis.assistant.ui.components.TelemetryBar
import com.jarvis.assistant.ui.components.TypewriterText
import com.jarvis.assistant.ui.components.WaveStrip
import com.jarvis.assistant.ui.components.rememberBattery
import com.jarvis.assistant.ui.theme.accent
import com.jarvis.assistant.ui.theme.hudColors

@Composable
fun HomeScreen(
    hud: HudState,
    level: State<Float>,
    messages: List<MessageEntity>,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onMic: () -> Unit,
    onToggleStandby: () -> Unit,
    onSubmitText: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onOpenHolo: () -> Unit = {},
    cameraGestureActive: Boolean = false,
    /** True over the lock screen: private content is hidden. */
    locked: Boolean = false,
) {
    val colors = hudColors()
    val accent by animateColorAsState(hud.phase.accent(colors), tween(450), label = "homeAccent")
    var showInput by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        HudBackground(accent = accent)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            TopBar(hud, accent, cameraGestureActive, locked, onOpenSettings, onOpenHistory, onOpenNotes, onOpenHolo)
            val battery by rememberBattery()
            TelemetryBar(battery, hud.online, Modifier.padding(top = 2.dp))

            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                JarvisCore(
                    phase = hud.phase,
                    level = level,
                    modifier = Modifier.size(side * 0.98f),
                    battery = if (battery.percent >= 0) battery.percent / 100f else -1f,
                )
                if (!micGranted) {
                    MicPermissionPanel(onGrant = onRequestMic, modifier = Modifier.align(Alignment.Center))
                }
            }

            StatusBlock(hud, accent)
            WaveStrip(hud.phase, level, accent, Modifier.padding(vertical = 2.dp))
            SubtitleArea(hud, accent)

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!locked) messages.take(2).reversed().forEach { MessageCard(it) }
            }

            if (!locked) QuickChips(accent, onSubmitText)

            AnimatedVisibility(
                visible = showInput,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                TextInputRow(accent) {
                    onSubmitText(it)
                    showInput = false
                }
            }

            Controls(
                hud = hud,
                accent = accent,
                inputOpen = showInput,
                onKeyboard = { showInput = !showInput },
                onMic = onMic,
                onToggleStandby = onToggleStandby,
            )
        }
    }
}

private val QUICK_ACTIONS = listOf(
    "TIME" to "지금 시간 알려줘",
    "WEATHER" to "오늘 날씨 알려줘",
    "BATTERY" to "내 배터리 얼마나 남았어?",
    "TORCH" to "손전등 켜줘",
    "MUSIC" to "음악 재생해줘",
    "NOTES" to "메모 읽어줘",
    "WI-FI" to "와이파이 설정 열어줘",
)

/** One-tap shortcuts for the most common commands (same pipeline as speech). */
@Composable
private fun QuickChips(accent: Color, onRun: (String) -> Unit) {
    val colors = hudColors()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_ACTIONS.forEach { (label, command) ->
            Text(
                label,
                color = colors.accentSoft,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
                    .clickable { onRun(command) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TopBar(
    hud: HudState,
    accent: Color,
    cameraGestureActive: Boolean,
    locked: Boolean,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenHolo: () -> Unit,
) {
    val colors = hudColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "J.A.R.V.I.S",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
            )
            Text(
                "AI CORE v1.3" + (if (hud.standby) " · STANDBY" else "") + (if (cameraGestureActive) " · GESTURE CAM" else ""),
                color = if (hud.standby) accent else colors.textDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
        }
        if (locked) {
            Text("LOCKED", color = colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        } else {
            IconButton(onClick = onOpenHolo) {
                Icon(Icons.Filled.ViewInAr, contentDescription = "Hologram", tint = accent)
            }
            IconButton(onClick = onOpenNotes) {
                Icon(Icons.Filled.EditNote, contentDescription = "Notes", tint = colors.textDim)
            }
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "Memory log", tint = colors.textDim)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textDim)
            }
        }
    }
}

@Composable
private fun StatusBlock(hud: HudState, accent: Color) {
    val colors = hudColors()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            hud.statusLabel,
            color = accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
        val line = when {
            hud.partial.isNotBlank() -> "YOU · ${hud.partial}"
            hud.notice != null -> hud.notice
            hud.phase == AssistantPhase.IDLE && hud.standby -> "\"JARVIS\" 라고 불러 보세요"
            hud.phase == AssistantPhase.IDLE -> "마이크를 누르거나 대기 모드를 켜세요"
            else -> ""
        }
        Text(
            line,
            color = if (hud.phase == AssistantPhase.ERROR) accent else colors.textDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(top = 4.dp).heightIn(min = 34.dp),
        )
    }
}

@Composable
private fun SubtitleArea(hud: HudState, accent: Color) {
    val colors = hudColors()
    Box(Modifier.fillMaxWidth().heightIn(min = 74.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = hud.subtitle != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(700))) {
            GlassPanel(Modifier.fillMaxWidth(), accent = accent) {
                TypewriterText(
                    text = hud.subtitle.orEmpty(),
                    color = colors.text,
                    fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TextInputRow(accent: Color, onSend: (String) -> Unit) {
    val colors = hudColors()
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("명령을 입력하세요", color = colors.textDim) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.4f),
                cursorColor = accent,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
            ),
        )
        IconButton(onClick = { if (text.isNotBlank()) onSend(text) }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = accent)
        }
    }
}

@Composable
private fun Controls(
    hud: HudState,
    accent: Color,
    inputOpen: Boolean,
    onKeyboard: () -> Unit,
    onMic: () -> Unit,
    onToggleStandby: () -> Unit,
) {
    val busy = hud.phase != AssistantPhase.IDLE && hud.phase != AssistantPhase.ERROR
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabeledButton("TYPE", Icons.Filled.Keyboard, "Type a command", 52.dp, inputOpen, accent, onKeyboard)
        LabeledButton(
            if (busy) "STOP" else "SPEAK",
            if (busy) Icons.Filled.Close else Icons.Filled.Mic,
            if (busy) "Stop" else "Start listening",
            78.dp,
            busy,
            accent,
            onMic,
        )
        LabeledButton(
            if (hud.standby) "STANDBY ON" else "STANDBY",
            Icons.Filled.PowerSettingsNew,
            if (hud.standby) "Stop background standby" else "Start background standby",
            52.dp,
            hud.standby,
            accent,
            onToggleStandby,
        )
    }
}

@Composable
private fun LabeledButton(
    label: String,
    icon: ImageVector,
    description: String,
    size: Dp,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = hudColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (active) 0.24f else 0.08f))
                .border(1.5.dp, accent.copy(alpha = if (active) 1f else 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) accent else colors.text, modifier = Modifier.size(size * 0.44f))
        }
        Text(
            label,
            color = if (active) accent else colors.textDim,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
