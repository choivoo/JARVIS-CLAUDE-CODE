package com.friday.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.friday.assistant.core.CoreState
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalEnergy
import com.friday.assistant.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The circular AI core. Animation phases are read only inside the draw lambda, so frames redraw
 * without recomposing; with reduce-motion (tests, accessibility) a single static frame is drawn.
 * [level] is a 0..1 loudness provider (microphone while listening, TTS while speaking).
 */
@Composable
fun FridayCore(state: CoreState, level: () -> Float, modifier: Modifier = Modifier, contextLevel: Float = 0f) {
    val e = LocalEnergy.current
    val still = LocalReduceMotion.current
    val t = if (still) remember { mutableFloatStateOf(0.25f) } else rememberInfiniteTransition(label = "core").let { tr ->
        val spin = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(durationFor(state), easing = LinearEasing), RepeatMode.Restart), label = "spin")
        spin
    }
    val pulse = if (still) remember { mutableFloatStateOf(0.5f) } else rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (state == CoreState.IDLE) 3200 else 1100), RepeatMode.Reverse), label = "p",
    )
    Box(modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "FRIDAY core ${state.name}" }) {
        Canvas(Modifier.matchParentSize()) { drawCore(state, t, pulse, level(), e.primary, e.secondary, e.tertiary, contextLevel) }
    }
}

private fun durationFor(s: CoreState) = when (s) {
    CoreState.THINKING -> 2200
    CoreState.EXECUTING -> 1200
    CoreState.ERROR -> 700
    else -> 9000
}

private fun DrawScope.drawCore(
    state: CoreState, t: State<Float>, pulse: State<Float>, level: Float,
    primary: Color, secondary: Color, tertiary: Color, contextLevel: Float,
) {
    val c = center
    val r = size.minDimension / 2f
    val ph = t.value
    val p = pulse.value
    val dim = state == CoreState.OFFLINE
    val tint = when (state) { CoreState.ERROR -> FridayColors.Error; CoreState.OFFLINE -> FridayColors.TextDim; else -> primary }
    val fade = if (dim) 0.45f else 1f

    // glow
    val glow = when (state) {
        CoreState.IDLE, CoreState.OFFLINE -> 0.10f + 0.06f * p
        CoreState.LISTENING, CoreState.SPEAKING -> 0.16f + 0.35f * level
        else -> 0.2f + 0.1f * p
    }
    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = glow * fade), Color.Transparent), c, r), r, c)

    // context ring: fills as FRIDAY holds more short-term context for follow-ups
    if (contextLevel > 0f) arc(c, r * 0.995f, -90f, 360f * contextLevel.coerceIn(0f, 1f), tertiary.copy(alpha = 0.75f * fade), 3f)

    // outer thin ring with ticks
    drawCircle(tint.copy(alpha = 0.35f * fade), r * 0.96f, c, style = Stroke(1.2f))
    for (i in 0 until 72) {
        val a = (i / 72f + ph * 0.05f) * 2 * PI.toFloat()
        val long = i % 6 == 0
        val r1 = r * (if (long) 0.90f else 0.93f)
        drawLine(secondary.copy(alpha = (if (long) 0.6f else 0.25f) * fade), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r * 0.96f, c.y + sin(a) * r * 0.96f), 1.2f)
    }

    // rotating arcs
    val spinning = state == CoreState.THINKING || state == CoreState.EXECUTING || state == CoreState.ERROR
    val sweepBase = ph * 360f * (if (spinning) 1f else 0.4f)
    arc(c, r * 0.80f, sweepBase, if (spinning) 110f else 60f, tertiary.copy(alpha = fade), 4f)
    arc(c, r * 0.80f, sweepBase + 180f, if (spinning) 110f else 60f, tertiary.copy(alpha = fade), 4f)
    arc(c, r * 0.68f, -sweepBase * 1.6f, 80f, secondary.copy(alpha = fade), 3f)
    arc(c, r * 0.68f, -sweepBase * 1.6f + 180f, 80f, secondary.copy(alpha = fade), 3f)
    drawCircle(tint.copy(alpha = 0.5f * fade), r * 0.58f, c, style = Stroke(1.5f))

    // waveform ring (listening / speaking) or breathing ring
    val bars = 64
    val wave = state == CoreState.LISTENING || state == CoreState.SPEAKING
    for (i in 0 until bars) {
        val a = i / bars.toFloat() * 2 * PI.toFloat()
        val shape = 0.5f + 0.5f * sin(a * 5f + ph * 2 * PI.toFloat() * 3f)
        val len = if (wave) r * (0.03f + 0.17f * level.coerceAtLeast(0.08f) * (0.35f + shape)) else r * (0.02f + 0.02f * p)
        val r0 = r * 0.50f
        drawLine(
            tint.copy(alpha = (if (wave) 0.9f else 0.45f) * fade),
            Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
            Offset(c.x + cos(a) * (r0 + len), c.y + sin(a) * (r0 + len)),
            3f, StrokeCap.Round,
        )
    }

    // nucleus
    val core = r * (0.22f + 0.04f * p + if (wave) 0.08f * level else 0f)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * fade), tint.copy(alpha = 0.75f * fade), Color.Transparent), c, core * 1.6f), core * 1.6f, c)
}

private fun DrawScope.arc(c: Offset, radius: Float, start: Float, sweep: Float, color: Color, width: Float) {
    drawArc(color, start, sweep, false, Offset(c.x - radius, c.y - radius), Size(radius * 2, radius * 2), style = Stroke(width, cap = StrokeCap.Round))
}
