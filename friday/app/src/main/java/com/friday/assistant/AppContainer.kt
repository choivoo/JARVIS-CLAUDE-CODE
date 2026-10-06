package com.friday.assistant

import android.app.Application
import android.content.Context
import com.friday.assistant.ai.AIProvider
import com.friday.assistant.ai.GeminiProvider
import com.friday.assistant.ai.OllamaProvider
import com.friday.assistant.ai.OpenAICompatibleProvider
import com.friday.assistant.command.ActivityLauncher
import com.friday.assistant.command.AndroidExecutors
import com.friday.assistant.command.AppResolver
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.ContactResolver
import com.friday.assistant.command.LocationHelper
import com.friday.assistant.core.FridayController
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.data.FridayDatabase
import com.friday.assistant.search.DefaultWebSearchProvider
import com.friday.assistant.search.WebSearchProvider
import com.friday.assistant.security.KeystoreSecureStore
import com.friday.assistant.security.SecureStore
import com.friday.assistant.settings.AiProviderType
import com.friday.assistant.settings.SettingsRepository
import com.friday.assistant.settings.TtsProviderType
import com.friday.assistant.stt.AndroidSpeechRecognizerEngine
import com.friday.assistant.stt.SpeechRecognizerEngine
import com.friday.assistant.tts.AndroidTTSProvider
import com.friday.assistant.tts.ElevenLabsCompatibleTTSProvider
import com.friday.assistant.tts.FridaySpeaker
import com.friday.assistant.tts.OpenAICompatibleTTSProvider
import com.friday.assistant.util.NetworkMonitor
import com.friday.assistant.wake.SpeechWakeWordEngine
import com.friday.assistant.wake.WakeWordEngine
import com.friday.assistant.weather.OpenMeteoProvider
import com.friday.assistant.weather.WeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Hand-rolled dependency graph; one instance lives in [FridayApp]. */
class AppContainer(
    val context: Context,
    secure: SecureStore = KeystoreSecureStore(context),
    db: FridayDatabase? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile var appInForeground = false
    val serviceRunning = MutableStateFlow(false)
    val serviceError = MutableStateFlow<String?>(null)

    val settingsRepo = SettingsRepository.create(context, secure)
    val database: FridayDatabase by lazy { db ?: FridayDatabase.create(context) }
    val conversations: ConversationRepository by lazy { ConversationRepository(database.dao()) }
    val network = NetworkMonitor(context)

    val weather: WeatherProvider = OpenMeteoProvider()
    val webSearch: WebSearchProvider = DefaultWebSearchProvider()
    val stt: SpeechRecognizerEngine by lazy { AndroidSpeechRecognizerEngine(context) }
    val wake: WakeWordEngine by lazy { SpeechWakeWordEngine(context) }

    private val androidTts by lazy { AndroidTTSProvider(context) { settingsRepo.current } }
    val androidTtsProvider get() = androidTts
    val speaker by lazy {
        FridaySpeaker(
            configured = { settingsRepo.current.ttsProvider },
            cloud = { type ->
                val s = settingsRepo.current
                val key = settingsRepo.ttsApiKey()
                when (type) {
                    TtsProviderType.OPENAI_COMPATIBLE -> OpenAICompatibleTTSProvider(s, key)
                    TtsProviderType.ELEVENLABS -> ElevenLabsCompatibleTTSProvider(s, key)
                    TtsProviderType.ANDROID -> null
                }
            },
            fallback = androidTts,
        )
    }

    val launcher = ActivityLauncher(context) { appInForeground }
    val router: CommandRouter by lazy {
        CommandRouter(
            AndroidExecutors(
                context, launcher, AppResolver(context), weather, webSearch,
                ContactResolver(context), LocationHelper(context), { settingsRepo.current.defaultCity },
            ).all(),
        )
    }

    fun aiProvider(): AIProvider {
        val s = settingsRepo.current
        return when (s.aiProvider) {
            AiProviderType.OPENAI_COMPATIBLE -> OpenAICompatibleProvider(s.aiEndpoint, s.aiModel, settingsRepo.aiApiKey())
            AiProviderType.GEMINI -> GeminiProvider(s.aiEndpoint, s.aiModel, settingsRepo.aiApiKey())
            AiProviderType.OLLAMA -> OllamaProvider(s.aiEndpoint, s.aiModel)
        }
    }

    val controller: FridayController by lazy {
        FridayController(
            scope = scope,
            settings = { settingsRepo.current },
            stt = stt,
            aiProvider = ::aiProvider,
            router = router,
            speaker = speaker,
            repo = conversations,
            isOnline = network::isOnline,
        )
    }
}

class FridayApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
