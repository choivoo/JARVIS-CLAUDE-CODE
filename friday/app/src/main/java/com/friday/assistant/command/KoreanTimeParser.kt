package com.friday.assistant.command

import java.time.LocalDateTime

data class ParsedTime(val hour: Int, val minute: Int, val dayOffset: Int)

/** Parses spoken Korean times such as "내일 오전 7시 반", "저녁 8시 15분", "19:30", "30분 뒤". */
object KoreanTimeParser {
    private val nativeNumbers = mapOf(
        "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6,
        "다섯" to 5, "네" to 4, "넷" to 4, "세" to 3, "셋" to 3, "두" to 2, "둘" to 2, "한" to 1, "하나" to 1,
    )
    private val relative = Regex("""(\d{1,3})\s*(시간|분)\s*(뒤|후)""")
    private val colon = Regex("""(\d{1,2})\s*:\s*(\d{2})""")
    private val hourRegex = Regex("""(\d{1,2}|열두|열한|열|아홉|여덟|일곱|여섯|다섯|네|넷|세|셋|두|둘|한|하나)\s*시""")
    private val minuteRegex = Regex("""시\s*(\d{1,2})\s*분""")

    fun parse(input: String, now: LocalDateTime): ParsedTime? {
        val text = input.replace("\\s+".toRegex(), " ")
        val dayOffset = when {
            text.contains("모레") -> 2
            text.contains("내일") -> 1
            else -> 0
        }
        relative.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val target = if (m.groupValues[2] == "시간") now.plusHours(n.toLong()) else now.plusMinutes(n.toLong())
            return ParsedTime(target.hour, target.minute, (target.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()).toInt())
        }
        var hour: Int
        var minute = 0
        val c = colon.find(text)
        if (c != null) {
            hour = c.groupValues[1].toInt()
            minute = c.groupValues[2].toInt()
        } else {
            val h = hourRegex.find(text) ?: return null
            val raw = h.groupValues[1]
            hour = raw.toIntOrNull() ?: nativeNumbers[raw] ?: return null
            val mm = minuteRegex.find(text)
            if (mm != null) minute = mm.groupValues[1].toInt()
            else if (text.substring(h.range.last + 1).trimStart().startsWith("반")) minute = 30
        }
        if (hour > 24 || minute > 59) return null

        val pm = Regex("오후|저녁|밤|낮").containsMatchIn(text)
        val am = Regex("오전|아침|새벽").containsMatchIn(text)
        val explicit24 = hour == 0 || hour > 12
        when {
            explicit24 -> Unit
            am -> if (hour == 12) hour = 0
            pm -> if (hour < 12 && !(text.contains("밤") && hour == 12)) hour += 12 else if (text.contains("밤") && hour == 12) hour = 0
            else -> hour = guessMeridiem(hour, dayOffset, minute, now)
        }
        return ParsedTime(hour % 24, minute, dayOffset + if (hour == 24) 1 else 0)
    }

    /** No am/pm spoken: for today pick the next upcoming one, otherwise 1–6 → afternoon, 7–11 → morning. */
    private fun guessMeridiem(h: Int, dayOffset: Int, minute: Int, now: LocalDateTime): Int {
        if (dayOffset == 0) {
            val nowMin = now.hour * 60 + now.minute
            val amMin = (h % 12) * 60 + minute
            val pmMin = amMin + 12 * 60
            return when {
                amMin > nowMin -> h % 12
                pmMin > nowMin -> h % 12 + 12
                else -> h % 12
            }
        }
        return if (h in 1..6 || h == 12) h % 12 + 12 else h
    }
}
