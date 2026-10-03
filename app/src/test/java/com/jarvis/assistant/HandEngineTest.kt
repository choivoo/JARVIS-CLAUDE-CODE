package com.jarvis.assistant

import com.jarvis.assistant.gesture.HandAnalyzer
import com.jarvis.assistant.gesture.HandConfig
import com.jarvis.assistant.gesture.HandEvent
import com.jarvis.assistant.gesture.HandFrame
import com.jarvis.assistant.gesture.HandGestureEngine
import com.jarvis.assistant.gesture.HandPose
import com.jarvis.assistant.gesture.OneEuro
import com.jarvis.assistant.gesture.Pt
import com.jarvis.assistant.gesture.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Builds a plausible upright hand (fingers up) at a given centre, with chosen finger states. */
private object Hand {
    private val config = HandConfig()

    fun build(
        cx: Float = 0.5f, cy: Float = 0.55f,
        index: Boolean = false, middle: Boolean = false, ring: Boolean = false, pinky: Boolean = false,
        thumbOut: Boolean = false, thumbUp: Boolean = false, pinch: Boolean = false,
    ): List<Pt> {
        val p = MutableList(21) { Pt(cx, cy) }
        val wrist = Pt(cx, cy + 0.25f)            // palm size 0.25
        p[0] = wrist
        val mcpX = listOf(cx - 0.075f, cx - 0.025f, cx + 0.025f, cx + 0.075f)   // index..pinky
        val mcpY = cy
        val states = listOf(index, middle, ring, pinky)
        for (f in 0 until 4) {
            val base = 5 + f * 4
            val mx = mcpX[f]
            p[base] = Pt(mx, mcpY)                                 // MCP
            p[base + 1] = Pt(mx, mcpY - 0.07f)                      // PIP (always up)
            if (states[f]) {
                p[base + 2] = Pt(mx, mcpY - 0.13f)                  // DIP
                p[base + 3] = Pt(mx, mcpY - 0.19f)                  // TIP extended
            } else {
                p[base + 2] = Pt(mx, mcpY - 0.03f)                  // DIP folded back
                p[base + 3] = Pt(mx, mcpY + 0.05f)                  // TIP curled onto the palm
            }
        }
        // thumb 1..4
        p[1] = Pt(cx - 0.07f, cy + 0.20f)
        p[2] = Pt(cx - 0.10f, cy + 0.14f)
        p[3] = Pt(cx - 0.12f, cy + 0.09f)
        p[4] = when {
            thumbUp -> Pt(cx - 0.12f, cy - 0.25f)                   // far above the wrist
            thumbOut -> Pt(cx - 0.24f, cy + 0.04f)                  // away from the index base
            else -> Pt(cx - 0.05f, cy + 0.08f)                      // folded across the palm
        }
        if (pinch) {
            val tip = p[8]
            p[4] = Pt(tip.x + 0.01f, tip.y + 0.01f)
        }
        return p
    }
}

class HandEngineTest {
    private val cfg = HandConfig()
    private fun pose(points: List<Pt>, was: Boolean = false) = HandAnalyzer.analyze(points, was, cfg).pose

    @Test
    fun classifiesTheBasicHandShapes() {
        assertEquals(HandPose.POINT, pose(Hand.build(index = true)))
        assertEquals(HandPose.VICTORY, pose(Hand.build(index = true, middle = true)))
        assertEquals(HandPose.OPEN_PALM, pose(Hand.build(true, true, true, true, thumbOut = true).let { it }))
        assertEquals(HandPose.FIST, pose(Hand.build()))
        assertEquals(HandPose.THUMBS_UP, pose(Hand.build(thumbOut = true, thumbUp = true)))
    }

    private fun Hand.build(a: Boolean, b: Boolean, c: Boolean, d: Boolean, thumbOut: Boolean) = Hand.build(
        index = a, middle = b, ring = c, pinky = d, thumbOut = thumbOut,
    )

    @Test
    fun pinchHasHysteresis() {
        val pinched = Hand.build(index = true, pinch = true)
        assertEquals(HandPose.PINCH, pose(pinched))
        // Slightly opened but still within the release threshold: stays pinched once pinching.
        val p = pinched.toMutableList()
        p[4] = Pt(p[8].x + 0.08f, p[8].y + 0.03f)            // ratio ~ 0.34 (> on 0.30, < off 0.46)
        assertEquals(HandPose.PINCH, pose(p, was = true))
        assertTrue(pose(p, was = false) != HandPose.PINCH)
        val open = pinched.toMutableList()
        open[4] = Pt(p[8].x + 0.25f, p[8].y + 0.1f)
        assertTrue(pose(open, was = true) != HandPose.PINCH)
    }

    // ---- engine ----
    private fun run(
        engine: HandGestureEngine,
        frames: List<List<Pt>?>,
        stepMs: Long = 33,
        start: Long = 1_000,
    ): List<HandEvent> {
        val out = mutableListOf<HandEvent>()
        var t = start
        for (f in frames) {
            out += engine.onFrame(f?.let { HandFrame(it, t) }, t)
            t += stepMs
        }
        return out
    }

    @Test
    fun cursorIsMirroredAndRemapped() {
        val e = HandGestureEngine()
        // Hand on the user's right = image LEFT (x small) => cursor on the right of the screen.
        val events = run(e, List(20) { Hand.build(cx = 0.2f, cy = 0.5f, index = true) })
        val c = events.filterIsInstance<HandEvent.Cursor>().last()
        assertTrue("cursor x=${c.x}", c.x > 0.6f)
        // Index tip is ~0.19 above the hand centre; the vertical centre maps near the screen middle.
        assertTrue(c.y in 0f..1f)
        val left = run(HandGestureEngine(), List(20) { Hand.build(cx = 0.8f, cy = 0.5f, index = true) })
            .filterIsInstance<HandEvent.Cursor>().last()
        assertTrue("left cursor x=${left.x}", left.x < 0.4f)
    }

