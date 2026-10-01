package com.jarvis.assistant

import com.jarvis.assistant.gesture.MotionSwipeDetector
import com.jarvis.assistant.gesture.ProximityWaveDetector
import com.jarvis.assistant.gesture.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureDetectorsTest {
    private val w = 320
    private val h = 240

    /** Grey background with a bright 60x60 "hand" centred at (cx, cy) in buffer pixel coordinates. */
    private fun frame(cx: Int?, cy: Int = h / 2, bg: Int = 60): ByteArray {
        val data = ByteArray(w * h) { bg.toByte() }
        if (cx != null) {
            for (y in (cy - 30).coerceAtLeast(0) until (cy + 30).coerceAtMost(h)) {
                for (x in (cx - 30).coerceAtLeast(0) until (cx + 30).coerceAtMost(w)) data[y * w + x] = 230.toByte()
            }
        }
        return data
    }

    private fun run(
        detector: MotionSwipeDetector,
        path: List<Pair<Int, Int>>,
        rotation: Int,
        stepMs: Long = 66,
    ): Swipe? {
        var result: Swipe? = null
        var t = 1_000L
        detector.onFrame(frame(null), w, h, w, rotation, t)
        for ((x, y) in path) {
            t += stepMs
            detector.onFrame(frame(x, y), w, h, w, rotation, t)?.let { result = it }
        }
        return result
    }

    private fun horizontal(from: Int, to: Int, steps: Int = 8) =
        (0..steps).map { (from + (to - from) * it / steps) to h / 2 }

    private fun vertical(from: Int, to: Int, steps: Int = 8) =
        (0..steps).map { w / 2 to (from + (to - from) * it / steps) }

    @Test
    fun upright_frameMovingImageLeftIsUsersRight() {
        // Front camera, upright image: a hand moving towards the image's left is the user's right.
        assertEquals(Swipe.RIGHT, run(MotionSwipeDetector(), horizontal(260, 60), rotation = 0))
        assertEquals(Swipe.LEFT, run(MotionSwipeDetector(), horizontal(60, 260), rotation = 0))
    }

    @Test
    fun verticalSwipes() {
        assertEquals(Swipe.UP, run(MotionSwipeDetector(), vertical(190, 40), rotation = 0))
        assertEquals(Swipe.DOWN, run(MotionSwipeDetector(), vertical(40, 190), rotation = 0))
    }

    @Test
    fun portraitSensorRotation270MapsAxesCorrectly() {
        // Buffer is landscape, device portrait (rotation 270): buffer-x motion is the user's vertical motion.
        // upright (x,y) = (cy', 1-cx'): moving buffer x from low to high => upright y decreases => UP.
        assertEquals(Swipe.UP, run(MotionSwipeDetector(), horizontal(60, 260), rotation = 270))
        assertEquals(Swipe.DOWN, run(MotionSwipeDetector(), horizontal(260, 60), rotation = 270))
    }

    @Test
    fun noMotionAndStaticHandProduceNothing() {
        val d = MotionSwipeDetector()
        assertNull(run(d, List(12) { 160 to 120 }, rotation = 0))
    }

    @Test
    fun wholeFrameBrightnessChangeIsIgnored() {
        val d = MotionSwipeDetector()
        var t = 0L
        var result: Swipe? = null
        for (i in 0 until 12) {
            t += 66
            val bg = if (i % 2 == 0) 40 else 200
            d.onFrame(frame(null, bg = bg), w, h, w, 0, t)?.let { result = it }
        }
        assertNull(result)
    }

    @Test
    fun shortJitterIsNotASwipe() {
        assertNull(run(MotionSwipeDetector(), horizontal(150, 175, steps = 6), rotation = 0))
    }

    @Test
    fun cooldownPreventsDoubleTrigger() {
        val d = MotionSwipeDetector()
        assertEquals(Swipe.LEFT, run(d, horizontal(60, 260), rotation = 0))
        // Immediately swiping again within the cooldown is ignored.
        var t = 1_000L + 9 * 66L + 66
        var again: Swipe? = null
        for ((x, y) in horizontal(60, 260)) {
            t += 66
            d.onFrame(frame(x, y), w, h, w, 0, t)?.let { again = it }
        }
        assertNull(again)
    }

    // ---- proximity waves ----

    @Test
    fun singleWaveIsReportedAfterTheDoubleWindow() {
        val d = ProximityWaveDetector()
        assertNull(d.onSensor(near = true, nowMs = 0))
        assertNull(d.onSensor(near = false, nowMs = 250))
        assertNull(d.poll(500))
        assertEquals(ProximityWaveDetector.Wave.SINGLE, d.poll(900))
        assertNull(d.poll(1_500))
    }

    @Test
    fun twoQuickWavesAreADoubleWave() {
        val d = ProximityWaveDetector()
        d.onSensor(true, 0); d.onSensor(false, 200)
        d.onSensor(true, 350)
        assertEquals(ProximityWaveDetector.Wave.DOUBLE, d.onSensor(false, 520))
        assertNull(d.poll(2_000))
    }

    @Test
    fun phoneInPocketOrHeldToEarIsNotAWave() {
        val d = ProximityWaveDetector()
        d.onSensor(true, 0)
        assertNull(d.onSensor(false, 5_000))
        assertNull(d.poll(6_000))
    }

    @Test
    fun fidgetingFloodIsRateLimited() {
        val d = ProximityWaveDetector()
        var t = 0L
        var reported = 0
        repeat(20) {
            d.onSensor(true, t); t += 100
            if (d.onSensor(false, t) != null) reported++
            t += 100
            if (d.poll(t) != null) reported++
        }
        // Early waves may be reported, but the flood is cut off after the limit.
        assert(reported < 12) { "reported=$reported" }
    }
}
