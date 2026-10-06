package com.friday.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.ai.AiException
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.core.CoreState
import com.friday.assistant.core.FridayController
import com.friday.assistant.core.SubtitleState
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.data.FridayDatabase
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.stt.SttError
import com.friday.assistant.stt.SttResult
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end pipeline with fake STT / AI / TTS / device and a real Room database, no network:
 * Korean speech -> STT -> AI -> Command -> Result -> English TTS + Korean subtitle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineIntegrationTest {
    private lateinit var db: FridayDatabase
    private lateinit var repo: ConversationRepository
    private val noon = LocalDateTime.of(2026, 10, 6, 12, 0)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FridayDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        repo = ConversationRepository(db.dao())
    }

    @After fun tearDown() = db.close()

    private class Rig(val controller: FridayController, val speaker: FakeSpeaker, val subtitles: MutableList<SubtitleState>, val cores: MutableList<CoreState>)

    private fun TestScope.rig(
        stt: FakeStt, ai: FakeAi, router: com.friday.assistant.command.CommandRouter, online: Boolean = true,
        speaker: FakeSpeaker = FakeSpeaker(), settings: FridaySettings = FridaySettings(),
    ): Rig {
        val c = FridayController(this, { settings }, stt, { ai }, router, speaker, repo, { online }, { noon })
        val subs = mutableListOf<SubtitleState>()
        val cores = mutableListOf<CoreState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.subtitle.collect { subs += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.core.collect { cores += it } }
        return Rig(c, speaker, subs, cores)
    }

    private fun json(speech: String, subtitle: String, action: String = "null") =
        """{"speech":"$speech","subtitle":"$subtitle","action":$action}"""

    @Test fun koreanSpeechToAiToCommandToEnglishVoiceAndKoreanSubtitle() = runTest {
        val weather = RecordingExecutor { _, _ -> CommandResult.ok("fallback", "대체", data = "place=Seoul; now=18C; today rain_chance=70%") }
        val ai = FakeAi(
            json("Let me check.", "확인해 볼게요.", """{"type":"WEATHER","day":"today"}"""),
            json("Yes, bring an umbrella. Seventy percent chance of rain.", "네, 우산을 챙기세요. 비 올 확률 70%입니다."),
        )
        val r = rig(FakeStt(SttResult.Text("오늘 비 올 것 같아?")), ai, routerWith(CommandType.WEATHER to weather))
        r.controller.startListening()
        advanceUntilIdle()

        assertEquals(1, weather.calls.size)
        assertEquals(2, ai.prompts.size)
        assertTrue("tool result must reach the AI", ai.prompts[1].last().content.contains("rain_chance=70%"))
        assertEquals(listOf("Yes, bring an umbrella. Seventy percent chance of rain."), r.speaker.spoken)
        // the Korean subtitle follows the English voice sentence by sentence
        val spoken = r.subtitles.filter { it.speaking }.map { it.subtitle }.distinct()
        assertEquals(listOf("네, 우산을 챙기세요.", "비 올 확률 70%입니다."), spoken)
        assertEquals("오늘 비 올 것 같아?", r.subtitles.first { it.user.isNotEmpty() }.user)
        assertTrue(r.cores.containsAll(listOf(CoreState.LISTENING, CoreState.THINKING, CoreState.EXECUTING, CoreState.SPEAKING)))
        assertEquals(CoreState.IDLE, r.controller.core.value)
        val stored = repo.messages.first()
        assertEquals(listOf("user", "assistant"), stored.map { it.role })
    }

    @Test fun ttsStartsSubtitleVisibleTtsEndsSubtitleHidden() = runTest {
        val speaker = FakeSpeaker().also { it.gate = CompletableDeferred() }
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello there.", "안녕하세요.")), routerWith(), speaker = speaker)
        r.controller.startListening()
        runCurrent()
        assertEquals(CoreState.SPEAKING, r.controller.core.value)
        assertEquals("안녕하세요.", r.controller.subtitle.value.subtitle)
        assertTrue(r.controller.subtitle.value.speaking)
        speaker.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("", r.controller.subtitle.value.subtitle)
        assertFalse(r.controller.subtitle.value.speaking)
    }

    @Test fun wakeWordLeadsToListening() = runTest {
        val stt = FakeStt(SttResult.Text("시간 알려줘")).also { it.gate = CompletableDeferred() }
        val r = rig(stt, FakeAi(json("x", "y")), routerWith())
        r.controller.onWake("")
        runCurrent()
        assertEquals(listOf("Yes?"), r.speaker.spoken)
        assertTrue(r.subtitles.any { it.subtitle == "네, 말씀하세요." })
        assertEquals(CoreState.LISTENING, r.controller.core.value)
        r.controller.stop() // release the held microphone so the test scope can finish
    }

    @Test fun wakeWordWithTrailingCommandSkipsListening() = runTest {
        val time = RecordingExecutor { _, _ -> CommandResult.ok("It's 12:00 PM.", "현재 시각은 오후 12시 0분입니다.") }
        val r = rig(FakeStt(SttResult.Empty), FakeAi(json("x", "y")), routerWith(CommandType.GET_TIME to time))
        r.controller.onWake("지금 몇 시야")
        advanceUntilIdle()
        assertEquals(1, time.calls.size)
        assertEquals(listOf("It's 12:00 PM."), r.speaker.spoken)
    }

    @Test fun aiFailureFallsBackToSpokenExplanation() = runTest {
        val r = rig(FakeStt(SttResult.Text("오늘 뉴스 알려줘")), FakeAi(AiException.RateLimited()), routerWith())
        r.controller.startListening()
        runCurrent()
        assertEquals(CoreState.ERROR, r.controller.core.value)
        advanceUntilIdle()
        assertTrue(r.speaker.spoken.single().contains("rate limited"))
        assertTrue(r.subtitles.any { it.subtitle.contains("한도") })
        assertEquals(CoreState.IDLE, r.controller.core.value)
    }

    @Test fun missingAiConfigurationIsExplained() = runTest {
        val r = rig(FakeStt(SttResult.Text("농담 해줘")), FakeAi(AiException.NotConfigured()), routerWith())
        r.controller.startListening(); advanceUntilIdle()
        assertTrue(r.speaker.spoken.single().contains("isn't configured"))
    }

    @Test fun offlineStillRunsLocalCommands() = runTest {
        val battery = RecordingExecutor { _, _ -> CommandResult.ok("Your battery is currently at seventy-two percent.", "현재 배터리는 72%입니다.") }
        val ai = FakeAi(json("never", "never"))
        val r = rig(FakeStt(SttResult.Text("배터리 얼마나 남았어?")), ai, routerWith(CommandType.GET_BATTERY to battery), online = false)
        r.controller.startListening(); advanceUntilIdle()
        assertEquals(1, battery.calls.size)
        assertTrue("AI must not be called offline", ai.prompts.isEmpty())
        assertEquals("Your battery is currently at seventy-two percent.", r.speaker.spoken.single())
        assertTrue(r.subtitles.any { it.subtitle == "현재 배터리는 72%입니다." })
    }

    @Test fun offlineNonLocalRequestIsDeclined() = runTest {
        val ai = FakeAi(json("never", "never"))
        val r = rig(FakeStt(SttResult.Text("원신 최신 소식 알려줘")), ai, routerWith(), online = false)
        r.controller.startListening(); advanceUntilIdle()
        assertTrue(ai.prompts.isEmpty())
        assertTrue(r.speaker.spoken.single().contains("offline"))
        assertTrue(r.cores.contains(CoreState.OFFLINE))
    }

    @Test fun unsupportedCommandFromAiIsRejected() = runTest {
        val exec = RecordingExecutor { _, _ -> CommandResult.ok("no", "no") }
        val ai = FakeAi(json("Formatting.", "포맷합니다.", """{"type":"FORMAT_DISK"}"""))
        val r = rig(FakeStt(SttResult.Text("폰 초기화해줘")), ai, routerWith(CommandType.GET_TIME to exec))
        r.controller.startListening(); advanceUntilIdle()
        assertTrue(exec.calls.isEmpty())
        assertEquals("I can't do that yet.", r.speaker.spoken.single())
    }

    @Test fun failedCommandReportsTruthNotTheAiOptimism() = runTest {
        val open = RecordingExecutor { _, _ -> CommandResult.failed("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.") }
        val ai = FakeAi(json("Opening it now!", "실행합니다!", """{"type":"OPEN_APP","target":"없는앱"}"""))
        val r = rig(FakeStt(SttResult.Text("없는앱 켜줘")), ai, routerWith(CommandType.OPEN_APP to open))
        r.controller.startListening(); advanceUntilIdle()
        assertEquals("I couldn't find that application.", r.speaker.spoken.single())
    }

    @Test fun malformedAiJsonStillAnswers() = runTest {
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi("Hello, I am FRIDAY"), routerWith())
        r.controller.startListening(); advanceUntilIdle()
        assertEquals("Hello, I am FRIDAY", r.speaker.spoken.single())
    }

    @Test fun callRequiresConfirmationThenExecutes() = runTest {
        val call = RecordingConfirmable(
            { c -> CommandResult.confirm("Would you like me to call Mom?", "엄마에게 전화를 걸까요?", c.copy(params = c.params + ("number" to "010"))) },
            { CommandResult.ok("Calling Mom.", "엄마에게 전화합니다.") },
        )
        val ai = FakeAi(json("Sure.", "네.", """{"type":"CALL_CONTACT_REQUEST","name":"엄마"}"""))
        val r = rig(FakeStt(SttResult.Text("엄마한테 전화해줘"), SttResult.Text("응")), ai, routerWith(CommandType.CALL_CONTACT_REQUEST to call))
        r.controller.startListening(); advanceUntilIdle()
        assertEquals(listOf(false, true), call.calls.map { it.second })
        assertEquals("010", call.calls[1].first.param("number"))
        assertEquals(listOf("Would you like me to call Mom?", "Calling Mom."), r.speaker.spoken)
        assertTrue(r.subtitles.any { it.subtitle == "엄마에게 전화를 걸까요?" })
        assertEquals(1, ai.prompts.size)
    }

    @Test fun decliningConfirmationCancels() = runTest {
        val call = RecordingConfirmable({ c -> CommandResult.confirm("Call Mom?", "전화할까요?", c) }, { CommandResult.ok("Calling.", "전화") })
        val ai = FakeAi(json("Sure.", "네.", """{"type":"CALL_CONTACT_REQUEST","name":"엄마"}"""))
        val r = rig(FakeStt(SttResult.Text("엄마한테 전화해줘"), SttResult.Text("아니 취소")), ai, routerWith(CommandType.CALL_CONTACT_REQUEST to call))
        r.controller.startListening(); advanceUntilIdle()
        assertEquals(listOf(false), call.calls.map { it.second })
        assertEquals("Okay, cancelled.", r.speaker.spoken.last())
    }

    @Test fun silenceAndSttErrorsAreHandled() = runTest {
        val r1 = rig(FakeStt(SttResult.Empty), FakeAi(json("x", "y")), routerWith(), speaker = FakeSpeaker())
        r1.controller.startListening(); advanceUntilIdle()
        assertEquals(CoreState.IDLE, r1.controller.core.value)
        assertTrue(r1.cores.contains(CoreState.ERROR))

        val r2 = rig(FakeStt(SttResult.Failure(SttError.NO_PERMISSION)), FakeAi(json("x", "y")), routerWith())
        r2.controller.startListening(); advanceUntilIdle()
        assertEquals(CoreState.IDLE, r2.controller.core.value)
        assertTrue(r2.subtitles.any { it.subtitle.contains("마이크 권한") })
    }

    @Test fun stopSilencesSpeechImmediately() = runTest {
        val speaker = FakeSpeaker().also { it.gate = CompletableDeferred() }
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello.", "안녕.")), routerWith(), speaker = speaker)
        r.controller.startListening(); runCurrent()
        assertEquals(CoreState.SPEAKING, r.controller.core.value)
        r.controller.stop()
        assertTrue(speaker.stops > 0)
        assertEquals(CoreState.IDLE, r.controller.core.value)
        assertEquals("", r.controller.subtitle.value.subtitle)
    }

    @Test fun memoryIsSentToAiAndCanBeDisabled() = runTest {
        val ai = FakeAi(json("First.", "첫째."), json("Second.", "둘째."))
        val r = rig(FakeStt(SttResult.Text("첫 질문"), SttResult.Text("두번째 질문")), ai, routerWith())
        r.controller.startListening(); advanceUntilIdle()
        r.controller.startListening(); advanceUntilIdle()
        assertTrue(ai.prompts[1].any { it.content == "첫 질문" })
        assertTrue(ai.prompts[1].any { it.content == "First." })

        repo.clear()
        val off = rig(FakeStt(SttResult.Text("비밀")), FakeAi(json("Ok.", "응.")), routerWith(), settings = FridaySettings(memoryEnabled = false))
        off.controller.startListening(); advanceUntilIdle()
        assertEquals(0, repo.count())
    }

    @Test fun voiceFeedbackOffStillShowsSubtitle() = runTest {
        val r = rig(FakeStt(SttResult.Text("안녕")), FakeAi(json("Hello.", "안녕하세요.")), routerWith(), settings = FridaySettings(voiceFeedback = false))
        r.controller.startListening(); advanceUntilIdle()
        assertTrue(r.speaker.spoken.isEmpty())
        assertTrue(r.subtitles.any { it.subtitle == "안녕하세요." && !it.speaking })
    }
}
