package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.ui.theme.accent
import com.jarvis.assistant.ui.theme.hudColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The JARVIS energy core. Everything is drawn in a single Canvas whose only inputs are read in the
 * draw phase (frame clock + audio level), so animating it never triggers recomposition.
 * Idle state is throttled to ~30 fps to save battery; active states run at the display rate.
 */
@Composable
fun JarvisCore(
    phase: AssistantPhase,
    level: State<Float>,
    modifier: Modifier = Modifier,
    /** 0..1 battery fraction drawn as the outer ring; negative hides it. */
    battery: Float = -1f,
) {
    val colors = hudColors()
    val accent by animateColorAsState(phase.accent(colors), tween(450), label = "coreAccent")
    val phaseState = rememberUpdatedState(phase)
    var time by remember { mutableFloatStateOf(0f) }
    val smoothed = remember { floatArrayOf(0f) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 13f)) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val gap = now - last
                val minGap = if (phaseState.value == AssistantPhase.IDLE) 33_000_000L else 0L
                if (gap >= minGap) {
                    time += gap / 1_000_000_000f
                    last = now
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        val t = time
        val p = phaseState.value
        val energetic = p == AssistantPhase.LISTENING || p == AssistantPhase.SPEAKING
        val target = if (energetic) level.value else 0f
        smoothed[0] += (target - smoothed[0]) * 0.35f
        var amp = smoothed[0]
        if (p == AssistantPhase.IDLE) amp = 0.05f + 0.035f * sin(t * 1.6f)
        if (p == AssistantPhase.THINKING || p == AssistantPhase.EXECUTING) amp = 0.10f + 0.06f * sin(t * 5f)
        if (energetic) amp = amp.coerceAtLeast(0.10f)

        val speed = when (p) {
            AssistantPhase.IDLE -> 1f
            AssistantPhase.LISTENING -> 2.2f
            AssistantPhase.THINKING -> 7f
            AssistantPhase.SPEAKING -> 2.8f
            AssistantPhase.EXECUTING -> 4.5f
            AssistantPhase.ERROR -> 0.2f
        }
        val blink = if (p == AssistantPhase.ERROR) 0.6f + 0.4f * sin(t * 6f) else 1f
        drawCore(t, amp, speed, blink, accent, colors.accentSoft.takeIf { !colors.isLight } ?: accent, dash, battery, p)
    }
}

