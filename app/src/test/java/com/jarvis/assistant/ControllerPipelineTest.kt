package com.jarvis.assistant

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.ai.AiConfig
import com.jarvis.assistant.ai.AiError
import com.jarvis.assistant.ai.AiException
import com.jarvis.assistant.ai.ChatTurn
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.command.CommandExecutor
import com.jarvis.assistant.command.CommandRouter
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.core.JarvisController
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.database.Role
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.security.SecureStorage
import com.jarvis.assistant.speech.ListenCallbacks
import com.jarvis.assistant.speech.ListenRequest
import com.jarvis.assistant.speech.SpeechRecognizerEngine
import com.jarvis.assistant.speech.SttError
import com.jarvis.assistant.speech.SttResult
import com.jarvis.assistant.speech.WakeDetection
import com.jarvis.assistant.speech.WakeWordEngine
import com.jarvis.assistant.tts.SpeechOutput
import com.jarvis.assistant.util.AudioLevelBus
import com.jarvis.assistant.util.NetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ArrayBlockingQueue

private class FakeAi(private val handler: (List<ChatTurn>, Int) -> String) : AIProvider {
    override val type = AiProviderType.OPENAI_COMPATIBLE
    val calls = mutableListOf<List<ChatTurn>>()
    override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String {
        calls += turns
        return handler(turns, calls.size)
    }
}

private class FakeSpeaker(var failWith: Exception? = null) : SpeechOutput {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String) {
        failWith?.let { throw it }
        spoken += text
    }
}

private class FakeRecognizer(vararg results: SttResult) : SpeechRecognizerEngine {
    private val queue = ArrayBlockingQueue<SttResult>(16).also { q -> results.forEach { q.add(it) } }
    override fun isAvailable() = true
    override suspend fun listen(request: ListenRequest, callbacks: ListenCallbacks): SttResult =
        queue.poll() ?: SttResult.NoSpeech
}

private class FakeWake(private val detection: WakeDetection) : WakeWordEngine {
    override val name = "fake"
    private var fired = false
    override suspend fun awaitWake(languageTag: String, sensitivity: com.jarvis.assistant.data.model.WakeSensitivity, mode: com.jarvis.assistant.data.model.WakeMode): WakeDetection {
        if (!fired) {
            fired = true
            return detection
        }
        awaitCancellation()
    }
}

