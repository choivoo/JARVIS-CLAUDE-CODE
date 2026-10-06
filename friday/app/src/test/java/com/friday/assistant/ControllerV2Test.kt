package com.friday.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.ai.AIProvider
import com.friday.assistant.ai.AiException
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.brief.BriefingComposer
import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.core.ContextEngine
import com.friday.assistant.core.CoreState
import com.friday.assistant.core.FridayController
import com.friday.assistant.core.SubtitleState
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.data.FridayDatabase
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.stt.SttResult
import com.friday.assistant.voice.Emphasis
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delays before answering, so latency and timeouts can be measured on the virtual clock. */
class SlowAi(private val firstDelays: List<Long>, private val answer: String) : AIProvider {
    override val name = "slow"
    var calls = 0
    override suspend fun complete(messages: List<ChatMessage>): String {
        val d = firstDelays.getOrElse(calls) { 0L }
        calls++
        delay(d)
        return answer
    }
}

/** Voice-assistant behaviour added in the second pass: follow-ups, context, barge-in, retries, latency, subtitles. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControllerV2Test {
    private lateinit var db: FridayDatabase
    private lateinit var repo: ConversationRepository
    private val noon = LocalDateTime.of(2026, 10, 6, 12, 0)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FridayDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        repo = ConversationRepository(db.dao())
    }

    @After fun tearDown() = db.close()

    private class Rig(val c: FridayController, val speaker: FakeSpeaker, val stt: FakeStt, val subs: MutableList<SubtitleState>, val cores: MutableList<CoreState>, val ctx: ContextEngine)

    private fun TestScope.rig(
        stt: FakeStt, ai: AIProvider, router: CommandRouter = routerWith(), online: Boolean = true, speaker: FakeSpeaker = FakeSpeaker(),
        settings: FridaySettings = FridaySettings(), ctx: ContextEngine = ContextEngine(), aiTimeoutMs: Long = 25_000,
    ): Rig {
        val c = FridayController(
            this, { settings }, stt, { ai }, router, speaker, repo, { online }, { noon },
            contextEngine = ctx, clockMs = { testScheduler.currentTime }, aiTimeoutMs = aiTimeoutMs, aiRetryDelayMs = 100,
        )
        val subs = mutableListOf<SubtitleState>(); val cores = mutableListOf<CoreState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.subtitle.collect { subs += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.core.collect { cores += it } }
        return Rig(c, speaker, stt, subs, cores, ctx)
    }

    private fun json(speech: String, subtitle: String, action: String = "null") = """{"speech":"$speech","subtitle":"$subtitle","action":$action}"""

    // ---- follow-up conversation -----------------------------------------------------------------------------

    @Test fun followUpContinuesWithoutSayingFridayAgain() = runTest {
        val ai = FakeAi(json("Hello.", "안녕하세요."), json("You're welcome.", "천만에요."))
        val r = rig(FakeStt(SttResult.Text("안녕"), SttResult.Text("고마워")), ai)
        r.c.startListening(); advanceUntilIdle()
        assertEquals(listOf("Hello.", "You're welcome."), r.speaker.spoken)
        assertEquals("first listen uses the default timeout, follow-ups use the window", listOf(null, 8_000L, 8_000L), r.stt.timeouts)
        assertFalse("silence in the follow-up window is not an error", r.cores.contains(CoreState.ERROR))
        assertEquals(CoreState.IDLE, r.c.core.value)
        assertNull("nothing complains about not hearing anything", r.subs.firstOrNull { it.subtitle.contains("듣지") || it.subtitle.contains("들리지") })
    }

    @Test fun followUpCanBeSwitchedOffAndTimedOut() = runTest {
        val off = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hi.", "안녕.")), settings = FridaySettings(followUpEnabled = false))
        off.c.startListening(); advanceUntilIdle()
        assertEquals(listOf<Long?>(null), off.stt.timeouts)

        val five = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hi.", "안녕.")), settings = FridaySettings(followUpTimeoutSec = 5))
        five.c.startListening(); advanceUntilIdle()
        assertEquals(5_000L, five.stt.timeouts[1])
    }

    @Test fun noFollowUpAfterAnErrorOrOfflineAnswer() = runTest {
        val err = rig(FakeStt(SttResult.Text("농담")), FakeAi(AiException.RateLimited()))
        err.c.startListening(); advanceUntilIdle()
        assertEquals(1, err.stt.timeouts.size)
        val off = rig(FakeStt(SttResult.Text("원신 소식")), FakeAi(json("x", "y")), online = false)
        off.c.startListening(); advanceUntilIdle()
        assertEquals(1, off.stt.timeouts.size)
    }

    // ---- context --------------------------------------------------------------------------------------------

    @Test fun previousAnswerContextReachesTheAiForTheNextQuestion() = runTest {
        val weather = RecordingExecutor { _, _ -> CommandResult.ok("fb", "대체", data = "place=Seoul; now=18C; today rain_chance=70%") }
        val ai = FakeAi(
            json("Checking.", "확인.", """{"type":"WEATHER","day":"today"}"""),
            json("Seventy percent chance of rain.", "비 올 확률 70%입니다."),
            json("Yes, take an umbrella.", "네, 우산을 챙기세요."),
        )
        val r = rig(FakeStt(SttResult.Text("오늘 날씨 알려줘"), SttResult.Text("그럼 우산 필요할까?")), ai, routerWith(CommandType.WEATHER to weather))
        r.c.startListening(); advanceUntilIdle()
        assertEquals(3, ai.prompts.size)
        val system = ai.prompts[2].first().content
        assertTrue(system.contains("Recent context"))
        assertTrue(system.contains("weather: place=Seoul"))
        assertEquals("the question is in the history too", "그럼 우산 필요할까?", ai.prompts[2].last { it.role == com.friday.assistant.ai.Role.USER }.content)
        assertEquals("Yes, take an umbrella.", r.speaker.spoken.last())
        assertTrue(r.c.contextSize.value >= 1)
    }

    @Test fun contextNeverCarriesPrivateData() = runTest {
        val cal = FakeCalendar(listOf(ev("비밀 병원 예약", 2026, 10, 6, 15)))
        val ctx = ContextEngine()
        val router = CommandRouter(CalendarExecutors(cal, ctx, { _, _, _ -> true }, { noon }).all())
        val ai = FakeAi(json("Fine.", "좋아요."))
        val r = rig(FakeStt(SttResult.Text("오늘 일정 알려줘"), SttResult.Text("고마워")), ai, router, ctx = ctx)
        r.c.startListening(); advanceUntilIdle()
        val everything = ai.prompts.flatten().joinToString { it.content }
        assertFalse("event titles must stay on the device", everything.contains("비밀 병원"))
        assertTrue(ctx.facts().any { it.summary.contains("1 events today") })
    }

    @Test fun ordinalFollowUpAfterEventsIsAnsweredLocallyWithoutAi() = runTest {
        val cal = FakeCalendar(listOf(ev("Team sync", 2026, 10, 6, 10, 30), ev("코딩", 2026, 10, 6, 16)))
        val ctx = ContextEngine()
        val router = CommandRouter(CalendarExecutors(cal, ctx, { _, _, _ -> true }, { noon }).all())
        val ai = FakeAi(json("never", "never"))
        val r = rig(FakeStt(SttResult.Text("오늘 일정 알려줘"), SttResult.Text("첫 번째 일정 몇 시야?")), ai, router, ctx = ctx)
        r.c.startListening(); advanceUntilIdle()
        assertEquals("You have two events today. The first is at ten thirty AM.", r.speaker.spoken[0])
        assertEquals("Number one is at ten thirty AM.", r.speaker.spoken[1])
        assertTrue("no AI call was needed", ai.prompts.isEmpty())
        assertNotNull(r.subs.firstOrNull { it.subtitle.contains("Team sync") })
    }

    // ---- barge-in / stop ------------------------------------------------------------------------------------

    @Test fun sayingFridayWhileSpeakingInterruptsAndListens() = runTest {
        val speaker = FakeSpeaker().also { it.gate = CompletableDeferred() }
        val stt = FakeStt(SttResult.Text("날씨 알려줘")).also { }
        val r = rig(stt, FakeAi(json("The weather this afternoon is expected to be sunny.", "오늘 오후는 맑겠습니다.")), speaker = speaker)
        r.c.startListening(); runCurrent()
        assertEquals(CoreState.SPEAKING, r.c.core.value)
        val stopsBefore = speaker.stops
        speaker.gate = null
        stt.gate = CompletableDeferred()
        r.c.onWake("")
        runCurrent()
        assertTrue("current speech is silenced at once", speaker.stops > stopsBefore)
        assertEquals("Yes?", speaker.spoken.last())
        assertEquals(CoreState.LISTENING, r.c.core.value)
        r.c.stop()
    }

    @Test fun ownVoiceSayingFridayIsNotACall() = runTest {
        val speaker = FakeSpeaker().also { it.gate = CompletableDeferred() }
        val r = rig(FakeStt(SttResult.Text("회의 언제야")), FakeAi(json("Your meeting is on Friday at four PM.", "회의는 금요일 오후 4시입니다.")), speaker = speaker)
        assertFalse(r.c.shouldIgnoreWake())
        r.c.startListening(); runCurrent()
        assertTrue("it is saying 'Friday' itself", r.c.shouldIgnoreWake())
        r.c.stop()
        assertFalse(r.c.shouldIgnoreWake())
        val calm = FakeSpeaker().also { it.gate = CompletableDeferred() }
        val r2 = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello there.", "안녕하세요.")), speaker = calm)
        r2.c.startListening(); runCurrent()
        assertFalse(r2.c.shouldIgnoreWake())
        r2.c.stop()
    }

    @Test fun stopPhraseSilencesWithoutAiOrSpeech() = runTest {
        val ai = FakeAi(json("never", "never"))
        val r = rig(FakeStt(SttResult.Text("그만")), ai)
        r.c.startListening(); advanceUntilIdle()
        assertTrue(ai.prompts.isEmpty()); assertTrue(r.speaker.spoken.isEmpty())
        assertEquals(CoreState.IDLE, r.c.core.value)
    }

    // ---- provider failures ----------------------------------------------------------------------------------

    @Test fun slowAiIsRetriedOnceThenAnswers() = runTest {
        val ai = SlowAi(listOf(10_000, 0), json("Here you go.", "여기 있습니다."))
        val r = rig(FakeStt(SttResult.Text("질문")), ai, aiTimeoutMs = 1_000, settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertEquals(2, ai.calls)
        assertEquals(listOf("Here you go."), r.speaker.spoken)
    }

    @Test fun retriesAreBoundedAndOnlyForTransientErrors() = runTest {
        val server = FakeAi(AiException.Server(503))
        val r1 = rig(FakeStt(SttResult.Text("질문")), server)
        r1.c.startListening(); advanceUntilIdle()
        assertEquals("one retry, never an endless loop", 2, server.prompts.size)
        assertTrue(r1.speaker.spoken.single().contains("problem"))

        val badKey = FakeAi(AiException.InvalidKey())
        val r2 = rig(FakeStt(SttResult.Text("질문")), badKey)
        r2.c.startListening(); advanceUntilIdle()
        assertEquals("a rejected key is not retried", 1, badKey.prompts.size)
        assertTrue(r2.speaker.spoken.single().contains("API key"))

        val limited = FakeAi(AiException.RateLimited())
        rig(FakeStt(SttResult.Text("질문")), limited).also { it.c.startListening(); advanceUntilIdle() }
        assertEquals(1, limited.prompts.size)

        val alwaysSlow = SlowAi(listOf(10_000, 10_000, 10_000), json("x", "y"))
        val r4 = rig(FakeStt(SttResult.Text("질문")), alwaysSlow, aiTimeoutMs = 1_000)
        r4.c.startListening(); advanceUntilIdle()
        assertEquals(2, alwaysSlow.calls)
        assertTrue(r4.speaker.spoken.single().contains("too long"))
    }

    @Test fun errorsAreSpokenWithWarningEmphasis() = runTest {
        val r = rig(FakeStt(SttResult.Text("질문")), FakeAi(AiException.NoInternet()))
        r.c.startListening(); advanceUntilIdle()
        assertEquals(listOf(Emphasis.WARNING), r.speaker.emphases)
        val ok = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hi.", "안녕.")), settings = FridaySettings(followUpEnabled = false))
        ok.c.startListening(); advanceUntilIdle()
        assertEquals(listOf(Emphasis.NORMAL), ok.speaker.emphases)
    }

    @Test fun permissionDenialIsExplainedInBothLanguages() = runTest {
        val router = CommandRouter(CalendarExecutors(FakeCalendar(), ContextEngine(), { _, _, _ -> true }, { noon }).all(), FakeAccess())
        val r = rig(FakeStt(SttResult.Text("오늘 일정 알려줘")), FakeAi(json("never", "never")), router)
        r.c.startListening(); advanceUntilIdle()
        assertEquals("I need calendar access for that. You can allow it in the Permission Center.", r.speaker.spoken.single())
        assertTrue(r.subs.any { it.subtitle.contains("캘린더 권한이 필요합니다") })
    }

    @Test fun malformedJsonStillGetsAnAnswer() = runTest {
        val r = rig(FakeStt(SttResult.Text("질문")), FakeAi("{\"speech\": \"cut o"), settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertEquals(1, r.speaker.spoken.size)
    }

    // ---- latency --------------------------------------------------------------------------------------------

    @Test fun latencyReportsOnlyWhatWasMeasured() = runTest {
        val ai = SlowAi(listOf(300), json("Hi.", "안녕."))
        val r = rig(FakeStt(SttResult.Text("질문")), ai, settings = FridaySettings(followUpEnabled = false))
        assertNull(r.c.latency.value)
        r.c.startListening(); advanceUntilIdle()
        val l = r.c.latency.value!!
        assertEquals(300L, l.ai)
        assertEquals(300L, l.total)
        assertNull("tap, not wake word", l.wake)
        assertEquals(0L, l.ttsFirstAudio)

        val typed = rig(FakeStt(), SlowAi(listOf(120), json("Hi.", "안녕.")), settings = FridaySettings(followUpEnabled = false))
        typed.c.submitText("안녕"); advanceUntilIdle()
        val t = typed.c.latency.value!!
        assertNull("typed input has no STT", t.stt)
        assertEquals(120L, t.ai)
    }

    @Test fun localCommandsHaveNoAiLatency() = runTest {
        val time = RecordingExecutor { _, _ -> CommandResult.ok("It's 12:00 PM.", "정오입니다.") }
        val r = rig(FakeStt(SttResult.Text("지금 몇 시야")), FakeAi(json("x", "y")), routerWith(CommandType.GET_TIME to time), settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertNull(r.c.latency.value!!.ai)
        assertNotNull(r.c.latency.value!!.command)
    }

    // ---- subtitle timing ------------------------------------------------------------------------------------

    @Test fun subtitleStaysBrieflyAfterSpeechThenDisappears() = runTest {
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello.", "안녕하세요.")), settings = FridaySettings(followUpEnabled = false, subtitleDurationMs = 2_500))
        r.c.startListening(); runCurrent()
        assertEquals("안녕하세요.", r.c.subtitle.value.subtitle)
        assertFalse("speech is over", r.c.subtitle.value.speaking)
        advanceTimeBy(2_400); runCurrent()
        assertEquals("안녕하세요.", r.c.subtitle.value.subtitle)
        advanceTimeBy(200); runCurrent()
        assertEquals("", r.c.subtitle.value.subtitle)
    }

    @Test fun alwaysShowSubtitleKeepsTheLastLine() = runTest {
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello.", "안녕하세요.")), settings = FridaySettings(followUpEnabled = false, alwaysShowSubtitle = true))
        r.c.startListening(); advanceUntilIdle()
        assertEquals("안녕하세요.", r.c.subtitle.value.subtitle)
    }

    @Test fun subtitleFollowsEachSentence() = runTest {
        val r = rig(FakeStt(SttResult.Text("일정")), FakeAi(json("You have two events. The first is at four PM.", "일정이 2개 있습니다. 첫 일정은 오후 4시입니다.")), settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertEquals(listOf("일정이 2개 있습니다.", "첫 일정은 오후 4시입니다."), r.subs.filter { it.speaking }.map { it.subtitle }.distinct())
    }

    // ---- cards, briefs, typed input -------------------------------------------------------------------------

    @Test fun cardsAppearForResultsAndFadeAway() = runTest {
        val cal = FakeCalendar(listOf(ev("Team sync", 2026, 10, 6, 10, 30)))
        val router = CommandRouter(CalendarExecutors(cal, ContextEngine(), { _, _, _ -> true }, { noon }).all())
        val r = rig(FakeStt(SttResult.Text("오늘 일정 알려줘")), FakeAi(json("x", "y")), router, settings = FridaySettings(followUpEnabled = false))
        assertNull(r.c.card.value)
        r.c.startListening(); runCurrent()
        assertNotNull(r.c.card.value)
        assertEquals(com.friday.assistant.command.CardKind.CALENDAR, r.c.card.value!!.kind)
        advanceTimeBy(13_000); runCurrent()
        assertNull(r.c.card.value)
    }

    @Test fun briefingByVoiceIsLocalAndNeedsNoAi() = runTest {
        val composer = BriefingComposer({ noon }, { null }, FakeCalendar(listOf(ev("회의", 2026, 10, 6, 15))), FakeNotifications(), FakeDevice())
        val ai = FakeAi(json("never", "never"))
        val r = rig(FakeStt(SttResult.Text("오늘 브리핑")), ai, CommandRouter(composer.executors()), settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertTrue(ai.prompts.isEmpty())
        assertTrue(r.speaker.spoken.single().startsWith("Good afternoon. It's 12:00 PM."))
    }

    @Test fun typedTextSkipsTheMicrophone() = runTest {
        val time = RecordingExecutor { _, _ -> CommandResult.ok("It's 12:00 PM.", "정오입니다.") }
        val r = rig(FakeStt(), FakeAi(json("x", "y")), routerWith(CommandType.GET_TIME to time), settings = FridaySettings(followUpEnabled = false))
        r.c.submitText("지금 몇 시야"); advanceUntilIdle()
        assertTrue(r.stt.timeouts.isEmpty())
        assertEquals(listOf("It's 12:00 PM."), r.speaker.spoken)
    }

    @Test fun unsupportedAiActionIsRejectedWithoutTouchingTheDevice() = runTest {
        val risky = RecordingConfirmable({ CommandResult.ok("x", "x") }, { CommandResult.ok("x", "x") })
        val ai = FakeAi(json("Wiping.", "삭제합니다.", """{"type":"WIPE_PHONE"}"""))
        val r = rig(FakeStt(SttResult.Text("폰 지워")), ai, routerWith(CommandType.CALL_CONTACT_REQUEST to risky), settings = FridaySettings(followUpEnabled = false))
        r.c.startListening(); advanceUntilIdle()
        assertEquals("I can't do that yet.", r.speaker.spoken.single())
        assertTrue(risky.calls.isEmpty())
    }
}
