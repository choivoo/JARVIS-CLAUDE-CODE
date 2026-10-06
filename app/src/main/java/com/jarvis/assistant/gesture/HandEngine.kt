package com.jarvis.assistant.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** A landmark in normalised, upright image coordinates (0..1, y grows downwards). */
data class Pt(val x: Float, val y: Float)

/** 21 MediaPipe hand landmarks, upright, NOT mirrored (as the front camera sees the user). */
class HandFrame(val points: List<Pt>, val timeMs: Long) {
    init {
        require(points.size == 21) { "A hand has 21 landmarks" }
    }
}

enum class HandPose { UNKNOWN, POINT, PINCH, OPEN_PALM, FIST, VICTORY, THUMBS_UP }

sealed interface HandEvent {
    /** Pointer position in screen fractions (0..1), already mirrored and smoothed. */
    data class Cursor(val x: Float, val y: Float, val pose: HandPose, val pinching: Boolean, val dwell: Float) : HandEvent
    data class PinchDown(val x: Float, val y: Float) : HandEvent
    data class PinchUp(val x: Float, val y: Float) : HandEvent
    data class DwellClick(val x: Float, val y: Float) : HandEvent
    data class SwipeEvent(val direction: Swipe) : HandEvent
    data class PoseHold(val pose: HandPose) : HandEvent
    data object Lost : HandEvent
}

data class HandConfig(
    /** Thumb-to-index distance (in palm sizes) below which the hand is pinching / above which it releases. */
    val pinchOn: Float = 0.30f,
    val pinchOff: Float = 0.46f,
    val holdMs: Long = 800,
    val dwellMs: Long = 900,
    val dwellRadius: Float = 0.03f,
    val swipeTravel: Float = 0.30f,
    val swipeWindowMs: Long = 450,
    val swipeCooldownMs: Long = 900,
    val lostMs: Long = 300,
    /** Share of the camera frame that maps onto the whole screen, so the edges are reachable without leaving view. */
    val activeRegion: Float = 0.70f,
    val dwellEnabled: Boolean = true,
)

/** Geometry of one hand: pose, pinch distance and a stable pointer position. */
object HandAnalyzer {
    class Features(
        val pose: HandPose,
        val pinchRatio: Float,
        val pinching: Boolean,
        val pointer: Pt,
        val palm: Pt,
        val palmSize: Float,
    )

    private const val WRIST = 0
    private const val THUMB_TIP = 4
    private const val INDEX_MCP = 5
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9

