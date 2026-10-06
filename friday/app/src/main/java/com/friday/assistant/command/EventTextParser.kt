package com.friday.assistant.command

import java.time.LocalDate
import java.time.LocalDateTime

data class ParsedEvent(val title: String?, val date: LocalDate, val hour: Int?, val minute: Int)

/** "금요일 오후 4시에 코딩 일정 추가해줘" -> title 코딩, next Friday, 16:00. */
object EventTextParser {
    private val noise = Regex("""(캘린더에?|달력에?|일정|스케줄|추가|등록|만들어|잡아|넣어|해줘|해주세요|해 줘|해봐)|(?<=\s|^)(좀|에서|에)(?=\s|$)""")
    private val timeTokens = Regex("""(오전|오후|아침|저녁|밤|낮|새벽)|(\d{1,2}\s*:\s*\d{2})|(\d{1,2}|열두|열한|열|아홉|여덟|일곱|여섯|다섯|네|세|두|한)\s*시(\s*\d{1,2}\s*분|\s*반)?""")

    fun parse(text: String, now: LocalDateTime): ParsedEvent {
        val dm = KoreanDateParser.parse(text, now.toLocalDate())
        val date = dm?.date ?: now.toLocalDate()
        var rest = text
        if (dm != null) rest = rest.removeRange(dm.range.first, dm.range.last + 1)
        val time = KoreanTimeParser.parse(text, now)
        rest = rest.replace(Regex("""(다음\s*주|담주|이번\s*주)"""), " ").replace(timeTokens, " ").replace(noise, " ")
            .replace(Regex("""\s+"""), " ").trim()
        // when no date word was spoken but a time was, a past time today means tomorrow
        var d = date
        if (dm == null && time != null && LocalDateTime.of(date, java.time.LocalTime.of(time.hour % 24, time.minute)).isBefore(now)) d = date.plusDays(1)
        return ParsedEvent(rest.ifBlank { null }, d, time?.hour, time?.minute ?: 0)
    }
}
