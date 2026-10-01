package com.jarvis.assistant.command

import java.time.LocalTime

data class ParsedTime(val hour: Int, val minute: Int)

/** Parses spoken Korean clock times such as "오후 3시 30분", "8시 반", "오전 7:15". */
object KoreanTimeParser {
    private val colon = Regex("(오전|오후|아침|저녁|밤|새벽|낮)?\\s*(\\d{1,2}):(\\d{2})")
    private val korean = Regex("(오전|오후|아침|저녁|밤|새벽|낮)?\\s*(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분|(반))?")

    fun parse(text: String, now: LocalTime = LocalTime.now()): ParsedTime? {
        val m = colon.find(text)
        val (period, hourRaw, minute) = if (m != null) {
            Triple(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
        } else {
            val k = korean.find(text) ?: return null
            val min = when {
                k.groupValues[4].isNotEmpty() -> 30
                k.groupValues[3].isNotEmpty() -> k.groupValues[3].toInt()
                else -> 0
            }
            Triple(k.groupValues[1], k.groupValues[2].toInt(), min)
        }
        if (hourRaw !in 0..24 || minute !in 0..59) return null
        val hour = hourRaw % 24

        val resolved = when (period) {
            "오전", "아침", "새벽" -> hour % 12
            "오후", "저녁" -> if (hour < 12) hour + 12 else hour
            "낮" -> if (hour in 1..11) hour + 12 else hour
            "밤" -> if (hour == 12) 0 else if (hour < 12) hour + 12 else hour
            else -> {
                if (hour > 12 || hour == 0) hour
                else {
                    // Ambiguous: choose whichever of AM/PM comes next from now.
                    val am = hour % 12
                    val pm = am + 12
                    val nowMin = now.hour * 60 + now.minute
                    // A time equal to "now" means the next day, never an alarm in the past.
                    fun until(h: Int) = (((h * 60 + minute) - nowMin + 1440) % 1440).let { if (it == 0) 1440 else it }
                    if (until(am) <= until(pm)) am else pm
                }
            }
        }
        return ParsedTime(resolved, minute)
    }

    fun speakEn(t: ParsedTime): String {
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "%d:%02d %s".format(h12, t.minute, if (t.hour < 12) "AM" else "PM")
    }

    fun speakKo(t: ParsedTime): String {
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val period = if (t.hour < 12) "오전" else "오후"
        return if (t.minute == 0) "$period ${h12}시" else "$period ${h12}시 ${t.minute}분"
    }
}
