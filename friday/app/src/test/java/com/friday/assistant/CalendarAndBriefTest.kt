package com.friday.assistant

import com.friday.assistant.brief.BriefingComposer
import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.EventTextParser
import com.friday.assistant.command.FollowUpResolver
import com.friday.assistant.command.KoreanDateParser
import com.friday.assistant.command.LocalIntentParser
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.core.ContextEngine
import com.friday.assistant.weather.DayForecast
import com.friday.assistant.weather.WeatherReport
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAndBriefTest {
    // Tuesday 2026-10-06 07:32
    private val now = LocalDateTime.of(2026, 10, 6, 7, 32)
    private val today = now.toLocalDate()

    // ---- date / event parsing -------------------------------------------------------------------------------

    @Test fun dateParser() {
        fun d(s: String) = KoreanDateParser.parse(s, today)?.date
        assertEquals(LocalDate.of(2026, 10, 6), d("오늘 일정"))
        assertEquals(LocalDate.of(2026, 10, 7), d("내일"))
        assertEquals(LocalDate.of(2026, 10, 8), d("모레"))
        assertEquals(LocalDate.of(2026, 10, 9), d("금요일에"))           // this Friday
        assertEquals(LocalDate.of(2026, 10, 13), d("다음 주 화요일"))     // today is Tuesday -> next week's
        assertEquals(LocalDate.of(2026, 10, 12), d("다음 주 월요일"))
        assertEquals(LocalDate.of(2026, 12, 25), d("12월 25일"))
        assertEquals(LocalDate.of(2027, 1, 2), d("1월 2일"))             // already past this year -> next year
        assertNull(d("아무 날짜 없음"))
    }

    @Test fun eventTextParser() {
        val e = EventTextParser.parse("금요일 오후 4시에 코딩 일정 추가해줘", now)
        assertEquals("코딩", e.title)
        assertEquals(LocalDate.of(2026, 10, 9), e.date)
        assertEquals(16, e.hour); assertEquals(0, e.minute)
        val e2 = EventTextParser.parse("내일 오전 9시 30분 치과 예약 일정 등록해줘", now)
        assertEquals("치과 예약", e2.title); assertEquals(9, e2.hour); assertEquals(30, e2.minute)
        assertEquals(LocalDate.of(2026, 10, 7), e2.date)
        assertNull(EventTextParser.parse("금요일 오후 4시 일정 추가해줘", now).title)
    }

    @Test fun localParserBuildsCalendarCommands() {
        fun p(s: String) = LocalIntentParser.parse(s, now)
        assertEquals(CommandType.GET_TODAY_EVENTS, p("오늘 일정 알려줘")?.type)
        assertEquals(CommandType.GET_TOMORROW_EVENTS, p("내일 일정 있어?")?.type)
        assertEquals(CommandType.GET_NEXT_EVENT, p("다음 일정 언제야?")?.type)
        assertEquals("치과", p("치과 일정 찾아줘")?.param("query"))
        val c = p("금요일 오후 4시에 코딩 일정 추가해줘")!!
        assertEquals(CommandType.CREATE_EVENT_REQUEST, c.type)
        assertEquals("코딩", c.param("title")); assertEquals("2026-10-09", c.param("date")); assertEquals("16", c.param("hour"))
        assertNull("no title -> let the AI ask", p("금요일 오후 4시에 일정 추가해줘"))
    }

    // ---- calendar commands ----------------------------------------------------------------------------------

    private val events = listOf(
        ev("코딩", 2026, 10, 6, 16), ev("Team sync", 2026, 10, 6, 10, 30), ev("점심", 2026, 10, 7, 12), ev("Dentist", 2026, 10, 9, 15),
    )
    private fun cal(c: FakeCalendar = FakeCalendar(events), ctx: ContextEngine = ContextEngine(), ui: (String) -> Boolean = { true }) = Triple(
        CommandRouter(CalendarExecutors(c, ctx, { t, _, _ -> ui(t) }, { now }).all()), c, ctx,
    )

    @Test fun todayEventsSummary() = runTest {
        val (r, _, ctx) = cal()
        val res = r.execute(Command(CommandType.GET_TODAY_EVENTS))
        assertEquals("You have two events today. The first is at ten thirty AM.", res.speech)
        assertTrue(res.subtitle.contains("오늘 일정이 2개") && res.subtitle.contains("Team sync"))
        assertEquals(2, ctx.events().size)
        assertNull("calendar details must not go to the AI", res.data)
        assertNotNull(res.card)
    }

    @Test fun tomorrowNextSearchAndEmpty() = runTest {
        val (r, _, _) = cal()
        assertTrue(r.execute(Command(CommandType.GET_TOMORROW_EVENTS)).speech.contains("one event tomorrow"))
        val next = r.execute(Command(CommandType.GET_NEXT_EVENT))
        assertTrue(next.speech.contains("today at ten thirty AM"))
        val s = r.execute(Command(CommandType.SEARCH_EVENTS, mapOf("query" to "dent")))
        assertTrue(s.speech.contains("Friday at three PM"))
        assertTrue(r.execute(Command(CommandType.SEARCH_EVENTS, mapOf("query" to "zzz"))).speech.contains("didn't find"))
        val empty = cal(FakeCalendar()).first.execute(Command(CommandType.GET_TODAY_EVENTS))
        assertEquals("You have no events today.", empty.speech); assertEquals("오늘은 일정이 없습니다.", empty.subtitle)
    }

    @Test fun createAsksFirstAndWritesOnlyAfterConfirmation() = runTest {
        val c = FakeCalendar(events, write = true)
        val (r, _, _) = cal(c)
        val ask = r.execute(Command(CommandType.CREATE_EVENT_REQUEST, mapOf("title" to "coding", "date" to "2026-10-09", "hour" to "16", "minute" to "0")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, ask.status)
        assertEquals("Would you like me to add coding to your calendar for Friday at four PM?", ask.speech)
        assertEquals("금요일 오후 4시에 ‘coding’ 일정을 추가할까요?", ask.subtitle)
        assertTrue("nothing written yet", c.inserted.isEmpty())
        val done = r.execute(ask.pending!!, confirmed = true)
        assertEquals("Done.", done.speech)
        assertEquals(1, c.inserted.size)
        assertEquals(LocalDateTime.of(2026, 10, 9, 16, 0), c.inserted[0].second)
        assertEquals(LocalDateTime.of(2026, 10, 9, 17, 0), c.inserted[0].third)
    }

    @Test fun koreanTitleIsNotMangledByTheEnglishVoice() = runTest {
        val ask = cal().first.execute(Command(CommandType.CREATE_EVENT_REQUEST, mapOf("title" to "코딩", "date" to "2026-10-09", "hour" to "16")))
        assertFalse(ask.speech.any { it in '가'..'힣' })
        assertTrue(ask.subtitle.contains("‘코딩’"))
    }

    @Test fun withoutWritePermissionTheCalendarAppOpensPrefilled() = runTest {
        var opened: String? = null
        val c = FakeCalendar(events, write = false)
        val (r, _, _) = cal(c, ui = { opened = it; true })
        val ask = r.execute(Command(CommandType.CREATE_EVENT_REQUEST, mapOf("title" to "Gym", "date" to "2026-10-08", "hour" to "7")))
        val res = r.execute(ask.pending!!, confirmed = true)
        assertEquals("Gym", opened)
        assertTrue(c.inserted.isEmpty())
        assertTrue(res.speech.contains("Tap save"))
    }

    @Test fun createWithMissingPartsAsks() = runTest {
        val (r, _, _) = cal()
        assertEquals(ResultStatus.FAILED, r.execute(Command(CommandType.CREATE_EVENT_REQUEST, mapOf("title" to "x"))).status)
        assertEquals(ResultStatus.FAILED, r.execute(Command(CommandType.CREATE_EVENT_REQUEST)).status)
    }

    @Test fun readNeedsCalendarPermissionBeforeAnythingHappens() = runTest {
        val c = FakeCalendar(events)
        val router = CommandRouter(CalendarExecutors(c, ContextEngine(), { _, _, _ -> true }, { now }).all(), FakeAccess())
        val r = router.execute(Command(CommandType.GET_TODAY_EVENTS))
        assertEquals(ResultStatus.NEEDS_PERMISSION, r.status)
        assertTrue(r.subtitle.contains("캘린더"))
    }

    // ---- context follow-ups ---------------------------------------------------------------------------------

    @Test fun ordinalFollowUpAnswersFromContext() = runTest {
        val (r, _, ctx) = cal()
        r.execute(Command(CommandType.GET_TODAY_EVENTS))
        val first = FollowUpResolver.resolve("첫 번째 일정 몇 시야?", ctx)!!
        assertEquals("Number one is at ten thirty AM.", first.speech)
        assertTrue(first.subtitle.contains("오전 10시 30분") && first.subtitle.contains("Team sync"))
        assertTrue(FollowUpResolver.resolve("두번째 일정은?", ctx)!!.subtitle.contains("코딩"))
        assertTrue(FollowUpResolver.resolve("마지막 일정 몇 시", ctx)!!.speech.startsWith("The last one"))
        assertTrue(FollowUpResolver.resolve("세 번째 일정", ctx)!!.speech.contains("only"))
        assertNull(FollowUpResolver.resolve("오늘 날씨", ctx))
        assertNull("no list was just given", FollowUpResolver.resolve("첫 번째 일정", ContextEngine()))
    }

    @Test fun contextEngineIsSmallAndExpires() {
        var t = 0L
        val ctx = ContextEngine({ t }, ttlMs = 1_000, maxFacts = 2)
        ctx.remember("weather", "rain 70%"); ctx.remember("battery", "72%"); ctx.remember("time", "9am")
        assertEquals(2, ctx.size)
        assertTrue(ctx.promptBlock()!!.contains("battery") && !ctx.promptBlock()!!.contains("weather"))
        ctx.remember("battery", "71%")
        assertEquals(2, ctx.size)
        t = 2_000
        assertEquals(0, ctx.size); assertNull(ctx.promptBlock())
        ctx.rememberEvents(listOf(ev("x", 2026, 1, 1, 1))); t = 4_000
        assertTrue(ctx.events().isEmpty())
        ctx.remember("a", "x".repeat(1000)); assertEquals(300, ctx.facts().single().summary.length)
    }

    // ---- briefs ---------------------------------------------------------------------------------------------

    private val weather = WeatherReport("Seoul", 14.2, 12.0, "partly cloudy", DayForecast(18.0, 9.0, 60, "rain"), DayForecast(20.0, 10.0, 10, "clear sky"))

    private fun brief(
        w: (suspend () -> WeatherReport?) = { weather }, c: FakeCalendar? = FakeCalendar(events), n: FakeNotifications? = FakeNotifications(items = listOf(notif("a", "Gmail", "t", "x", 1), notif("b", "Gmail", "t", "y", 2))),
        d: FakeDevice? = FakeDevice(FakeDevice.defaultStatus.copy(batteryPercent = 82)), at: LocalDateTime = now,
    ) = BriefingComposer({ at }, w, c, n, d, perSourceTimeoutMs = 1_000)

    @Test fun morningBriefAggregatesEverythingAvailable() = runTest {
        val r = brief().morning()
        assertEquals(
            "Good morning. It's 7:32 AM. The temperature is fourteen degrees with a good chance of rain today. " +
                "You have two events today, the first at ten thirty AM. Your battery is at eighty-two percent. You have two recent notifications.",
            r.speech,
        )
        assertTrue(r.subtitle.contains("좋은 아침입니다") && r.subtitle.contains("기온은 14°C") && r.subtitle.contains("배터리는 82%") && r.subtitle.contains("알림이 2개"))
        assertNotNull(r.card)
    }

    @Test fun missingSourcesAreLeftOutNotInvented() = runTest {
        val r = brief(w = { null }, c = FakeCalendar(read = false), n = FakeNotifications(enabled = false), d = null).morning()
        assertEquals("Good morning. It's 7:32 AM.", r.speech)
        val boom = brief(w = { error("offline") }, c = null, n = null).morning()
        assertFalse(boom.speech.contains("temperature"))
        assertFalse(boom.speech.contains("event"))
        assertTrue(boom.speech.contains("battery"))
    }

    @Test fun slowSourceIsSkippedAfterItsTimeout() = runTest {
        val r = brief(w = { kotlinx.coroutines.delay(60_000); weather }).morning()
        assertFalse(r.speech.contains("temperature"))
        assertTrue(r.speech.contains("Your battery"))
    }

    @Test fun eveningBriefCoversRemainingEventsTomorrowAndWeather() = runTest {
        val r = brief(at = LocalDateTime.of(2026, 10, 6, 19, 0), c = FakeCalendar(events + ev("야식", 2026, 10, 6, 21))).evening()
        assertTrue(r.speech.contains("You have one event left today, the first at nine PM."))
        assertTrue(r.speech.contains("Tomorrow your first event is at twelve PM."))
        assertTrue(r.speech.contains("Tomorrow looks like clear sky, high twenty."))
        assertTrue(r.subtitle.contains("내일 첫 일정은 오후 12시 점심"))
    }

    @Test fun greetingFollowsTheClock() = runTest {
        assertTrue(brief(at = LocalDateTime.of(2026, 10, 6, 14, 5)).morning().speech.startsWith("Good afternoon."))
        assertTrue(brief(at = LocalDateTime.of(2026, 10, 6, 21, 5)).morning().speech.startsWith("Good evening."))
    }

    @Test fun briefCommandsAreRoutedAndLocal() {
        assertEquals(CommandType.MORNING_BRIEF, LocalIntentParser.parse("FRIDAY 오늘 브리핑", now)?.type)
        assertEquals(CommandType.MORNING_BRIEF, LocalIntentParser.parse("오늘 브리핑 해줘", now)?.type)
        assertEquals(CommandType.EVENING_BRIEF, LocalIntentParser.parse("오늘 정리해줘", now)?.type)
        assertEquals(CommandType.EVENING_BRIEF, LocalIntentParser.parse("저녁 브리핑", now)?.type)
    }
}
