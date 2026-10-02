package com.jarvis.assistant

import android.app.Application
import android.content.Context
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.audio.UiSounds
import com.jarvis.assistant.command.commands.AltitudeCommand
import com.jarvis.assistant.command.commands.AmbientLightCommand
import com.jarvis.assistant.command.commands.AppSettingsCommand
import com.jarvis.assistant.command.commands.BatteryDetailCommand
import com.jarvis.assistant.command.commands.BriefingCommand
import com.jarvis.assistant.command.commands.BrightnessCommand
import com.jarvis.assistant.command.commands.CalculateCommand
import com.jarvis.assistant.command.commands.CalendarReadCommand
import com.jarvis.assistant.command.commands.CoinFlipCommand
import com.jarvis.assistant.command.commands.CompassCommand
import com.jarvis.assistant.command.commands.ConvertUnitsCommand
import com.jarvis.assistant.command.commands.CounterCommand
import com.jarvis.assistant.command.commands.CryptoPriceCommand
import com.jarvis.assistant.command.commands.CurrencyCommand
import com.jarvis.assistant.command.commands.DaysUntilCommand
import com.jarvis.assistant.command.commands.DeviceInfoCommand
import com.jarvis.assistant.command.commands.DiceCommand
import com.jarvis.assistant.command.commands.EchoCommand
import com.jarvis.assistant.command.commands.EightBallCommand
import com.jarvis.assistant.command.commands.EmailCommand
import com.jarvis.assistant.command.commands.EnvironmentCommand
import com.jarvis.assistant.command.commands.FindPhoneCommand
import com.jarvis.assistant.command.commands.FunFactCommand
import com.jarvis.assistant.command.commands.GetDateCommand
import com.jarvis.assistant.command.commands.HelpCommand
import com.jarvis.assistant.command.commands.JarvisSettingCommand
import com.jarvis.assistant.command.commands.JokeCommand
import com.jarvis.assistant.command.commands.MemoryInfoCommand
import com.jarvis.assistant.command.commands.MuteCommand
import com.jarvis.assistant.command.commands.MusicKeysCommand
import com.jarvis.assistant.command.commands.MusicSearchCommand
import com.jarvis.assistant.command.commands.NetworkInfoCommand
import com.jarvis.assistant.command.commands.NewsCommand
import com.jarvis.assistant.command.commands.PasswordCommand
import com.jarvis.assistant.command.commands.PickRandomCommand
import com.jarvis.assistant.command.commands.PlaceResolver
import com.jarvis.assistant.command.commands.PlayStoreSearchCommand
import com.jarvis.assistant.command.commands.QuoteCommand
import com.jarvis.assistant.command.commands.RandomNumberCommand
import com.jarvis.assistant.command.commands.ReminderCancelCommand
import com.jarvis.assistant.command.commands.ReminderListCommand
import com.jarvis.assistant.command.commands.ReminderSetCommand
import com.jarvis.assistant.command.commands.RingerModeCommand
import com.jarvis.assistant.command.commands.RoutineListCommand
import com.jarvis.assistant.command.commands.SearchSiteCommand
import com.jarvis.assistant.command.commands.ShareLocationCommand
import com.jarvis.assistant.command.commands.ShowAlarmsCommand
import com.jarvis.assistant.command.commands.SosFlashCommand
import com.jarvis.assistant.command.commands.StatusReportCommand
import com.jarvis.assistant.command.commands.StepCountCommand
import com.jarvis.assistant.command.commands.StopwatchCommand
import com.jarvis.assistant.command.commands.StorageInfoCommand
import com.jarvis.assistant.command.commands.StreamVolumeCommand
import com.jarvis.assistant.command.commands.TaskCommand
import com.jarvis.assistant.command.commands.UptimeCommand
import com.jarvis.assistant.command.commands.WhereAmICommand
import com.jarvis.assistant.command.commands.WorldTimeCommand
import com.jarvis.assistant.data.repository.ReminderRepository
import com.jarvis.assistant.data.repository.RoutineRepository
import com.jarvis.assistant.data.repository.TaskRepository
import com.jarvis.assistant.reminder.ReminderScheduler
import com.jarvis.assistant.routine.RoutineBook
import com.jarvis.assistant.weather.EnvironmentProvider
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
    val tasks = TaskRepository(database.taskDao())
    val reminders = ReminderRepository(database.reminderDao())
    val routineRepo = RoutineRepository(database.routineDao())
    val reminderScheduler = ReminderScheduler(app)
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
    private lateinit var router: CommandRouter
    private val weatherProvider = OpenMeteoWeatherProvider()
    private val locationProvider = LocationProvider(app)
    private val places = PlaceResolver(weatherProvider, locationProvider, settings)
    private val envProvider = EnvironmentProvider()
    private val stopwatch = com.jarvis.assistant.tools.StopwatchEngine()
    private val routineBook = RoutineBook(routineRepo)
    val uiSounds = UiSounds(settings, appScope)

    private val commandList = run {
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
            WeatherCommand(weatherProvider, locationProvider, settings),
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
            // ---- v1.2 ----
            BrightnessCommand(app, launcher), MuteCommand(app), RingerModeCommand(app, launcher), StreamVolumeCommand(app),
            StorageInfoCommand(), MemoryInfoCommand(app), DeviceInfoCommand(), NetworkInfoCommand(app), BatteryDetailCommand(app),
            UptimeCommand(), SosFlashCommand(app), FindPhoneCommand(app), MusicKeysCommand(app), MusicSearchCommand(launcher),
            GetDateCommand(), WorldTimeCommand(), DaysUntilCommand(), StopwatchCommand(stopwatch),
            CalculateCommand(), ConvertUnitsCommand(), CurrencyCommand(), RandomNumberCommand(), DiceCommand(), CoinFlipCommand(),
            PickRandomCommand(), PasswordCommand(app), JokeCommand(), QuoteCommand(), FunFactCommand(), EightBallCommand(),
            EnvironmentCommand(places, envProvider), WhereAmICommand(app, locationProvider),
            ShareLocationCommand(app, locationProvider, launcher), NewsCommand(), CryptoPriceCommand(),
            ReminderSetCommand(reminders, reminderScheduler), ReminderListCommand(reminders), ReminderCancelCommand(reminders, reminderScheduler),
            TaskCommand(tasks), CounterCommand(settings), EmailCommand(launcher), CalendarReadCommand(app), ShowAlarmsCommand(launcher),
            SearchSiteCommand(launcher), PlayStoreSearchCommand(launcher), AppSettingsCommand(resolver, launcher),
            AmbientLightCommand(app), CompassCommand(app), StepCountCommand(app), AltitudeCommand(app),
            HelpCommand(), EchoCommand(), JarvisSettingCommand(settings), RoutineListCommand(routineRepo),
            StatusReportCommand(app, settings, network, recognizer) { router.supportedTypes.size },
            BriefingCommand { router },
        )
    }
    init {
        router = CommandRouter(commandList)
    }
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
        routines = routineBook,
        sounds = uiSounds,
    )
    val gestures = GestureManager(app, settings, controller, appScope)
}