    @Test
    fun cursorStaysSteadyOnATremblingHand() {
        val e = HandGestureEngine()
        val xs = mutableListOf<Float>()
        var t = 1_000L
        for (i in 0 until 40) {
            val jitter = if (i % 2 == 0) 0.004f else -0.004f
            e.onFrame(HandFrame(Hand.build(cx = 0.5f + jitter, cy = 0.5f, index = true), t), t).filterIsInstance<HandEvent.Cursor>()
                .forEach { if (i > 10) xs += it.x }
            t += 33
        }
        assertTrue("spread ${xs.max() - xs.min()}", xs.max() - xs.min() < 0.02f)
    }

    @Test
    fun pinchProducesDownThenUpAndTracksDragging() {
        val e = HandGestureEngine()
        val frames = mutableListOf<List<Pt>?>()
        repeat(6) { frames += Hand.build(cx = 0.5f, index = true) }
        repeat(6) { frames += Hand.build(cx = 0.5f, index = true, pinch = true) }
        repeat(8) { i -> frames += Hand.build(cx = 0.5f - i * 0.02f, index = true, pinch = true) }   // drag towards user's right
        repeat(6) { frames += Hand.build(cx = 0.35f, index = true) }
        val events = run(e, frames)
        val down = events.filterIsInstance<HandEvent.PinchDown>()
        val up = events.filterIsInstance<HandEvent.PinchUp>()
        assertEquals(1, down.size)
        assertEquals(1, up.size)
        assertTrue("dragged right: ${down[0].x} -> ${up[0].x}", up[0].x > down[0].x + 0.1f)
    }

    @Test
    fun holdingAPoseFiresOnceAfterTheHoldTime() {
        val e = HandGestureEngine()
        val events = run(e, List(40) { Hand.build(true, true, true, true, thumbOut = true).let { _ -> Hand.build(index = true, middle = true, ring = true, pinky = true) } })
        val holds = events.filterIsInstance<HandEvent.PoseHold>()
        assertEquals(1, holds.size)
        assertEquals(HandPose.OPEN_PALM, holds[0].pose)
    }

    @Test
    fun aFlickeringPoseIsIgnored() {
        val e = HandGestureEngine()
        val frames = List(40) { i ->
            if (i % 2 == 0) Hand.build(index = true, middle = true, ring = true, pinky = true) else Hand.build()
        }
        assertTrue(run(e, frames).none { it is HandEvent.PoseHold })
    }

    @Test
    fun dwellOnAPointFiresAClick() {
        val e = HandGestureEngine()
        val events = run(e, List(50) { Hand.build(cx = 0.5f, cy = 0.5f, index = true) })
        assertEquals(1, events.filterIsInstance<HandEvent.DwellClick>().size)
        val progress = events.filterIsInstance<HandEvent.Cursor>().map { it.dwell }
        assertTrue(progress.any { it in 0.2f..0.9f })
    }

    @Test
    fun movingFingerDoesNotDwellClick() {
        val e = HandGestureEngine()
        val frames = List(50) { i -> Hand.build(cx = 0.3f + i * 0.008f, cy = 0.5f, index = true) }
        assertTrue(run(e, frames).none { it is HandEvent.DwellClick })
    }

    @Test
    fun openPalmSwipesInTheUsersDirection() {
        fun swipe(from: Float, to: Float): Swipe? {
            val e = HandGestureEngine()
            val frames = mutableListOf<List<Pt>?>()
            repeat(6) { frames += Hand.build(cx = from, index = true, middle = true, ring = true, pinky = true) }
            repeat(10) { i -> frames += Hand.build(cx = from + (to - from) * (i + 1) / 10f, index = true, middle = true, ring = true, pinky = true) }
            return run(e, frames).filterIsInstance<HandEvent.SwipeEvent>().firstOrNull()?.direction
        }
        // Image-left is the user's right.
        assertEquals(Swipe.RIGHT, swipe(0.75f, 0.25f))
        assertEquals(Swipe.LEFT, swipe(0.25f, 0.75f))
    }

    @Test
    fun aSlowDriftIsNotASwipe() {
        val e = HandGestureEngine()
        val frames = List(60) { i -> Hand.build(cx = 0.4f + i * 0.002f, index = true, middle = true, ring = true, pinky = true) }
        assertTrue(run(e, frames).none { it is HandEvent.SwipeEvent })
    }

    @Test
    fun losingTheHandReleasesAPinchAndReportsLost() {
        val e = HandGestureEngine()
        val frames = mutableListOf<List<Pt>?>()
        repeat(8) { frames += Hand.build(index = true, pinch = true) }
        repeat(20) { frames += null }
        val events = run(e, frames)
        assertEquals(1, events.filterIsInstance<HandEvent.PinchDown>().size)
        assertEquals(1, events.filterIsInstance<HandEvent.PinchUp>().size)
        assertEquals(1, events.filterIsInstance<HandEvent.Lost>().size)
        assertNotNull(events.lastOrNull())
    }

    @Test
    fun oneEuroSmoothsStillAndFollowsFast() {
        val f = OneEuro()
        var t = 0f
        var last = 0f
        for (i in 0 until 30) { last = f.filter(0.5f + (if (i % 2 == 0) 0.01f else -0.01f), t); t += 0.033f }
        assertTrue(abs(last - 0.5f) < 0.006f)
        val g = OneEuro()
        t = 0f
        var v = 0f
        for (i in 0 until 10) { v = g.filter(i * 0.1f, t); t += 0.033f }
        assertTrue("fast motion lag ${0.9f - v}", 0.9f - v < 0.25f)
    }
}
