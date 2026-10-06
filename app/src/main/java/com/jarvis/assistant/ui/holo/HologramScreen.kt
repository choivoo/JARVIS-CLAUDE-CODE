package com.jarvis.assistant.ui.holo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.HudState
import com.jarvis.assistant.gesture.GestureManager
import com.jarvis.assistant.holo.FRect
import com.jarvis.assistant.holo.HoloDataHub
import com.jarvis.assistant.holo.HoloInteraction
import com.jarvis.assistant.holo.HoloPanel
import com.jarvis.assistant.holo.HologramController
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.JarvisCore
import com.jarvis.assistant.ui.components.TypewriterText
import com.jarvis.assistant.ui.theme.accent
import com.jarvis.assistant.ui.theme.hudColors
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

private val TOOLBAR = listOf("OPEN", "ARRANGE", "ASK", "CLEAR", "EXIT")

/**
 * The holographic workspace: up to 11 floating windows that boot up one after another. Windows can be
 * touched, or grabbed and moved with an air gesture; JARVIS knows which window you point at.
 */
@Composable
fun HologramScreen(
    holo: HologramController,
    hub: HoloDataHub,
    hud: HudState,
    level: State<Float>,
    pointerFlow: StateFlow<GestureManager.Pointer>,
    pointerEvents: SharedFlow<GestureManager.PointerEvent>,
    trackingEngine: StateFlow<String>,
    handsEnabled: Boolean,
    handsPermitted: Boolean,
    parallax: Boolean,
    onAsk: () -> Unit,
    onExit: () -> Unit,
    onEnableHands: () -> Unit,
) {
    val colors = hudColors()
    val state by holo.state.collectAsState()
    val content by hub.content.collectAsState()
    val tracking by trackingEngine.collectAsState()
    val accent by animateColorAsState(hud.phase.accent(colors), tween(450), label = "holoAccent")
    val tilt = rememberTilt(parallax)
    var picker by remember { mutableStateOf(false) }

    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) clock.floatValue += (now - last) / 1e9f
                last = now
            }
        }
    }

    // Load whatever is open, and refresh every few minutes while the projector is on.
    LaunchedEffect(state.openPanels) { hub.ensureLoaded(state.openPanels) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60_000L)
            hub.refreshAll(holo.state.value.openPanels)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cW = constraints.maxWidth.toFloat()
        val cH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current.density
        val toolbarTop = 1f - (84f * density) / cH

        val interaction = remember { HoloInteraction({ holo.state.value.windows }, holo) }
        interaction.actionsFor = { p -> hub.contentOf(p).actions }

        fun pickerRect(i: Int): FRect {
            val cols = 6
            val col = i % cols
            val row = i / cols
            val w = (0.94f - 0.012f * (cols - 1)) / cols
            val x = 0.03f + col * (w + 0.012f)
            val top = toolbarTop - 0.135f
            val y = top + row * 0.062f
            return FRect(x, y, x + w, y + 0.054f)
        }

        fun pickerHit(x: Float, y: Float): Int? =
            if (!picker) null else HoloPanel.values().indices.firstOrNull { pickerRect(it).contains(x, y) }

        fun toolbarAction(i: Int) {
            when (i) {
                0 -> picker = !picker
                1 -> holo.arrange()
                2 -> onAsk()
                3 -> holo.closeAll()
                else -> onExit()
            }
        }

        fun down(x: Float, y: Float) {
            if (y >= toolbarTop || pickerHit(x, y) != null) return
            interaction.onDown(x, y)
        }

        fun move(x: Float, y: Float) {
            if (y >= toolbarTop) return
            interaction.onMove(x, y)
        }

        fun up(x: Float, y: Float) {
            if (y >= toolbarTop) {
                toolbarAction((x * TOOLBAR.size).toInt().coerceIn(0, TOOLBAR.size - 1))
                return
            }
            val idx = pickerHit(x, y)
            if (idx != null) {
                val panel = HoloPanel.values()[idx]
                if (state.openPanels.contains(panel)) holo.close(panel) else holo.open(panel)
                holo.persistChoice()
                return
            }
            interaction.onUp(x, y)
        }

        // Air cursor events (pinch / dwell) and hover tracking.
        LaunchedEffect(Unit) {
            pointerEvents.collect { e ->
                when (e) {
                    is GestureManager.PointerEvent.Down -> down(e.x, e.y)
                    is GestureManager.PointerEvent.Up -> up(e.x, e.y)
                    is GestureManager.PointerEvent.Click -> Unit
                }
            }
        }
        LaunchedEffect(Unit) {
            pointerFlow.collect { p -> if (p.visible) move(p.x, p.y) }
        }

        Box(Modifier.fillMaxSize()) {
            HudBackground(accent = accent)
            HoloFloor(accent)

            // ---- touch: drag windows, tap to click (same code path as the air cursor)
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        down(first.position.x / cW, first.position.y / cH)
                        var pressed = true
                        var last = first.position
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            last = change.position
                            if (change.pressed) {
                                move(last.x / cW, last.y / cH)
                                change.consume()
                            } else {
                                pressed = false
                            }
                        }
                        up(last.x / cW, last.y / cH)
                    }
                },
            )

            // ---- windows
            val sinceBoot = (System.nanoTime() - state.bootToken) / 1_000_000L
            val deckBoot = sinceBoot in 0..2_000
            state.windows.forEachIndexed { index, w ->
                androidx.compose.runtime.key(w.panel) {
                    HoloWindowView(
                        window = w,
                        content = content[w.panel] ?: com.jarvis.assistant.holo.HoloContent(),
                        index = index,
                        deckBoot = deckBoot,
                        bootToken = state.bootToken,
                        focused = state.target == w.panel,
                        cW = cW, cH = cH,
                        accent = accent, soft = colors.accentSoft, text = colors.text, dim = colors.textDim,
                        tilt = tilt,
                    )
                }
            }

            if (state.windows.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NO WINDOWS", color = accent, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 14.sp)
                    Text(
                        "OPEN으로 창을 추가하거나\n\"날씨 창 열어줘\" 라고 말해 보세요",
                        color = colors.textDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // ---- picker
            if (picker) {
                HoloPanel.values().forEachIndexed { i, panel ->
                    val r = pickerRect(i)
                    val open = state.openPanels.contains(panel)
                    Box(
                        Modifier
                            .offsetFraction(r, cW, cH)
                            .size(androidx.compose.ui.unit.Dp(r.w * cW / density), androidx.compose.ui.unit.Dp(r.h * cH / density))
                            .drawBehindChip(accent, open),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(panel.ko, color = if (open) Color.Black else colors.text, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            // ---- title + status
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HOLOGRAPHIC PROJECTOR", color = colors.text, fontSize = 13.sp, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "${state.windows.size} / ${HoloPanel.MAX_OPEN} WINDOWS · " + when {
                                tracking.isNotEmpty() -> tracking.uppercase()
                                handsEnabled -> "STARTING CAMERA…"
                                else -> "TOUCH MODE"
                            },
                            color = accent, fontSize = 9.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    JarvisCore(hud.phase, level, Modifier.size(58.dp))
                }
                if (!handsEnabled) {
                    Text(
                        if (handsPermitted) "AIR GESTURES OFF · TAP TO ENABLE" else "TAP TO ENABLE HAND TRACKING (CAMERA)",
                        color = colors.accentSoft, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onEnableHands),
                    )
                } else {
                    Text(
                        "집게손가락으로 가리키고 · 엄지와 검지를 모아 집어 옮기기 · 1초 머무르면 클릭",
                        color = colors.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---- subtitle + hint
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 84.dp, start = 18.dp, end = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val target = state.target
                if (target != null) {
                    Text(
                        "▸ ${target.ko} 창을 가리키는 중 — 말씀하시면 이 창을 기준으로 이해합니다",
                        color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    )
                }
                hud.subtitle?.let {
                    TypewriterText(it, colors.text, 15.sp, Modifier.padding(top = 6.dp))
                }
                hud.notice?.let { Text(it, color = colors.textDim, fontSize = 11.sp, textAlign = TextAlign.Center) }
            }

            // ---- toolbar
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(72.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TOOLBAR.forEachIndexed { i, label ->
                    val active = (i == 0 && picker) || (i == 2 && hud.phase.name == "LISTENING")
                    Box(
                        Modifier.weight(1f).fillMaxSize().drawBehindChip(accent, active),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (active) Color.Black else colors.accentSoft, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ---- air cursor on top of everything
            HandCursor(pointerFlow, accent, { clock.floatValue })
        }
    }
}

private fun Modifier.offsetFraction(r: FRect, cW: Float, cH: Float): Modifier =
    this.then(Modifier.androidxOffsetPx((r.l * cW).toInt(), (r.t * cH).toInt()))

private fun Modifier.androidxOffsetPx(x: Int, y: Int): Modifier =
    this.offset { androidx.compose.ui.unit.IntOffset(x, y) }

private fun Modifier.drawBehindChip(accent: Color, active: Boolean): Modifier = this.drawBehind {
    drawRect(accent.copy(alpha = if (active) 0.9f else 0.10f))
    drawRect(accent.copy(alpha = 0.7f), style = Stroke(1.2f * density))
}
