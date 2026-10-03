package com.jarvis.assistant.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.holo.HoloContent
import com.jarvis.assistant.holo.HoloInteraction
import com.jarvis.assistant.holo.HoloWindow
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One holographic window. It materialises with a scan-line reveal and a flicker, floats with a
 * little parallax from the phone's tilt, and shows a loading bar until its data arrives.
 */
@Composable
fun HoloWindowView(
    window: HoloWindow,
    content: HoloContent,
    index: Int,
    deckBoot: Boolean,
    bootToken: Long,
    focused: Boolean,
    cW: Float,
    cH: Float,
    accent: Color,
    soft: Color,
    text: Color,
    dim: Color,
    tilt: State<Offset>,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { (window.w * cW).toDp() }
    val heightDp = with(density) { (window.h * cH).toDp() }
    val fs = (widthDp.value / 190f).coerceIn(0.62f, 1.5f)

    val progress = remember(window.panel, bootToken) { Animatable(0f) }
    LaunchedEffect(window.panel, bootToken) {
        if (deckBoot) delay(index * 190L)
        progress.animateTo(1f, tween(820, easing = FastOutSlowInEasing))
    }
    val depthPx = with(density) { (5 + (window.z % 5) * 3).dp.toPx() }

    Box(
        Modifier
            .offset { IntOffset((window.x * cW).roundToInt(), (window.y * cH).roundToInt()) }
            .size(widthDp, heightDp)
            .graphicsLayer {
                translationX = tilt.value.x * depthPx
                translationY = tilt.value.y * depthPx
                val p = progress.value
                val flicker = if (p < 1f) (0.55f + 0.45f * sin(p * 70f)) * p.coerceAtLeast(0.2f) else 1f
                alpha = flicker
                scaleX = 0.94f + 0.06f * p
                scaleY = 0.94f + 0.06f * p
            }
            .drawBehind { drawHoloFrame(accent, focused, progress.value, density.density) }
            .drawWithContent {
                val p = progress.value
                clipRect(bottom = size.height * p) { this@drawWithContent.drawContent() }
                if (p < 1f) {
                    val y = size.height * p
                    drawLine(accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.5f * density.density)
                    drawRect(
                        Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), startY = y, endY = y + 24 * density.density),
                        topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, 24 * density.density),
                    )
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = (9 * fs).dp, vertical = (6 * fs).dp)) {
            Header(window, content, fs, accent, soft, dim)
            Spacer(Modifier.height((3 * fs).dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Body(window, content, fs, heightDp.value, accent, soft, text, dim)
            }
            if (content.actions.isNotEmpty()) ActionRow(content, fs, accent, soft)
        }
    }
}

@Composable
private fun Header(window: HoloWindow, content: HoloContent, fs: Float, accent: Color, soft: Color, dim: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size((6 * fs).dp).graphicsLayer {}.drawBehind {
                val c = when (content.status) {
                    HoloContent.Status.READY -> accent
                    HoloContent.Status.LOADING -> Color(0xFFFFC857)
                    HoloContent.Status.ERROR -> Color(0xFFFF4D5E)
                    HoloContent.Status.EMPTY -> Color.Gray
                }
                drawCircle(c)
            },
        )
        Spacer(Modifier.size((6 * fs).dp))
        Box(Modifier.weight(1f)) {
            // faint chromatic offset behind the title for the "projected light" look
            Text(window.panel.title, color = Color(0xFFFF4D8D).copy(alpha = 0.35f), fontSize = (10 * fs).sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.offset(x = 0.8.dp))
            Text(window.panel.title, color = soft, fontSize = (10 * fs).sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, maxLines = 1)
        }
        Text(window.panel.ko, color = dim, fontSize = (9 * fs).sp, maxLines = 1)
        Spacer(Modifier.size((8 * fs).dp))
        Text("×", color = dim, fontSize = (14 * fs).sp)
    }
}

