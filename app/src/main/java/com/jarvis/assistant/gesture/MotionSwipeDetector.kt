package com.jarvis.assistant.gesture

import kotlin.math.abs

enum class Swipe { LEFT, RIGHT, UP, DOWN }

/**
 * Detects a hand swipe from a stream of low-resolution luminance frames (front camera).
 * Pure Kotlin: frame differencing -> motion centroid track -> dominant direction.
 * Frames are never stored; only the previous 32x24 grid is kept in memory.
 *
 * Directions are from the USER's point of view (as in a mirror): moving the hand towards your own
 * right reports [Swipe.RIGHT].
 */
class MotionSwipeDetector(
    private val gridW: Int = 32,
    private val gridH: Int = 24,
    private val diffThreshold: Int = 28,
    private val minMotion: Float = 0.015f,
    private val maxMotion: Float = 0.55f,
    private val minTravel: Float = 0.30f,
    private val windowMs: Long = 700,
    private val minDurationMs: Long = 120,
    private val cooldownMs: Long = 1_100,
    private val frontCamera: Boolean = true,
) {
    private var previous: IntArray? = null
    private val track = ArrayDeque<Triple<Long, Float, Float>>() // time, x, y (upright image coordinates)
    private var cooldownUntil = 0L

    /**
     * @param luma  Y plane, [rowStride] bytes per row
     * @param rotationDegrees clockwise rotation that makes the frame upright (ImageInfo.rotationDegrees)
     */
    fun onFrame(luma: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int, nowMs: Long): Swipe? {
        val grid = downsample(luma, width, height, rowStride)
        val prev = previous
        previous = grid
        if (prev == null || nowMs < cooldownUntil) return null

        var count = 0
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val i = y * gridW + x
                if (abs(grid[i] - prev[i]) > diffThreshold) {
                    count++
                    sx += x
                    sy += y
                }
            }
        }
        val ratio = count.toFloat() / (gridW * gridH)
        if (ratio < minMotion || ratio > maxMotion) {
            // Nothing moving, or the whole frame changed (camera shake / exposure): drop the track.
            if (ratio > maxMotion) track.clear()
            trimTrack(nowMs)
            return null
        }
        val cx = (sx / count / (gridW - 1)).toFloat()
        val cy = (sy / count / (gridH - 1)).toFloat()
        val (ux, uy) = toUpright(cx, cy, rotationDegrees)
        track.addLast(Triple(nowMs, ux, uy))
        trimTrack(nowMs)
        return evaluate(nowMs)
    }

    fun reset() {
        previous = null
        track.clear()
    }

    private fun trimTrack(nowMs: Long) {
        while (track.isNotEmpty() && nowMs - track.first().first > windowMs) track.removeFirst()
    }

    private fun evaluate(nowMs: Long): Swipe? {
        if (track.size < 4) return null
        val first = track.first()
        val last = track.last()
        if (last.first - first.first < minDurationMs) return null
        val dx = last.second - first.second
        val dy = last.third - first.third
        val major = maxOf(abs(dx), abs(dy))
        if (major < minTravel) return null
        val horizontal = abs(dx) >= abs(dy)
        val minor = if (horizontal) abs(dy) else abs(dx)
        if (minor > major * 0.6f) return null

        // Mostly monotonic movement, not a hand jittering back and forth.
        var agree = 0
        var steps = 0
        val points = track.toList()
        for (i in 1 until points.size) {
            val step = if (horizontal) points[i].second - points[i - 1].second else points[i].third - points[i - 1].third
            steps++
            if (step * (if (horizontal) dx else dy) >= 0) agree++
        }
        if (agree < steps * 0.7f) return null

        track.clear()
        cooldownUntil = nowMs + cooldownMs
        return if (horizontal) {
            // Upright, un-mirrored image: the user's right is on the image's left. Mirror for a natural feel.
            val userDx = if (frontCamera) -dx else dx
            if (userDx > 0) Swipe.RIGHT else Swipe.LEFT
        } else {
            if (dy < 0) Swipe.UP else Swipe.DOWN
        }
    }

    private fun downsample(luma: ByteArray, width: Int, height: Int, rowStride: Int): IntArray {
        val out = IntArray(gridW * gridH)
        val cellW = width / gridW
        val cellH = height / gridH
        if (cellW < 1 || cellH < 1) return out
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                // Sample a few pixels per cell (cheap and enough for motion).
                var sum = 0
                var n = 0
                for (sy in 0 until 2) for (sx in 0 until 2) {
                    val px = gx * cellW + (sx * 2 + 1) * cellW / 4
                    val py = gy * cellH + (sy * 2 + 1) * cellH / 4
                    val idx = py * rowStride + px
                    if (idx in luma.indices) {
                        sum += luma[idx].toInt() and 0xFF
                        n++
                    }
                }
                out[gy * gridW + gx] = if (n == 0) 0 else sum / n
            }
        }
        return out
    }

    private fun toUpright(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }
}
