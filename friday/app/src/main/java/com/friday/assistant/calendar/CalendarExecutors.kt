package com.friday.assistant.calendar

import com.friday.assistant.command.CardKind
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ConfirmableExecutor
import com.friday.assistant.command.InfoCard
import com.friday.assistant.core.ContextEngine
import com.friday.assistant.notification.NotificationExecutors
import com.friday.assistant.util.EnglishNumbers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Opens the calendar app's "new event" screen prefilled (used when WRITE_CALENDAR was not granted). */
fun interface CalendarInsertUi { fun open(title: String, start: LocalDateTime, end: LocalDateTime): Boolean }

class CalendarExecutors(
    private val provider: CalendarProvider,
    private val context: ContextEngine,
    private val insertUi: CalendarInsertUi,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    fun all(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.GET_TODAY_EVENTS to CommandExecutor { _, _ -> day(0) },
        CommandType.GET_TOMORROW_EVENTS to CommandExecutor { _, _ -> day(1) },
        CommandType.GET_NEXT_EVENT to CommandExecutor { _, _ -> next() },
        CommandType.SEARCH_EVENTS to CommandExecutor { c, _ -> search(c.param("query")) },
        CommandType.CREATE_EVENT_REQUEST to object : ConfirmableExecutor() {
            override suspend fun prepare(command: Command) = prepareCreate(command)
            override suspend fun perform(command: Command) = performCreate(command)
        },
    )

    private fun day(offset: Long): CommandResult {
        val today = clock().toLocalDate()
        val d = today.plusDays(offset)
        val events = provider.events(d.atStartOfDay(), d.plusDays(1).atStartOfDay())
        context.rememberEvents(events)
        val label = if (offset == 0L) "today" else "tomorrow"
        val labelKo = if (offset == 0L) "오늘" else "내일"
        if (events.isEmpty()) return CommandResult.ok("You have no events $label.", "${labelKo}은 일정이 없습니다.", card = InfoCard(CardKind.CALENDAR, "${labelKo} 일정", listOf("일정 없음")))
        val first = events.first()
        val n = events.size
        return CommandResult.ok(
            "You have ${EnglishNumbers.words(n)} event${if (n == 1) "" else "s"} $label. The first is at ${timeWords(first)}.",
            "${labelKo} 일정이 ${n}개 있습니다. 첫 일정: ${timeKo(first)} ${first.title}",
            card = InfoCard(CardKind.CALENDAR, "${labelKo} 일정 $n", events.take(5).map { "${timeKo(it)} ${it.title}" }),
            contextNote = "calendar: $n events $label",
        )
    }

    private fun next(): CommandResult {
        val now = clock()
        val events = provider.events(now, now.plusDays(14)).filter { !it.end.isBefore(now) }
        context.rememberEvents(events.take(5))
        val e = events.firstOrNull() ?: return CommandResult.ok("You have nothing coming up in the next two weeks.", "앞으로 2주간 예정된 일정이 없습니다.")
        val whenEn = dayWords(e.start.toLocalDate(), now.toLocalDate()).first
        val whenKo = dayWords(e.start.toLocalDate(), now.toLocalDate()).second
        return CommandResult.ok(
            "Your next event is $whenEn at ${timeWords(e)}.",
            "다음 일정: $whenKo ${timeKo(e)} ${e.title}",
            card = InfoCard(CardKind.CALENDAR, "다음 일정", listOf("$whenKo ${timeKo(e)}", e.title)),
        )
    }

    private fun search(query: String?): CommandResult {
        val q = query ?: return CommandResult.failed("What should I look for?", "어떤 일정을 찾을까요?")
        val now = clock()
        val hits = provider.events(now.minusDays(1), now.plusDays(60)).filter { it.title.contains(q, ignoreCase = true) }
        context.rememberEvents(hits.take(5))
        if (hits.isEmpty()) return CommandResult.ok("I didn't find a matching event.", "'$q'와 일치하는 일정이 없습니다.")
        val e = hits.first()
        val (en, ko) = dayWords(e.start.toLocalDate(), now.toLocalDate())
        return CommandResult.ok(
            "I found ${EnglishNumbers.words(hits.size)} matching event${if (hits.size == 1) "" else "s"}. The first is $en at ${timeWords(e)}.",
            "'$q' 일정 ${hits.size}개. 첫 일정: $ko ${timeKo(e)} ${e.title}",
            card = InfoCard(CardKind.CALENDAR, "검색: $q", hits.take(5).map { "${it.start.monthValue}/${it.start.dayOfMonth} ${timeKo(it)} ${it.title}" }),
        )
    }

    // ---- create (always confirmed first) --------------------------------------------------------------------

    private fun parseCreate(c: Command): Triple<String, LocalDateTime, LocalDateTime>? {
        val title = c.param("title") ?: return null
        val date = c.param("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val hour = c.param("hour")?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = c.param("minute")?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        val dur = c.param("durationMin")?.toLongOrNull()?.coerceIn(5, 24 * 60) ?: 60L
        val start = LocalDateTime.of(date, LocalTime.of(hour, minute))
        return Triple(title.take(120), start, start.plusMinutes(dur))
    }

    private fun prepareCreate(c: Command): CommandResult {
        if (c.param("title") == null) return CommandResult.failed("What should I call the event?", "일정 제목을 알려 주세요.")
        val (title, start, end) = parseCreate(c) ?: return CommandResult.failed("What day and time should it be?", "일정의 날짜와 시간을 알려 주세요.")
        val (dEn, dKo) = dayWords(start.toLocalDate(), clock().toLocalDate())
        val titleEn = if (NotificationExecutors.isMostlyLatin(title)) title else "this event"
        return CommandResult.confirm(
            "Would you like me to add $titleEn to your calendar for $dEn at ${timeWords(start)}?",
            "$dKo ${hourKo(start)}에 ‘$title’ 일정을 추가할까요?",
            Command(
                CommandType.CREATE_EVENT_REQUEST,
                mapOf("title" to title, "date" to start.toLocalDate().toString(), "hour" to start.hour.toString(), "minute" to start.minute.toString(),
                    "durationMin" to java.time.Duration.between(start, end).toMinutes().toString()),
            ),
        )
    }

    private fun performCreate(c: Command): CommandResult {
        val (title, start, end) = parseCreate(c) ?: return CommandResult.failed("I lost the event details.", "일정 정보를 잃어버렸습니다.")
        if (provider.canWrite()) {
            return if (provider.insert(title, start, end)) CommandResult.ok("Done.", "일정을 추가했습니다.")
            else CommandResult.failed("I couldn't find a calendar I can write to.", "쓸 수 있는 캘린더를 찾지 못했습니다.")
        }
        return if (insertUi.open(title, start, end)) CommandResult.ok(
            "I opened your calendar with the event filled in. Tap save to add it.",
            "캘린더를 열었습니다. 저장을 눌러 일정을 추가하세요. (쓰기 권한이 없어 직접 추가하지 못했습니다)",
        ) else CommandResult.failed("I couldn't open a calendar app.", "캘린더 앱을 열 수 없습니다.")
    }

    companion object {
        fun timeWords(e: EventInfo) = if (e.allDay) "all day" else timeWords(e.start)
        fun timeWords(t: LocalDateTime): String {
            val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
            val ap = if (t.hour < 12) "AM" else "PM"
            val m = when { t.minute == 0 -> ""; t.minute < 10 -> " oh ${EnglishNumbers.words(t.minute)}"; else -> " ${EnglishNumbers.words(t.minute)}" }
            return "${EnglishNumbers.words(h12)}$m $ap"
        }
        fun timeKo(e: EventInfo) = if (e.allDay) "종일" else hourKo(e.start)
        fun hourKo(t: LocalDateTime): String {
            val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
            return "${if (t.hour < 12) "오전" else "오후"} ${h12}시" + if (t.minute > 0) " ${t.minute}분" else ""
        }

        /** (English, Korean) wording for a date relative to today. */
        fun dayWords(d: LocalDate, today: LocalDate): Pair<String, String> {
            val diff = java.time.temporal.ChronoUnit.DAYS.between(today, d)
            return when {
                diff == 0L -> "today" to "오늘"
                diff == 1L -> "tomorrow" to "내일"
                diff in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) to ko(d.dayOfWeek)
                else -> d.format(DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)) to "${d.monthValue}월 ${d.dayOfMonth}일"
            }
        }
        private fun ko(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.KOREAN)
    }
}
