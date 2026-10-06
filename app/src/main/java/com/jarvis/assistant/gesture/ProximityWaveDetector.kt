package com.jarvis.assistant.gesture

/**
 * Turns proximity-sensor near/far transitions into "hand wave" gestures. Works with the binary
 * proximity sensors found on virtually every phone, even with the screen off.
 *
 *  - a wave is NEAR then FAR within [maxNearMs] (a pocket or a phone call stays NEAR and is ignored)
 *  - one wave is reported after [doubleWindowMs] of silence, two waves inside it are a double wave
 */
class ProximityWaveDetector(
    private val maxNearMs: Long = 800,
    private val doubleWindowMs: Long = 600,
    private val maxWavesPerWindow: Int = 6,
    private val rateWindowMs: Long = 8_000,
) {
    enum class Wave { SINGLE, DOUBLE }

    private var nearSince = -1L
    private var lastWaveAt = -1L
    private var pendingSingle = false
    private val recentWaves = ArrayDeque<Long>()

    /** Feed every sensor event. Returns a gesture only for a completed double wave. */
    fun onSensor(near: Boolean, nowMs: Long): Wave? {
        if (near) {
            if (nearSince < 0) nearSince = nowMs
            return null
        }
        val since = nearSince
        nearSince = -1
        if (since < 0 || nowMs - since > maxNearMs) return null

        recentWaves.addLast(nowMs)
        while (recentWaves.isNotEmpty() && nowMs - recentWaves.first() > rateWindowMs) recentWaves.removeFirst()
        if (recentWaves.size > maxWavesPerWindow) return null // fidgeting in a pocket / bag

        if (pendingSingle && nowMs - lastWaveAt <= doubleWindowMs) {
            pendingSingle = false
            lastWaveAt = -1
            return Wave.DOUBLE
        }
        pendingSingle = true
        lastWaveAt = nowMs
        return null
    }

    /** Call periodically (or on a timer): reports a single wave once the double-wave window has passed. */
    fun poll(nowMs: Long): Wave? {
        if (pendingSingle && nearSince < 0 && nowMs - lastWaveAt > doubleWindowMs) {
            pendingSingle = false
            lastWaveAt = -1
            return Wave.SINGLE
        }
        return null
    }

    val hasPending: Boolean get() = pendingSingle

    val doubleWindow: Long get() = doubleWindowMs
}
