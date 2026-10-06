package com.friday.assistant.core

/** One interaction's measured timings in ms. A null field was NOT measured for that interaction. */
data class LatencyReport(
    val wake: Long? = null,
    val stt: Long? = null,
    val ai: Long? = null,
    val command: Long? = null,
    val ttsFirstAudio: Long? = null,
    val total: Long? = null,
)

/** Collects timestamps through the pipeline; only real measurements ever become numbers. */
class LatencyTracker(private val now: () -> Long) {
    private var wakeAt: Long? = null
    private var listenStart: Long? = null
    private var sttEnd: Long? = null
    private var aiMs: Long? = null
    private var cmdMs: Long? = null
    private var ttsRequested: Long? = null
    private var wake: Long? = null
    private var stt: Long? = null
    private var ttsFirst: Long? = null
    private var total: Long? = null
    var last: LatencyReport? = null
        private set

    fun reset() { wakeAt = null; listenStart = null; sttEnd = null; aiMs = null; cmdMs = null; ttsRequested = null; wake = null; stt = null; ttsFirst = null; total = null }
    fun wakeDetected() { reset(); wakeAt = now() }
    fun listeningStarted() { listenStart = now(); wakeAt?.let { wake = listenStart!! - it } }
    fun sttFinished() { sttEnd = now(); listenStart?.let { stt = sttEnd!! - it } }
    /** Text arrived without STT (typed input): nothing about STT is measured but the pipeline clock starts. */
    fun textReceived() { if (sttEnd == null) sttEnd = now() }
    fun aiFinished(ms: Long) { aiMs = (aiMs ?: 0) + ms }
    fun commandFinished(ms: Long) { cmdMs = (cmdMs ?: 0) + ms }
    fun ttsRequested() { ttsRequested = now(); ttsFirst = null }
    fun firstAudio() {
        if (ttsFirst != null) return
        val t = now()
        ttsRequested?.let { ttsFirst = t - it }
        sttEnd?.let { total = t - it }
        last = LatencyReport(wake, stt, aiMs, cmdMs, ttsFirst, total)
    }
}