    private fun d(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /** A finger counts as extended when its tip is clearly farther from the wrist than its middle joint. */
    private fun extended(p: List<Pt>, tip: Int, pip: Int) = d(p[tip], p[WRIST]) > d(p[pip], p[WRIST]) * 1.08f

    fun analyze(p: List<Pt>, wasPinching: Boolean, config: HandConfig): Features {
        val palmSize = d(p[WRIST], p[MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val pinchRatio = d(p[THUMB_TIP], p[INDEX_TIP]) / palmSize
        // A fist also brings thumb and index tips together; a pinch keeps the index finger reaching forward.
        val indexReach = d(p[INDEX_TIP], p[WRIST]) / palmSize
        val pinching = if (wasPinching) pinchRatio < config.pinchOff && indexReach > 0.80f
        else pinchRatio < config.pinchOn && indexReach > 0.95f

        val index = extended(p, 8, 6)
        val middle = extended(p, 12, 10)
        val ring = extended(p, 16, 14)
        val pinky = extended(p, 20, 18)
        val thumbOut = d(p[THUMB_TIP], p[INDEX_MCP]) > palmSize * 0.62f
        val thumbUp = thumbOut && p[THUMB_TIP].y < p[WRIST].y - palmSize * 0.55f

        val pose = when {
            pinching -> HandPose.PINCH
            index && !middle && !ring && !pinky -> HandPose.POINT
            index && middle && !ring && !pinky -> HandPose.VICTORY
            index && middle && ring && pinky -> HandPose.OPEN_PALM
            !index && !middle && !ring && !pinky -> if (thumbUp) HandPose.THUMBS_UP else HandPose.FIST
            else -> HandPose.UNKNOWN
        }
        val palm = Pt(
            (p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5f,
            (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5f,
        )
        val pointer = when (pose) {
            // Midpoint of the pinching fingertips is far steadier than the index tip alone.
            HandPose.PINCH -> Pt((p[THUMB_TIP].x + p[INDEX_TIP].x) / 2f, (p[THUMB_TIP].y + p[INDEX_TIP].y) / 2f)
            HandPose.OPEN_PALM, HandPose.FIST, HandPose.THUMBS_UP -> palm
            else -> p[INDEX_TIP]
        }
        return Features(pose, pinchRatio, pinching, pointer, palm, palmSize)
    }
}

/** One Euro filter: heavy smoothing when still (no jitter), light smoothing when moving fast (no lag). */
class OneEuro(private val minCutoff: Float = 1.4f, private val beta: Float = 6f, private val dCutoff: Float = 1.0f) {
    private var x = 0f
    private var dx = 0f
    private var last = -1f
    private var primed = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() {
        primed = false
        last = -1f
    }

    fun filter(value: Float, tSec: Float): Float {
        if (!primed) {
            primed = true
            x = value
            dx = 0f
            last = tSec
            return value
        }
        val dt = (tSec - last).coerceAtLeast(1e-3f)
        last = tSec
        val rawDx = (value - x) / dt
        dx += alpha(dCutoff, dt) * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }
}

/**
 * Turns a stream of hand frames into pointer, click, drag, swipe and pose events.
 * Pure Kotlin, so every rule is unit tested without a camera.
 */
class HandGestureEngine(var config: HandConfig = HandConfig()) {
    private val fx = OneEuro()
    private val fy = OneEuro()

    private var wasPinching = false
    private var pinchFrames = 0
    private var releaseFrames = 0
    private var pinchActive = false

    private var stablePose = HandPose.UNKNOWN
    private var poseFrames = 0
    private var poseSince = 0L
    private var poseFired = false

    private var lastSeen = -1L
    private var lostSent = true

    private val palmTrack = ArrayDeque<Triple<Long, Float, Float>>()
    private var swipeCooldownUntil = 0L

    private var dwellAnchor: Pair<Float, Float>? = null
    private var dwellSince = 0L
    private var dwellLatched = false

    fun reset() {
        fx.reset(); fy.reset()
        wasPinching = false; pinchActive = false; pinchFrames = 0; releaseFrames = 0
        stablePose = HandPose.UNKNOWN; poseFrames = 0; poseFired = false
        palmTrack.clear(); dwellAnchor = null; lostSent = true
    }

    /** Feed a frame, or null when no hand is visible. [nowMs] is the capture time. */
    fun onFrame(frame: HandFrame?, nowMs: Long): List<HandEvent> {
        val out = mutableListOf<HandEvent>()
        if (frame == null) {
            if (!lostSent && lastSeen >= 0 && nowMs - lastSeen > config.lostMs) {
                lostSent = true
                if (pinchActive) out += HandEvent.PinchUp(lastX, lastY)
                reset()
                out += HandEvent.Lost
            }
            return out
        }
        lastSeen = nowMs
        lostSent = false

        val f = HandAnalyzer.analyze(frame.points, wasPinching, config)
        val region = config.activeRegion
        val margin = (1f - region) / 2f
        val sx = (((1f - f.pointer.x) - margin) / region).coerceIn(0f, 1f)   // mirrored: selfie view
        val sy = ((f.pointer.y - margin) / region).coerceIn(0f, 1f)
        val t = nowMs / 1000f
        val x = fx.filter(sx, t)
        val y = fy.filter(sy, t)
        lastX = x
        lastY = y

        // ---- pinch with debounce (2 frames in, 2 frames out)
        if (f.pinching) {
            pinchFrames++; releaseFrames = 0
            if (!pinchActive && pinchFrames >= 2) {
                pinchActive = true
                out += HandEvent.PinchDown(x, y)
            }
        } else {
            releaseFrames++; pinchFrames = 0
            if (pinchActive && releaseFrames >= 2) {
                pinchActive = false
                out += HandEvent.PinchUp(x, y)
            }
        }
        wasPinching = f.pinching || pinchActive

        // ---- stable pose (3 frames)
        if (f.pose == stablePose) {
            poseFrames++
        } else {
            val candidate = f.pose
            if (candidate == pendingPose) pendingFrames++ else { pendingPose = candidate; pendingFrames = 1 }
            if (pendingFrames >= 3) {
                stablePose = candidate
                poseFrames = 3
                poseSince = nowMs
                poseFired = false
                pendingFrames = 0
            }
        }
        if (!poseFired && nowMs - poseSince >= config.holdMs &&
            stablePose in setOf(HandPose.OPEN_PALM, HandPose.FIST, HandPose.VICTORY, HandPose.THUMBS_UP)
        ) {
            poseFired = true
            out += HandEvent.PoseHold(stablePose)
        }

        // ---- palm swipes (open hand only)
        if (stablePose == HandPose.OPEN_PALM && nowMs >= swipeCooldownUntil) {
            palmTrack.addLast(Triple(nowMs, x, y))
            while (palmTrack.isNotEmpty() && nowMs - palmTrack.first().first > config.swipeWindowMs) palmTrack.removeFirst()
            detectSwipe(nowMs)?.let {
                out += HandEvent.SwipeEvent(it)
                swipeCooldownUntil = nowMs + config.swipeCooldownMs
                poseFired = true // a swipe is not also a "hold"
                palmTrack.clear()
            }
        } else if (stablePose != HandPose.OPEN_PALM) {
            palmTrack.clear()
        }

        // ---- dwell click (pointing finger resting on a spot)
        var dwell = 0f
        if (config.dwellEnabled && stablePose == HandPose.POINT) {
            val anchor = dwellAnchor
            if (anchor == null || hypot(x - anchor.first, y - anchor.second) > config.dwellRadius) {
                dwellAnchor = x to y
                dwellSince = nowMs
                dwellLatched = false
            } else if (!dwellLatched) {
                dwell = ((nowMs - dwellSince).toFloat() / config.dwellMs).coerceIn(0f, 1f)
                if (dwell >= 1f) {
                    dwellLatched = true
                    out += HandEvent.DwellClick(anchor.first, anchor.second)
                }
            }
        } else {
            dwellAnchor = null
        }

        out += HandEvent.Cursor(x, y, stablePose.takeIf { it != HandPose.UNKNOWN } ?: f.pose, pinchActive, dwell)
        return out
    }

    private var lastX = 0.5f
    private var lastY = 0.5f
    private var pendingPose = HandPose.UNKNOWN
    private var pendingFrames = 0

    private fun detectSwipe(nowMs: Long): Swipe? {
        if (palmTrack.size < 4) return null
        val first = palmTrack.first()
        val last = palmTrack.last()
        val dt = (last.first - first.first) / 1000f
        if (dt < 0.12f) return null
        val dx = last.second - first.second
        val dy = last.third - first.third
        val major = maxOf(abs(dx), abs(dy))
        if (major < config.swipeTravel || major / dt < 0.55f) return null
        val horizontal = abs(dx) >= abs(dy)
        val minor = if (horizontal) abs(dy) else abs(dx)
        if (minor > major * 0.6f) return null
        return if (horizontal) (if (dx > 0) Swipe.RIGHT else Swipe.LEFT) else (if (dy > 0) Swipe.DOWN else Swipe.UP)
    }
}
