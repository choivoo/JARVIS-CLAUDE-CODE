package com.jarvis.assistant.command

import java.time.LocalTime

data class ParsedTime(val hour: Int, val minute: Int)

/** Parses spoken Korean clock times such as "오후 3시 30분", "8시 반", "오전 7:15". */
object KoreanTimeParser {
    private val spokenHours = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한", "열두")
    private val hourWords = spokenHours.joinToString("|").replace("열한", """열\s*한""").replace("열두", """열\s*두""")
    private const val periodWords = "오전|오후|아침|저녁|밤|새벽|낮"
    private val clock = Regex(
        """(?<!\d)($periodWords)?\s*(?:(\d{1,2}):(\d{2})(?!\d)|""" +
            """(\d{1,2}|(?:(?<![가-힣])|(?<=$periodWords))(?:$hourWords))\s*시(?!간)""" +
            """(?:\s*(\d+|[영공일이삼사오육칠팔구십백천]+)\s*분|\s*(반))?)"""
    )
    private const val digits = "영일이삼사오육칠팔구"
    private val spokenMinutes = (0..59).associateBy { minute ->
        val tens = minute / 10
        val ones = minute % 10
        when {
            tens == 0 -> digits[ones].toString()
            else -> (if (tens == 1) "" else digits[tens].toString()) + "십" +
                (if (ones == 0) "" else digits[ones].toString())
        }
    }

    data class ClockMatch(val time: ParsedTime, val range: IntRange)

    fun parse(text: String, now: LocalTime = LocalTime.now()): ParsedTime? = find(text, now)?.time

    fun find(text: String, now: LocalTime): ClockMatch? {
        val match = clock.find(text) ?: return null
        val (period, colonHour, colonMinute, koreanHour, koreanMinute, half) = match.destructured
        val raw = colonHour.ifEmpty { koreanHour }.filterNot { it.isWhitespace() }
        val hourRaw = raw.toIntOrNull() ?: (spokenHours.indexOf(raw) + 1)
        val minute = when {
            half.isNotEmpty() -> 30
            colonMinute.isNotEmpty() -> colonMinute.toInt()
            koreanMinute.isNotEmpty() -> koreanMinute.toIntOrNull() ?: spokenMinutes[koreanMinute] ?: return null
            else -> 0
        }
        if (hourRaw !in 0..24 || minute !in 0..59) return null
        if (period.isNotEmpty() && hourRaw !in 1..12) return null
        if (hourRaw == 24 && minute != 0) return null
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
        return ClockMatch(ParsedTime(resolved, minute), match.range)
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