private fun DrawScope.drawCore(
    t: Float,
    amp: Float,
    speed: Float,
    blink: Float,
    accent: Color,
    soft: Color,
    dash: PathEffect,
    battery: Float,
    phase: AssistantPhase,
) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = min(size.width, size.height) / 2f
    val dp = density

    // 1. Glow
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = (0.26f + amp * 0.40f) * blink), Color.Transparent),
            center = c,
            radius = r,
        ),
        radius = r,
        center = c,
    )

    // 1b. Radar sweep (slow when idle, fast while thinking / executing)
    val sweepSpeed = when (phase) {
        AssistantPhase.THINKING, AssistantPhase.EXECUTING -> 150f
        AssistantPhase.IDLE -> 28f
        else -> 55f
    }
    rotate(degrees = t * sweepSpeed, pivot = c) {
        drawCircle(
            brush = Brush.sweepGradient(
                0f to Color.Transparent, 0.82f to Color.Transparent, 1f to accent.copy(alpha = 0.30f * blink), center = c,
            ),
            radius = r * 0.80f, center = c,
        )
    }

    // 1c. Battery ring: dim track plus a bright arc for the charge level
    if (battery >= 0f) {
        val bR = r * 0.995f
        drawArc(
            color = accent.copy(alpha = 0.12f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(c.x - bR, c.y - bR), size = Size(bR * 2, bR * 2), style = Stroke(width = 3f * dp),
        )
        drawArc(
            color = (if (battery < 0.16f) Color(0xFFFF4D5E) else accent).copy(alpha = 0.9f),
            startAngle = -90f, sweepAngle = 360f * battery.coerceIn(0f, 1f), useCenter = false,
            topLeft = Offset(c.x - bR, c.y - bR), size = Size(bR * 2, bR * 2),
            style = Stroke(width = 3f * dp, cap = StrokeCap.Round),
        )
    }

    // 2. Outer hairline ring
    drawCircle(accent.copy(alpha = 0.40f * blink), radius = r * 0.97f, center = c, style = Stroke(1f * dp))

    // 3. Tick ring (rotating)
    val tickBase = t * 5f * speed
    for (i in 0 until 60) {
        val a = Math.toRadians((tickBase + i * 6f).toDouble())
        val long = i % 5 == 0
        val r1 = r * (if (long) 0.885f else 0.905f)
        val r2 = r * 0.935f
        drawLine(
            color = accent.copy(alpha = (if (long) 0.85f else 0.45f) * blink),
            start = Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
            end = Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2),
            strokeWidth = (if (long) 1.8f else 1f) * dp,
        )
    }

    // 4. Segmented arcs (counter-rotating)
    val arcR = r * 0.80f
    val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
    val arcSize = Size(arcR * 2, arcR * 2)
    val sweepBase = 70f + 25f * sin(t * 0.9f * speed)
    for (k in 0 until 3) {
        drawArc(
            color = accent.copy(alpha = 0.85f * blink),
            startAngle = -t * 22f * speed + k * 120f,
            sweepAngle = sweepBase,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 3f * dp, cap = StrokeCap.Round),
        )
    }
    val arc2R = r * 0.70f
    for (k in 0 until 2) {
        drawArc(
            color = soft.copy(alpha = 0.55f * blink),
            startAngle = t * 34f * speed + k * 180f,
            sweepAngle = 40f,
            useCenter = false,
            topLeft = Offset(c.x - arc2R, c.y - arc2R),
            size = Size(arc2R * 2, arc2R * 2),
            style = Stroke(width = 2f * dp, cap = StrokeCap.Round),
        )
    }

    // 5. Dashed inner ring
    rotate(degrees = -t * 12f * speed, pivot = c) {
        drawCircle(
            soft.copy(alpha = 0.35f * blink),
            radius = r * 0.62f,
            center = c,
            style = Stroke(width = 1.2f * dp, pathEffect = dash),
        )
    }

    // 6. Waveform bars around the core
    val bars = 72
    val inner = r * 0.46f
    val maxLen = r * 0.15f
    for (i in 0 until bars) {
        val a = (i.toFloat() / bars) * (2f * PI.toFloat()) - PI.toFloat() / 2f
        val shape = abs(sin(i * 0.55f + t * 7f) * cos(i * 0.23f - t * 4.1f))
        val len = 2f * dp + amp * maxLen * (0.35f + 0.65f * shape)
        drawLine(
            color = soft.copy(alpha = (0.55f + 0.45f * shape) * blink),
            start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
            end = Offset(c.x + cos(a) * (inner + len), c.y + sin(a) * (inner + len)),
            strokeWidth = 2.2f * dp,
            cap = StrokeCap.Round,
        )
    }

    // 7. Core + rotating hexagon
    val pulse = 1f + 0.03f * sin(t * 2f) + amp * 0.12f
    val coreR = r * 0.34f * pulse
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.95f), accent.copy(alpha = 0.85f), accent.copy(alpha = 0.0f)),
            center = c,
            radius = coreR,
        ),
        radius = coreR,
        center = c,
    )
    val hexR = r * 0.27f
    val hexRot = Math.toRadians((t * 18f * speed).toDouble())
    for (i in 0 until 6) {
        val a1 = hexRot + i * PI / 3
        val a2 = hexRot + (i + 1) * PI / 3
        drawLine(
            color = Color.White.copy(alpha = 0.75f * blink),
            start = Offset(c.x + cos(a1).toFloat() * hexR, c.y + sin(a1).toFloat() * hexR),
            end = Offset(c.x + cos(a2).toFloat() * hexR, c.y + sin(a2).toFloat() * hexR),
            strokeWidth = 1.4f * dp,
            cap = StrokeCap.Round,
        )
    }

    // 7b. Orbiting nodes on the tick ring
    for (k in 0 until 3) {
        val a = Math.toRadians((t * 38f * speed + k * 120f).toDouble())
        val nr = r * 0.865f
        val p = Offset(c.x + cos(a).toFloat() * nr, c.y + sin(a).toFloat() * nr)
        drawCircle(accent.copy(alpha = 0.25f * blink), radius = 7f * dp, center = p)
        drawCircle(Color.White.copy(alpha = 0.9f * blink), radius = 2.4f * dp, center = p)
    }

    // 8. Particles
    for (i in 0 until 18) {
        val a = i * 2.3999631f + t * (0.15f + (i % 5) * 0.04f)
        val frac = (i * 0.6180339f + t * 0.04f * (1 + i % 3)) % 1f
        val rr = r * (0.36f + 0.58f * frac)
        val alpha = sin(PI.toFloat() * frac)
        drawCircle(
            color = soft.copy(alpha = alpha * 0.7f * blink),
            radius = (1.1f + (i % 3) * 0.7f) * dp,
            center = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr),
        )
    }
}
