package com.jarvis.assistant

import android.app.Application
import android.content.Context
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.ai.GeminiProvider
import com.jarvis.assistant.ai.OllamaProvider
import com.jarvis.assistant.ai.OpenAICompatibleProvider
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.AppVisibility
import com.jarvis.assistant.command.CommandExecutor
import com.jarvis.assistant.command.CommandRouter
import com.jarvis.assistant.command.ContactResolver
import com.jarvis.assistant.command.commands.CallCommand
import com.jarvis.assistant.command.commands.CopyTextCommand
import com.jarvis.assistant.command.commands.CreateEventCommand
import com.jarvis.assistant.command.commands.ListNotesCommand
import com.jarvis.assistant.command.commands.NavigateCommand
import com.jarvis.assistant.command.commands.OpenCameraCommand
import com.jarvis.assistant.command.commands.SaveNoteCommand
import com.jarvis.assistant.command.commands.SendSmsCommand
import com.jarvis.assistant.command.commands.SetTimerCommand
import com.jarvis.assistant.command.commands.ShareTextCommand
import com.jarvis.assistant.command.commands.FlashlightCommand
import com.jarvis.assistant.command.commands.GetBatteryCommand
import com.jarvis.assistant.command.commands.GetTimeCommand
import com.jarvis.assistant.command.commands.MusicCommand
import com.jarvis.assistant.command.commands.NotificationCommand
import com.jarvis.assistant.command.commands.OpenAppCommand
import com.jarvis.assistant.command.commands.OpenSettingsCommand
import com.jarvis.assistant.command.commands.OpenUrlCommand
import com.jarvis.assistant.command.commands.SearchWebCommand
import com.jarvis.assistant.command.commands.SearchYoutubeCommand
import com.jarvis.assistant.command.commands.SetAlarmCommand
import com.jarvis.assistant.command.commands.VolumeCommand
import com.jarvis.assistant.command.commands.WeatherCommand
import com.jarvis.assistant.command.commands.WebAnswerCommand
import com.jarvis.assistant.core.JarvisController
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.NoteRepository
import com.jarvis.assistant.gesture.GestureManager
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.search.BrowserSearchProvider
import com.jarvis.assistant.search.InstantAnswerSearchProvider
import com.jarvis.assistant.security.SecureStorage
import com.jarvis.assistant.speech.AndroidSpeechRecognizer
import com.jarvis.assistant.speech.SpeechWakeWordEngine
import com.jarvis.assistant.tts.AndroidTTSProvider
import com.jarvis.assistant.tts.ElevenLabsTTSProvider
import com.jarvis.assistant.tts.JarvisSpeaker
import com.jarvis.assistant.tts.OpenAICompatibleTTSProvider
import com.jarvis.assistant.tts.PcmPlayer
import com.jarvis.assistant.tts.TTSProvider
import com.jarvis.assistant.util.AudioLevelBus
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.weather.LocationProvider
import com.jarvis.assistant.weather.OpenMeteoWeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class JarvisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.visibility)
    }
}

val Context.container: AppContainer get() = (applicationContext as JarvisApp).container

/** Hand-rolled dependency graph: every engine is created here, so swapping one is a one-line change. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val secure = SecureStorage(app)
    private val database = JarvisDatabase.create(app)
    val notes = NoteRepository(database.noteDao())
    val settings = SettingsRepository(database.settingsDao(), secure, appScope)
    val conversations = ConversationRepository(database.conversationDao())

    val network = NetworkMonitor(app)
    val levels = AudioLevelBus()
    val visibility = AppVisibility()

    // Speech in
    private val recognizer = AndroidSpeechRecognizer(app)
    private val wakeEngine = SpeechWakeWordEngine(app, recognizer)

    // Speech out
    private val androidTts = AndroidTTSProvider(app)
    private val ttsProviders: Map<TtsProviderType, TTSProvider> = mapOf(
        TtsProviderType.ANDROID to androidTts,
        TtsProviderType.OPENAI_COMPATIBLE to OpenAICompatibleTTSProvider(),
        TtsProviderType.ELEVENLABS to ElevenLabsTTSProvider(),
    )
    val speaker = JarvisSpeaker(app, settings, androidTts, ttsProviders, PcmPlayer(levels), levels)

    // Brain
    private val aiProviders: Map<AiProviderType, AIProvider> = listOf(
        OpenAICompatibleProvider(), GeminiProvider(), OllamaProvider(),
    ).associateBy { it.type }

    // Commands
    private val launcher = ActivityLauncher(app, visibility)
    private val resolver = AppResolver(app)
    private val contacts = ContactResolver(app)
    private val router = CommandRouter(
        listOf(
            OpenAppCommand(resolver, launcher),
            OpenUrlCommand(launcher),
            OpenSettingsCommand(launcher),
            SearchWebCommand(BrowserSearchProvider(launcher)),
            WebAnswerCommand(InstantAnswerSearchProvider()),
            SearchYoutubeCommand(resolver, launcher),
            SetAlarmCommand(launcher),
            GetTimeCommand(),
            GetBatteryCommand(app),
            VolumeCommand(app),
            FlashlightCommand(app),
            NotificationCommand(app),
            MusicCommand(app, resolver, launcher),
            WeatherCommand(OpenMeteoWeatherProvider(), LocationProvider(app), settings),
            SetTimerCommand(launcher),
            CallCommand(launcher, contacts),
            SendSmsCommand(launcher, contacts),
            NavigateCommand(resolver, launcher),
            CreateEventCommand(launcher),
            OpenCameraCommand(launcher),
            CopyTextCommand(app),
            ShareTextCommand(launcher),
            SaveNoteCommand(notes),
            ListNotesCommand(notes),
        ),
    )
    private val executor = CommandExecutor(router)

    val controller = JarvisController(
        context = app,
        scope = appScope,
        settingsRepo = settings,
        conversations = conversations,
        aiProviders = aiProviders,
        recognizer = recognizer,
        wakeEngine = wakeEngine,
        speaker = speaker,
        executor = executor,
        network = network,
        levels = levels,
    )
    val gestures = GestureManager(app, settings, controller, appScope)
}
