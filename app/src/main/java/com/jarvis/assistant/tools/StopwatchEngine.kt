package com.jarvis.assistant.tools

/** In-memory stopwatch with laps (time source injectable for tests). */
class StopwatchEngine(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var startedAt = -1L
    private var accumulated = 0L
    private val laps = mutableListOf<Long>()

    val running: Boolean get() = startedAt >= 0

    fun start(): Boolean {
        if (running) return false
        startedAt = clock()
        return true
    }

    fun elapsedMs(): Long = accumulated + if (running) clock() - startedAt else 0L

    /** Returns elapsed time when stopped, or null if it was not running. */
    fun stop(): Long? {
        if (!running) return null
        accumulated += clock() - startedAt
        startedAt = -1
        return accumulated
    }

    fun lap(): Long? {
        if (!running) return null
        val e = elapsedMs()
        laps += e
        return e
    }

    fun lapCount() = laps.size

    fun reset() {
        startedAt = -1
        accumulated = 0
        laps.clear()
    }

    companion object {
        fun speakEn(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts += "$h hour${if (h > 1) "s" else ""}"
            if (m > 0) parts += "$m minute${if (m > 1) "s" else ""}"
            if (s > 0 || parts.isEmpty()) parts += "$s second${if (s != 1L) "s" else ""}"
            return parts.joinToString(" ")
        }

        fun speakKo(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return buildString {
                if (h > 0) append("${h}시간 ")
                if (m > 0) append("${m}분 ")
                if (s > 0 || (h == 0L && m == 0L)) append("${s}초")
            }.trim()
        }
    }
}