@Composable
private fun Body(window: HoloWindow, content: HoloContent, fs: Float, heightDp: Float, accent: Color, soft: Color, text: Color, dim: Color) {
    when {
        content.status == HoloContent.Status.LOADING -> LoadingView(window, fs, accent, dim)
        window.panel == com.jarvis.assistant.holo.HoloPanel.CLOCK -> ClockView(content, fs, soft, text, dim)
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy((2 * fs).dp)) {
                content.big?.let {
                    Text(it, color = soft, fontSize = (30 * fs).sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Clip)
                }
                content.sub?.let { Text(it, color = dim, fontSize = (10.5f * fs).sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                content.note?.let { Text(it, color = dim, fontSize = (10.5f * fs).sp, maxLines = 3) }
                val reserved = (if (content.big != null) 44f else 8f) * fs + (if (content.sub != null) 16f else 0f) * fs +
                    (if (content.actions.isNotEmpty()) 38f else 0f) * fs + 26f * fs
                val lineHeight = 15f * fs
                val maxLines = floor((heightDp - reserved) / lineHeight).toInt().coerceAtLeast(0)
                content.lines.take(maxLines).forEach {
                    Text(it, color = text, fontSize = (11 * fs).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LoadingView(window: HoloWindow, fs: Float, accent: Color, dim: Color) {
    val transition = rememberInfiniteTransition(label = "loading")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val dots = (sweep * 4).toInt()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("LOADING " + "·".repeat(dots + 1), color = dim, fontSize = (10 * fs).sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        Spacer(Modifier.height((6 * fs).dp))
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            drawLine(accent.copy(alpha = 0.2f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            val a = (sweep * 1.4f - 0.2f) * size.width
            drawLine(accent, Offset(a.coerceAtLeast(0f), size.height / 2), Offset((a + size.width * 0.3f).coerceAtMost(size.width), size.height / 2), strokeWidth = 4f)
        }
        Spacer(Modifier.height((4 * fs).dp))
        Text(window.panel.title.lowercase() + " link", color = dim.copy(alpha = 0.7f), fontSize = (9 * fs).sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ClockView(content: HoloContent, fs: Float, soft: Color, text: Color, dim: Color) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000L - now.nano / 1_000_000L)
        }
    }
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    Column(verticalArrangement = Arrangement.spacedBy((2 * fs).dp)) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), color = soft, fontSize = (26 * fs).sp, fontWeight = FontWeight.Light, maxLines = 1)
        content.lines.take(4).forEach { line ->
            val parts = line.split('|')
            val zone = parts[0]
            val name = parts.getOrElse(1) { parts[0] }
            val t = runCatching { now.withZoneSameInstant(ZoneId.of(zone)).format(fmt) }.getOrDefault("--:--")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = dim, fontSize = (10.5f * fs).sp, maxLines = 1)
                Text(t, color = text, fontSize = (10.5f * fs).sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ActionRow(content: HoloContent, fs: Float, accent: Color, soft: Color) {
    Row(
        Modifier.fillMaxWidth().height((30 * fs).dp),
        horizontalArrangement = Arrangement.spacedBy((6 * fs).dp),
    ) {
        content.actions.forEach { a ->
            Box(
                Modifier.weight(1f).fillMaxHeight().drawBehind {
                    drawRect(accent.copy(alpha = 0.12f))
                    drawRect(accent.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
                },
                contentAlignment = Alignment.Center,
            ) { Text(a.label, color = soft, fontSize = (13 * fs).sp) }
        }
    }
}

/** Cut-corner glass with glowing edges, scan lines and corner brackets. */
private fun DrawScope.drawHoloFrame(accent: Color, focused: Boolean, progress: Float, density: Float) {
    val w = size.width
    val h = size.height
    val c = 14f * density
    val path = Path().apply {
        moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
    }
    drawPath(path, Brush.verticalGradient(listOf(accent.copy(alpha = 0.17f), accent.copy(alpha = 0.05f))))
    // scan lines
    var y = 0f
    val step = 4f * density
    while (y < h) {
        drawLine(accent.copy(alpha = 0.045f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        y += step
    }
    // glow + border
    drawPath(path, accent.copy(alpha = if (focused) 0.35f else 0.14f), style = Stroke(width = 6f * density * (if (focused) 1.4f else 1f)))
    drawPath(path, accent.copy(alpha = if (focused) 1f else 0.7f), style = Stroke(width = (if (focused) 1.8f else 1.1f) * density))
    // header rule
    drawLine(accent.copy(alpha = 0.35f), Offset(0f, h * HoloInteraction.HEADER), Offset(w, h * HoloInteraction.HEADER), strokeWidth = 1f * density)
    // brackets
    val b = 9f * density
    val bc = accent.copy(alpha = 0.95f)
    drawLine(bc, Offset(c, 0f), Offset(c + b, 0f), strokeWidth = 2.2f * density)
    drawLine(bc, Offset(w, h - c - b), Offset(w, h - c), strokeWidth = 2.2f * density)
    drawLine(bc, Offset(0f, h), Offset(b, h), strokeWidth = 2.2f * density)
    drawLine(bc, Offset(w - b, 0f), Offset(w, 0f), strokeWidth = 2.2f * density)
    if (progress < 1f) drawRect(accent.copy(alpha = 0.06f * (1f - progress)))
}
