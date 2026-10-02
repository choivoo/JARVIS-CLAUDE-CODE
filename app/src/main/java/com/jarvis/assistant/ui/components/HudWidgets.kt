package com.jarvis.assistant.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.ui.theme.hudColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

data class BatteryState(val percent: Int, val charging: Boolean)

/** Live battery state from the sticky broadcast (no polling). */
@Composable
fun rememberBattery(): State<BatteryState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(readBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.value = parse(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun readBattery(context: Context): BatteryState =
    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::parse) ?: BatteryState(-1, false)

private fun parse(i: Intent): BatteryState {
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return BatteryState(
        percent = if (level < 0) -1 else level * 100 / scale,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** Clock, date, battery and link status in one monospace readout line. */
@Composable
fun TelemetryBar(battery: BatteryState, online: Boolean, modifier: Modifier = Modifier) {
    val colors = hudColors()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L - now.nano / 1_000_000L)
        }
    }
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val date = now.format(DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH)).uppercase()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = colors.accentSoft, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        Text(
            "  $date", color = colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        val bat = if (battery.percent < 0) "BAT --" else "BAT ${battery.percent}%" + if (battery.charging) " +" else ""
        Text(
            bat, color = if (battery.percent in 0..15 && !battery.charging) Color(0xFFFF4D5E) else colors.textDim,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
        Text(
            "  ${if (online) "LINK" else "NO LINK"}",
            color = if (online) colors.accent else Color(0xFFFFB627),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
    }
}

/** Audio-reactive spectrum strip. Reads the level inside the draw phase; throttled to ~30 fps when idle. */
@Composable
fun WaveStrip(phase: AssistantPhase, level: State<Float>, accent: Color, modifier: Modifier = Modifier) {
    val phaseState = rememberUpdatedState(phase)
    var time by remember { mutableFloatStateOf(0f) }
    val smooth = remember { floatArrayOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val gap = now - last
                val minGap = if (phaseState.value == AssistantPhase.IDLE) 40_000_000L else 0L
                if (gap >= minGap) {
                    time += gap / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val p = phaseState.value
        val t = time
        val live = p == AssistantPhase.LISTENING || p == AssistantPhase.SPEAKING
        smooth[0] += ((if (live) level.value else 0f) - smooth[0]) * 0.4f
        val amp = when (p) {
            AssistantPhase.THINKING, AssistantPhase.EXECUTING -> 0.18f + 0.1f * sin(t * 6f)
            AssistantPhase.IDLE -> 0.05f + 0.02f * sin(t * 1.4f)
            else -> smooth[0].coerceAtLeast(0.08f)
        }
        val bars = 64
        val gap = size.width / bars
        val mid = size.height / 2f
        for (i in 0 until bars) {
            val x = gap * (i + 0.5f)
            val edge = 1f - abs(i - bars / 2f) / (bars / 2f) * 0.55f           // taller in the middle
            val shape = abs(sin(i * 0.45f + t * 6.5f) * 0.6f + sin(i * 0.21f - t * 3.7f) * 0.4f)
            val h = (2f + amp * size.height * 0.95f * shape * edge).coerceAtMost(size.height / 2f)
            drawLine(
                accent.copy(alpha = 0.35f + 0.6f * shape * edge),
                Offset(x, mid - h), Offset(x, mid + h), strokeWidth = gap * 0.45f, cap = StrokeCap.Round,
            )
        }
    }
}

/** Reveals [text] character by character, like a terminal; the full text reserves the layout space. */
@Composable
fun TypewriterText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    charsPerSecond: Int = 45,
) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        shown = 0
        while (shown < text.length) {
            delay(1_000L / charsPerSecond)
            shown++
        }
    }
    Box(modifier) {
        Text(text, color = Color.Transparent, fontSize = fontSize, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(text.take(shown), color = color, fontSize = fontSize, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
