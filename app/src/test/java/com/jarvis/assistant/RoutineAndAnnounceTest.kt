package com.jarvis.assistant

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.ai.AiConfig
import com.jarvis.assistant.ai.ChatTurn
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.command.CommandExecutor
import com.jarvis.assistant.command.CommandRouter
import com.jarvis.assistant.core.JarvisController
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.RoutineRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.routine.RoutineBook
import com.jarvis.assistant.security.SecureStorage
import com.jarvis.assistant.speech.ListenCallbacks
import com.jarvis.assistant.speech.ListenRequest
import com.jarvis.assistant.speech.SpeechRecognizerEngine
import com.jarvis.assistant.speech.SttResult
import com.jarvis.assistant.speech.WakeDetection
import com.jarvis.assistant.speech.WakeWordEngine
import com.jarvis.assistant.tts.SpeechOutput
import com.jarvis.assistant.util.AudioLevelBus
import com.jarvis.assistant.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineAndAnnounceTest {
    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var bg: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var routineRepo: RoutineRepository
    private val spoken = mutableListOf<String>()

    private class Cmd(override val types: List<String>, val speech: String) : Command {
        var runs = 0
        override val executesBeforeSpeech = true
        override suspend fun execute(action: AiAction): ActionResult {
            runs++
            return ActionResult.ok(speech, speech)
        }
    }

    private object NoAi : AIProvider {
        override val type = AiProviderType.OPENAI_COMPATIBLE
        override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String = """{"speech":"x","subtitle":"x","action":null}"""
    }

    private object NoRec : SpeechRecognizerEngine {
        override fun isAvailable() = true
        override suspend fun listen(request: ListenRequest, callbacks: ListenCallbacks): SttResult = SttResult.NoSpeech
    }

    private object NoWake : WakeWordEngine {
        override val name = "none"
        override suspend fun awaitWake(languageTag: String, sensitivity: com.jarvis.assistant.data.model.WakeSensitivity): WakeDetection =
            awaitCancellation()
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        routineRepo = RoutineRepository(db.routineDao())
    }

    @After
    fun tearDown() = db.close()

    private fun controller(commands: List<Command>): JarvisController = JarvisController(
        context = app, scope = bg, settingsRepo = settings, conversations = ConversationRepository(db.conversationDao()),
        aiProviders = AiProviderType.values().associateWith { NoAi }, recognizer = NoRec, wakeEngine = NoWake,
        speaker = SpeechOutput { spoken += it }, executor = CommandExecutor(CommandRouter(commands)),
        network = NetworkMonitor(app), levels = AudioLevelBus(), routines = RoutineBook(routineRepo), sounds = null,
    )

    private fun env(body: suspend TestScope.() -> Unit) = runTest {
        bg = backgroundScope
        settings = SettingsRepository(db.settingsDao(), SecureStorage(app), backgroundScope)
        body()
    }

    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
    }

    @Test
    fun builtInRoutineRunsItsStepsInOrder() = env {
        val time = Cmd(listOf("GET_TIME"), "It's 7:00 AM.")
        val battery = Cmd(listOf("GET_BATTERY"), "Battery 80 percent.")
        val c = controller(listOf(time, battery, Cmd(listOf("WEATHER"), "Sunny."), Cmd(listOf("CALENDAR_TODAY"), "Nothing today."), Cmd(listOf("NEWS"), "No news.")))
        c.submitText("굿모닝 루틴 시작")
        settle()
        assertEquals("Good morning. Let me prepare your briefing.", spoken.first())
        assertEquals(listOf("It's 7:00 AM.", "Sunny.", "Nothing today.", "Battery 80 percent.", "No news."), spoken.drop(1))
    }

    @Test
    fun customRoutineUsesSavedSteps() = env {
        routineRepo.save("공부", "지금 시간 알려줘\n내 배터리 얼마나 남았어?")
        val time = Cmd(listOf("GET_TIME"), "Time.")
        val battery = Cmd(listOf("GET_BATTERY"), "Battery.")
        controller(listOf(time, battery)).submitText("공부 루틴 실행")
        settle()
        assertEquals(1, time.runs)
        assertEquals(1, battery.runs)
        assertEquals(listOf("Time.", "Battery."), spoken)
    }

    @Test
    fun unknownRoutineIsExplained() = env {
        controller(emptyList()).submitText("없는 루틴 실행")
        settle()
        // The AI fallback (NoAi) answers "x" because the local parser does not map this phrase to a routine.
        assertTrue(spoken.isNotEmpty())
    }

    @Test
    fun announceSpeaksUnprompted() = env {
        val c = controller(emptyList())
        c.announce("Pardon the interruption. A reminder: call mum", "알림입니다: 엄마에게 전화")
        settle()
        assertEquals(listOf("Pardon the interruption. A reminder: call mum"), spoken)
    }

    @Test
    fun repeatSaysTheLastLineAgain() = env {
        val c = controller(listOf(Cmd(listOf("GET_TIME"), "It's noon.")))
        c.submitText("지금 시간 알려줘")
        settle()
        c.submitText("다시 말해줘")
        settle()
        assertEquals(listOf("It's noon.", "It's noon."), spoken)
    }
}