private class FakeCommand(
    override val types: List<String>,
    override val executesBeforeSpeech: Boolean = false,
    private val result: ActionResult = ActionResult.ok(),
) : Command {
    val executed = mutableListOf<AiAction>()
    override suspend fun execute(action: AiAction): ActionResult {
        executed += action
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControllerPipelineTest {
    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var bg: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var conversations: ConversationRepository
    private val speaker = FakeSpeaker()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        conversations = ConversationRepository(db.conversationDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun controller(
        ai: AIProvider,
        commands: List<Command> = emptyList(),
        recognizer: SpeechRecognizerEngine = FakeRecognizer(),
        wake: WakeWordEngine = FakeWake(WakeDetection("jarvis", null)),
    ) = JarvisController(
        context = app,
        scope = bg,
        settingsRepo = settings,
        conversations = conversations,
        aiProviders = AiProviderType.values().associateWith { ai },
        recognizer = recognizer,
        wakeEngine = wake,
        speaker = speaker,
        executor = CommandExecutor(CommandRouter(commands)),
        network = NetworkMonitor(app),
        levels = AudioLevelBus(),
    )

    /** Controller and repositories live in backgroundScope so their endless collectors don't block runTest. */
    private fun env(body: suspend TestScope.() -> Unit) = runTest {
        bg = backgroundScope
        settings = SettingsRepository(db.settingsDao(), SecureStorage(app), backgroundScope)
        body()
    }

    /** advanceUntilIdle() ignores background-scope work, so move virtual time past every timer instead. */
    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
    }

    private suspend fun history() = conversations.observeRecent(50).first().reversed()

    @Test
    fun plainReplyIsSpokenSubtitledAndStored() = env {
        val ai = FakeAi { _, _ -> """{"speech":"Good evening, sir.","subtitle":"좋은 저녁입니다.","action":null}""" }
        val c = controller(ai)
        c.submitText("안녕 자비스")
        settle()
        assertEquals(listOf("Good evening, sir."), speaker.spoken)
        val msgs = history()
        assertEquals(listOf(Role.USER, Role.JARVIS), msgs.map { it.role })
        assertEquals("좋은 저녁입니다.", msgs[1].subtitle)
        assertEquals(AssistantPhase.IDLE, c.hud.value.phase)
        assertNull(c.hud.value.subtitle) // faded out after speaking
    }

    @Test
    fun subtitleIsVisibleWhileSpeaking() = env {
        var subtitleDuringSpeech: String? = null
        lateinit var c: JarvisController
        val ai = FakeAi { _, _ -> """{"speech":"Certainly.","subtitle":"물론입니다.","action":null}""" }
        val spy = object : SpeechOutput {
            override suspend fun speak(text: String) {
                subtitleDuringSpeech = c.hud.value.subtitle
                assertEquals(AssistantPhase.SPEAKING, c.hud.value.phase)
            }
        }
        c = JarvisController(
            app, bg, settings, conversations, AiProviderType.values().associateWith { ai },
            FakeRecognizer(), FakeWake(WakeDetection("jarvis", null)), spy,
            CommandExecutor(CommandRouter(emptyList())), NetworkMonitor(app), AudioLevelBus(),
        )
        c.submitText("hi")
        settle()
        assertEquals("물론입니다.", subtitleDuringSpeech)
    }

    @Test
    fun actionIsRoutedToCommand() = env {
        val open = FakeCommand(listOf("OPEN_APP"))
        val ai = FakeAi { _, _ ->
            """{"speech":"Opening YouTube.","subtitle":"유튜브를 엽니다.","action":{"type":"OPEN_APP","package":"com.google.android.youtube"}}"""
        }
        controller(ai, listOf(open)).submitText("유튜브 열어줘")
        settle()
        assertEquals("com.google.android.youtube", open.executed.single().param("package"))
        assertEquals(listOf("Opening YouTube."), speaker.spoken)
    }

    @Test
    fun failedCommandSpeaksItsOwnMessage() = env {
        val open = FakeCommand(
            listOf("OPEN_APP"),
            result = ActionResult.fail("I couldn't find that application.", "해당 앱을 찾을 수 없습니다."),
        )
        val ai = FakeAi { _, _ -> """{"speech":"Opening it.","subtitle":"엽니다.","action":{"type":"OPEN_APP","app":"foo"}}""" }
        controller(ai, listOf(open)).submitText("foo 열어줘")
        settle()
        assertEquals(listOf("Opening it.", "I couldn't find that application."), speaker.spoken)
        assertEquals("해당 앱을 찾을 수 없습니다.", history().last().subtitle)
    }

    @Test
    fun quickCommandSpeaksOnlyItsResult() = env {
        val time = FakeCommand(listOf("GET_TIME"), executesBeforeSpeech = true, result = ActionResult.ok("It's 3:45 PM.", "지금은 오후 3시 45분입니다."))
        val ai = FakeAi { _, _ -> """{"speech":"One moment.","subtitle":"잠시만요.","action":{"type":"GET_TIME"}}""" }
        controller(ai, listOf(time)).submitText("지금 시간 알려줘")
        settle()
        assertEquals(listOf("It's 3:45 PM."), speaker.spoken)
    }

    @Test
    fun unsupportedActionIsRefusedNotExecuted() = env {
        val open = FakeCommand(listOf("OPEN_APP"))
        val ai = FakeAi { _, _ -> """{"speech":"Done.","subtitle":"완료","action":{"type":"RUN_SHELL","cmd":"rm -rf /"}}""" }
        controller(ai, listOf(open)).submitText("do something")
        settle()
        assertTrue(open.executed.isEmpty())
        assertEquals(listOf("I'm afraid I can't do that yet."), speaker.spoken)
    }

    @Test
    fun dataCommandIsNarratedByFollowUpCall() = env {
        val weather = FakeCommand(
            listOf("WEATHER"),
            result = ActionResult(true, "It's 18 degrees.", "현재 18도입니다.", data = "Now: 18C, clear", narrate = true),
        )
        val ai = FakeAi { _, n ->
            if (n == 1) """{"speech":"I'll check the weather.","subtitle":"날씨를 확인하겠습니다.","action":{"type":"WEATHER","city":"Seoul","day":"today"}}"""
            else """{"speech":"It is a clear 18 degrees in Seoul.","subtitle":"서울은 맑고 18도입니다.","action":null}"""
        }
        controller(ai, listOf(weather)).submitText("오늘 서울 날씨 알려줘")
        settle()
        assertEquals(listOf("I'll check the weather.", "It is a clear 18 degrees in Seoul."), speaker.spoken)
        assertEquals(2, ai.calls.size)
        assertTrue(ai.calls[1].last().content.contains("TOOL_RESULT"))
        assertTrue(ai.calls[1].last().content.contains("Now: 18C, clear"))
    }

    @Test
    fun narrationFallsBackToLocalSummaryWhenAiFails() = env {
        val weather = FakeCommand(
            listOf("WEATHER"),
            result = ActionResult(true, "It's 18 degrees.", "현재 18도입니다.", data = "Now: 18C", narrate = true),
        )
        val ai = FakeAi { _, n ->
            if (n == 1) """{"speech":"Checking.","subtitle":"확인합니다.","action":{"type":"WEATHER"}}"""
            else throw AiException(AiError.NETWORK, "down")
        }
        controller(ai, listOf(weather)).submitText("날씨")
        settle()
        assertEquals(listOf("Checking.", "It's 18 degrees."), speaker.spoken)
    }

    @Test
    fun offlineAiFallsBackToLocalCommandParser() = env {
        val torch = FakeCommand(listOf("FLASHLIGHT_ON"), executesBeforeSpeech = true)
        val ai = FakeAi { _, _ -> throw AiException(AiError.NETWORK, "offline") }
        controller(ai, listOf(torch)).submitText("손전등 켜줘")
        settle()
        assertEquals(1, torch.executed.size)
        assertEquals(listOf("Turning the flashlight on."), speaker.spoken)
    }

    @Test
    fun offlineWithoutLocalMatchSaysConnectionUnavailable() = env {
        val ai = FakeAi { _, _ -> throw AiException(AiError.NETWORK, "offline") }
        controller(ai).submitText("우주의 크기는?")
        settle()
        assertEquals(listOf("Connection unavailable."), speaker.spoken)
        assertEquals("인터넷 연결을 사용할 수 없습니다.", history().last().subtitle)
    }

    @Test
    fun missingApiKeyIsExplained() = env {
        val ai = FakeAi { _, _ -> throw AiException(AiError.NOT_CONFIGURED, "no key") }
        controller(ai).submitText("우주의 크기는?")
        settle()
        assertTrue(speaker.spoken.single().contains("API key"))
    }

    @Test
    fun malformedModelOutputStillSpeaks() = env {
        val ai = FakeAi { _, _ -> "I am well, thank you." }
        controller(ai).submitText("how are you")
        settle()
        assertEquals(listOf("I am well, thank you."), speaker.spoken)
    }

    @Test
    fun ttsFailureKeepsSubtitleAndDoesNotCrash() = env {
        speaker.failWith = com.jarvis.assistant.tts.TtsException(com.jarvis.assistant.tts.TtsError.ENGINE, "boom")
        val ai = FakeAi { _, _ -> """{"speech":"Hello.","subtitle":"안녕하세요.","action":null}""" }
        val c = controller(ai)
        c.submitText("hi")
        settle()
        assertEquals(2, history().size)
        assertEquals(AssistantPhase.IDLE, c.hud.value.phase)
    }

    @Test
    fun voiceFeedbackOffSkipsSpeech() = env {
        settings.put(SettingKeys.VOICE_FEEDBACK, false)
        val ai = FakeAi { _, _ -> """{"speech":"Hello.","subtitle":"안녕하세요.","action":null}""" }
        controller(ai).submitText("hi")
        settle()
        assertTrue(speaker.spoken.isEmpty())
        assertEquals("안녕하세요.", history().last().subtitle)
    }

    @Test
    fun micTapListensAndProcessesSpeech() = env {
        val ai = FakeAi { turns, _ ->
            assertEquals("유튜브 열어줘", turns.last().content)
            """{"speech":"Opening YouTube.","subtitle":"유튜브를 엽니다.","action":null}"""
        }
        val c = controller(ai, recognizer = FakeRecognizer(SttResult.Text("유튜브 열어줘", listOf("유튜브 열어줘"))))
        c.listenNow()
        settle()
        assertEquals(listOf("Opening YouTube."), speaker.spoken)
    }

    @Test
    fun silenceShowsHintInsteadOfCallingAi() = env {
        val ai = FakeAi { _, _ -> error("AI must not be called") }
        val c = controller(ai, recognizer = FakeRecognizer(SttResult.NoSpeech))
        c.listenNow()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100)
        assertTrue(ai.calls.isEmpty())
        assertTrue(c.hud.value.notice?.contains("들리지") == true)
    }

    @Test
    fun recognizerFailureShowsErrorState() = env {
        val ai = FakeAi { _, _ -> error("AI must not be called") }
        val c = controller(ai, recognizer = FakeRecognizer(SttResult.Failure(SttError.NETWORK)))
        c.listenNow()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100)
        assertEquals(AssistantPhase.ERROR, c.hud.value.phase)
        assertTrue(c.hud.value.notice?.contains("연결") == true)
    }

    @Test
    fun deniedMicrophoneBlocksStandby() = env {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val c = controller(FakeAi { _, _ -> "" })
        assertEquals(false, c.startStandby())
        assertEquals(AssistantPhase.ERROR, c.hud.value.phase)
        assertEquals(false, c.hud.value.standby)
    }

    @Test
    fun wakeWordWithInlineCommandSkipsAcknowledgement() = env {
        val ai = FakeAi { turns, _ ->
            assertEquals("지금 시간 알려줘", turns.last().content)
            """{"speech":"One moment.","subtitle":"잠시만요.","action":null}"""
        }
        val c = controller(ai, wake = FakeWake(WakeDetection("자비스", "지금 시간 알려줘")))
        assertTrue(c.startStandby())
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(listOf("One moment."), speaker.spoken)
        assertTrue(c.hud.value.standby)
        c.stopStandby()
        assertEquals(false, c.hud.value.standby)
    }

    @Test
    fun bareWakeWordAcknowledgesThenListens() = env {
        val ai = FakeAi { turns, _ ->
            assertEquals("배터리 얼마나 남았어", turns.last().content)
            """{"speech":"Checking.","subtitle":"확인합니다.","action":null}"""
        }
        val rec = FakeRecognizer(SttResult.Text("배터리 얼마나 남았어", listOf("배터리 얼마나 남았어")))
        val c = controller(ai, recognizer = rec, wake = FakeWake(WakeDetection("jarvis", null)))
        c.startStandby()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(2, speaker.spoken.size)
        assertTrue(speaker.spoken[0] in listOf("Yes, sir?", "At your service.", "I'm listening.", "How may I help?"))
        assertEquals("Checking.", speaker.spoken[1])
        c.stopStandby()
    }

    @Test
    fun conversationHistoryIsSentToAi() = env {
        val ai = FakeAi { _, n -> """{"speech":"Reply $n.","subtitle":"응답 $n","action":null}""" }
        val c = controller(ai)
        c.submitText("첫 번째")
        settle()
        c.submitText("두 번째")
        settle()
        val second = ai.calls[1].map { it.role to it.content }
        assertTrue(second.any { it.first == ChatTurn.USER && it.second == "첫 번째" })
        assertTrue(second.any { it.first == ChatTurn.ASSISTANT && it.second.contains("Reply 1.") })
        assertEquals("두 번째", ai.calls[1].last().content)
    }

    @Test
    fun clearingHistoryRemovesMessages() = env {
        val ai = FakeAi { _, _ -> """{"speech":"Hi.","subtitle":"안녕","action":null}""" }
        controller(ai).submitText("hello")
        settle()
        assertEquals(2, history().size)
        conversations.clearAll()
        assertEquals(0, history().size)
    }
}
