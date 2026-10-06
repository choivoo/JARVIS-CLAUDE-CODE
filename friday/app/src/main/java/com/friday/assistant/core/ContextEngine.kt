package com.friday.assistant.core

import com.friday.assistant.calendar.EventInfo

data class ContextFact(val kind: String, val summary: String, val at: Long)

/**
 * Minimal short-term memory for follow-ups ("그럼 우산 필요할까?"). Only tiny, non-private summaries are kept,
 * they expire, and only a few exist at once. Calendar details stay on the device (used by local follow-ups only).
 */
class ContextEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60_000,
    private val maxFacts: Int = 4,
) {
    private val facts = ArrayDeque<ContextFact>()
    var lastEvents: List<EventInfo> = emptyList()
        private set
    private var eventsAt = 0L

    val size: Int get() = facts().size

    fun remember(kind: String, summary: String) {
        facts.removeAll { it.kind == kind }
        facts.addLast(ContextFact(kind, summary.take(300), clock()))
        while (facts.size > maxFacts) facts.removeFirst()
    }

    fun rememberEvents(events: List<EventInfo>) { lastEvents = events; eventsAt = clock() }

    fun events(): List<EventInfo> = if (clock() - eventsAt <= ttlMs) lastEvents else emptyList()

    fun facts(): List<ContextFact> = facts.filter { clock() - it.at <= ttlMs }

    /** Text added to the AI prompt, or null when there is nothing relevant. */
    fun promptBlock(): String? = facts().takeIf { it.isNotEmpty() }
        ?.joinToString("; ", prefix = "Recent context (may be referred to as 'it', 'then', '그럼'): ") { "${it.kind}: ${it.summary}" }

    fun clear() { facts.clear(); lastEvents = emptyList() }
}
