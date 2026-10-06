package com.jarvis.assistant

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.ai.AiConfig
import com.jarvis.assistant.ai.AiError
import com.jarvis.assistant.ai.AiException
import com.jarvis.assistant.ai.ChatTurn
import com.jarvis.assistant.ai.OpenAICompatibleProvider
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandExecutor
import com.jarvis.assistant.command.CommandRouter
import com.jarvis.assistant.core.JarvisController
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.security.SecretStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

private class MemoryStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) map.remove(name) else map[name] = value
    }

    override fun get(name: String) = map[name]
    override fun has(name: String) = map.containsKey(name)
}

private class Scripted(override val type: AiProviderType, val behaviour: () -> String) : AIProvider {
    var calls = 0
    override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String {
        calls++
        return behaviour()
    }
}

private fun reply(text: String) = """{"speech":"$text","subtitle":"$text","action":null}"""

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeAiFailoverTest {
    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var bg: CoroutineScope
    private lateinit var settings: SettingsRepository
    private val store = MemoryStore()
    private val spoken = mutableListOf<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    }

    @After
    fun tearDown() = db.close()

    private fun env(body: suspend TestScope.() -> Unit) = runTest {
        bg = backgroundScope
        settings = SettingsRepository(db.settingsDao(), store, backgroundScope)
        body()
    }

    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
    }

    private fun controller(providers: List<AIProvider>) = JarvisController(
        context = app, scope = bg, settingsRepo = settings, conversations = ConversationRepository(db.conversationDao()),
        aiProviders = providers.associateBy { it.type },
        recognizer = object : SpeechRecognizerEngine {
            override fun isAvailable() = true
            override suspend fun listen(request: ListenRequest, callbacks: ListenCallbacks): SttResult = SttResult.NoSpeech
        },
        wakeEngine = object : WakeWordEngine {
            override val name = "none"
            override suspend fun awaitWake(languageTag: String, sensitivity: com.jarvis.assistant.data.model.WakeSensitivity, mode: com.jarvis.assistant.data.model.WakeMode): WakeDetection =
                awaitCancellation()
        },
        speaker = SpeechOutput { spoken += it },
        executor = CommandExecutor(CommandRouter(emptyList())),
        network = NetworkMonitor(app), levels = AudioLevelBus(),
    )

    @Test
    fun geminiIsTheDefaultFreeProvider() {
        val s = AppSettings()
        assertEquals(AiProviderType.GEMINI, s.aiProvider)
        assertEquals("gemini-2.5-flash-lite", s.aiModel)
        assertTrue(AiProviderType.values().none { it.freeNote.isBlank() || it.keyUrl.isBlank() })
        assertTrue(s.aiFailover)
    }

    @Test
    fun limitHitMovesToTheNextProviderWithAKey() = env {
        store.put("key.ai.GEMINI", "g")
        store.put("key.ai.GROQ", "q")
        val gemini = Scripted(AiProviderType.GEMINI) { throw AiException(AiError.RATE_LIMIT, "429") }
        val groq = Scripted(AiProviderType.GROQ) { reply("From Groq.") }
        controller(listOf(gemini, groq)).submitText("우주의 크기는?")
        settle()
        assertEquals(1, gemini.calls)
        assertEquals(1, groq.calls)
        assertEquals(listOf("From Groq."), spoken)
    }

    @Test
    fun providersWithoutAKeyAreNeverCalled() = env {
        store.put("key.ai.GEMINI", "g")
        val gemini = Scripted(AiProviderType.GEMINI) { throw AiException(AiError.RATE_LIMIT, "429") }
        val groq = Scripted(AiProviderType.GROQ) { reply("should not run") }
        controller(listOf(gemini, groq)).submitText("우주의 크기는?")
        settle()
        assertEquals(0, groq.calls)
        assertEquals(listOf("I'm being rate limited. Please try again shortly."), spoken)
    }

    @Test
    fun rateLimitedProviderRestsForAWhile() = env {
        store.put("key.ai.GEMINI", "g")
        store.put("key.ai.GROQ", "q")
        val gemini = Scripted(AiProviderType.GEMINI) { throw AiException(AiError.RATE_LIMIT, "429") }
        val groq = Scripted(AiProviderType.GROQ) { reply("ok") }
        val c = controller(listOf(gemini, groq))
        c.submitText("질문 하나")
        settle()
        c.submitText("질문 둘")
        settle()
        assertEquals(2, groq.calls)
        // The selected provider is still tried first each time (so it recovers on its own).
        assertEquals(2, gemini.calls)
    }

    @Test
    fun failoverCanBeSwitchedOff() = env {
        store.put("key.ai.GEMINI", "g")
        store.put("key.ai.GROQ", "q")
        settings.put(SettingKeys.AI_FAILOVER, false)
        val gemini = Scripted(AiProviderType.GEMINI) { throw AiException(AiError.RATE_LIMIT, "429") }
        val groq = Scripted(AiProviderType.GROQ) { reply("nope") }
        controller(listOf(gemini, groq)).submitText("우주의 크기는?")
        settle()
        assertEquals(0, groq.calls)
    }

    @Test
    fun offlineDoesNotBurnThroughBackups() = env {
        store.put("key.ai.GEMINI", "g")
        store.put("key.ai.GROQ", "q")
        val gemini = Scripted(AiProviderType.GEMINI) { throw AiException(AiError.NETWORK, "offline") }
        val groq = Scripted(AiProviderType.GROQ) { reply("nope") }
        controller(listOf(gemini, groq)).submitText("우주의 크기는?")
        settle()
        assertEquals(0, groq.calls)
        assertEquals(listOf("Connection unavailable."), spoken)
    }

    @Test
    fun selectedProviderCanBeAnyOfTheFreeOnes() = env {
        settings.put(SettingKeys.AI_PROVIDER, AiProviderType.GROQ.name)
        store.put("key.ai.GROQ", "q")
        val cfg = settings.aiConfig(AiProviderType.GROQ)
        assertEquals("https://api.groq.com/openai/v1", cfg.endpoint)
        assertEquals("llama-3.1-8b-instant", cfg.model)
        assertEquals("q", cfg.apiKey)
        val groq = Scripted(AiProviderType.GROQ) { reply("hi") }
        controller(listOf(groq)).submitText("안녕")
        settle()
        assertEquals(listOf("hi"), spoken)
    }

    @Test
    fun hostedServicesRequireAKeyButLocalServersDoNot() {
        val provider = OpenAICompatibleProvider(AiProviderType.GROQ)
        try {
            kotlinx.coroutines.runBlocking {
                provider.complete(AiConfig(AiProviderType.GROQ, "https://api.groq.com/openai/v1", "m", null), emptyList())
            }
            throw AssertionError("expected NOT_CONFIGURED")
        } catch (e: AiException) {
            assertEquals(AiError.NOT_CONFIGURED, e.kind)
        }
    }

    // ---- token saving ----
    @Test
    fun promptOnlyAttachesRelevantActionGroups() {
        val now = ZonedDateTime.now()
        val chat = PromptBuilder.system(now, "Seoul", "Sir", "안녕 오늘 기분 어때")
        val timer = PromptBuilder.system(now, "Seoul", "Sir", "10분 타이머 맞춰줘")
        val all = PromptBuilder.system(now, "Seoul", "Sir")
        assertFalse(chat.contains("SET_TIMER"))
        assertTrue(timer.contains("SET_TIMER"))
        assertFalse(timer.contains("CRYPTO_PRICE"))
        assertTrue(chat.contains("OPEN_APP"))               // core always present
        assertTrue(chat.length < timer.length && timer.length < all.length)
        assertTrue("a chat turn should be well under half the full catalog", chat.length < all.length * 0.6)
        assertEquals(listOf("time"), PromptBuilder.groupsFor("10분 타이머 맞춰줘"))
        assertTrue(PromptBuilder.groupsFor("비트코인 시세랑 뉴스").containsAll(listOf("info")))
        assertTrue(all.contains("CALCULATE") && all.contains("RUN_ROUTINE") && all.contains("JARVIS_SETTING"))
    }
}
