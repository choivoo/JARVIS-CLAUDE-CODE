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
import com.friday.assistant.tts.OpenAICompatibleTTSProvider
import com.friday.assistant.util.NetworkMonitor
import com.friday.assistant.util.Permissions
import com.friday.assistant.voice.AudioFocusManager
import com.friday.assistant.voice.Speaker
import com.friday.assistant.voice.VoiceEngine
import com.friday.assistant.brief.BriefingComposer
import com.friday.assistant.calendar.AndroidCalendarProvider
import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.calendar.CalendarInsertUi
import com.friday.assistant.calendar.CalendarProvider
import com.friday.assistant.command.AccessChecker
import com.friday.assistant.command.AppControlExecutors
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandType
import com.friday.assistant.core.ContextEngine
import com.friday.assistant.device.AndroidDeviceStatusProvider
import com.friday.assistant.device.DeviceExecutors
import com.friday.assistant.device.DeviceStatusProvider
import com.friday.assistant.media.AndroidMediaBackend
import com.friday.assistant.media.MediaBackend
import com.friday.assistant.media.MediaExecutors
import com.friday.assistant.notification.NotificationExecutors
import com.friday.assistant.notification.NotificationHub
import com.friday.assistant.permission.PermissionCenter
import com.friday.assistant.proactive.AlertKind
import com.friday.assistant.proactive.LowBatterySource
import com.friday.assistant.proactive.ProactiveEngine
import com.friday.assistant.proactive.UpcomingEventSource
import com.friday.assistant.proactive.WeatherWarningSource
import com.friday.assistant.weather.WeatherReport
import com.friday.assistant.overlay.SubtitleOverlay
import kotlinx.coroutines.launch
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import kotlinx.coroutines.CancellationException
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
    /** Tier A = OpenAI-compatible cloud voice, Tier B = ElevenLabs-compatible, Tier C = Android TTS (always available). */
    val voice: VoiceEngine by lazy {
        VoiceEngine(
            settings = { settingsRepo.current },
            tier = { type ->
                val st = settingsRepo.current
                when (type) {
                    TtsProviderType.OPENAI_COMPATIBLE -> settingsRepo.ttsApiKey().takeIf { it.isNotBlank() }?.let { OpenAICompatibleTTSProvider(st, it) }
                    TtsProviderType.ELEVENLABS -> settingsRepo.ttsApiKey2().takeIf { it.isNotBlank() && st.ttsSecondaryVoice.isNotBlank() }?.let {
                        ElevenLabsCompatibleTTSProvider(st.copy(ttsVoice = st.ttsSecondaryVoice, ttsEndpoint = "https://api.elevenlabs.io/v1", ttsModel = "eleven_flash_v2_5"), it)
                    }
                    TtsProviderType.ANDROID -> null
                }
            },
            fallback = androidTts,
            focus = AudioFocusManager(context),
        )
    }
    val speaker: Speaker get() = voice

    val notifications = NotificationHub(context)
    val calendar: CalendarProvider = AndroidCalendarProvider(context)
    val deviceStatus: DeviceStatusProvider = AndroidDeviceStatusProvider(context)
    val mediaBackend: MediaBackend = AndroidMediaBackend(context)
    val contextEngine = ContextEngine()
    val access = object : AccessChecker {
        override fun hasPermission(permission: String) = Permissions.has(context, permission)
        override fun hasNotificationAccess() = NotificationHub.isAccessGranted(context)
    }

    val launcher = ActivityLauncher(context) { appInForeground }
    private val locationHelper = LocationHelper(context)

    /** Weather for the current location if known, else the default city; null when unavailable. */
    suspend fun weatherOrNull(): WeatherReport? = try {
        val c = locationHelper.lastKnown()
        weather.fetch(if (c == null) settingsRepo.current.defaultCity else null, c?.lat, c?.lon)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    val router: CommandRouter by lazy {
        val apps = AppResolver(context)
        val insertUi = CalendarInsertUi { title, start, end ->
            val z = java.time.ZoneId.systemDefault()
            val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(z).toInstant().toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(z).toInstant().toEpochMilli())
            try { launcher.launch(i, "the calendar"); true } catch (e: ActivityNotFoundException) { false }
        }
        val all = LinkedHashMap<CommandType, CommandExecutor>()
        all += AndroidExecutors(
            context, launcher, apps, weather, webSearch, ContactResolver(context), locationHelper, { settingsRepo.current.defaultCity },
        ).all()
        all += AppControlExecutors(context, launcher, apps).all()
        all += DeviceExecutors(deviceStatus).all()
        all += MediaExecutors(mediaBackend).all()
        all += NotificationExecutors(notifications).all()
        all += CalendarExecutors(calendar, contextEngine, insertUi).all()
        all += BriefingComposer({ java.time.LocalDateTime.now() }, ::weatherOrNull, calendar, notifications, deviceStatus).executors()
        CommandRouter(all, access)
    }

    val proactive: ProactiveEngine by lazy {
        ProactiveEngine(
            { settingsRepo.current },
            mapOf(
                AlertKind.LOW_BATTERY to LowBatterySource(deviceStatus),
                AlertKind.UPCOMING_EVENT to UpcomingEventSource(calendar),
                AlertKind.WEATHER_WARNING to WeatherWarningSource(::weatherOrNull),
            ),
        )
    }

    val overlay = SubtitleOverlay(context, { settingsRepo.current }, { appInForeground })

    /** Mirrors the subtitle onto the overlay and re-checks it when settings change. Called once from the Application. */
    fun startOverlay() {
        scope.launch { controller.subtitle.collect { overlay.update(it.subtitle) } }
        scope.launch { settingsRepo.settings.collect { overlay.refresh() } }
    }

    val permissionSnapshot get() = PermissionCenter.snapshot(context)

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
            contextEngine = contextEngine,
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
        container.startOverlay()
    }
}
