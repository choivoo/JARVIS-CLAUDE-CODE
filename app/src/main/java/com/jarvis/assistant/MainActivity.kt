package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.ui.boot.BootScreen
import com.jarvis.assistant.ui.conversation.ConversationScreen
import com.jarvis.assistant.ui.conversation.NotesScreen
import com.jarvis.assistant.ui.holo.HologramScreen
import com.jarvis.assistant.ui.home.HomeScreen
import com.jarvis.assistant.ui.settings.PermissionActions
import com.jarvis.assistant.ui.settings.PermissionStatus
import com.jarvis.assistant.ui.settings.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = applicationContext.container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsState()
            JarvisTheme(settings.theme, settings.accent) {
                JarvisRoot(container, settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Quick Settings tile / launcher shortcut: start listening immediately. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_UI, false) == true) {
            // Called while the phone was asleep: light the screen and show JARVIS above the lock screen.
            intent.removeExtra(EXTRA_WAKE_UI)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_LISTEN)
            if (Perms.hasMic(this)) applicationContext.container.controller.listenNow()
        }
    }

    override fun onStart() {
        super.onStart()
        applicationContext.container.gestures.acquire()
    }

    override fun onStop() {
        applicationContext.container.gestures.release()
        super.onStop()
    }

    companion object {
        const val EXTRA_LISTEN = "com.jarvis.assistant.extra.LISTEN"
        const val EXTRA_WAKE_UI = "com.jarvis.assistant.extra.WAKE_UI"
    }
}

private enum class Screen { HOME, SETTINGS, HISTORY, NOTES, HOLO }

@Composable
private fun JarvisRoot(container: AppContainer, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = container.controller
    val hud by controller.hud.collectAsState()
    val level = container.levels.level.collectAsState()
    val messages by container.conversations.observeRecent(200).collectAsState(initial = emptyList())

    var booted by rememberSaveable { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }

    // Permission state, re-read whenever the user comes back (e.g. from system settings).
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val status = remember(permTick) {
        PermissionStatus(
            mic = Perms.hasMic(context),
            notifications = Perms.hasNotifications(context),
            location = Perms.hasLocation(context),
            overlay = Perms.canOverlay(context),
            camera = Perms.hasCamera(context),
            contacts = Perms.hasContacts(context),
            calendar = Perms.hasCalendar(context),
            activity = Perms.hasActivityRecognition(context),
            writeSettings = Perms.canWriteSettings(context),
            batteryOptimization = Perms.ignoresBatteryOptimizations(context),
        )
    }

    // Over the lock screen JARVIS must not reveal private data (history, notes, settings).
    val keyguard = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }
    DisposableEffect(permTick) {
        locked = keyguard?.isKeyguardLocked == true
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                locked = keyguard?.isKeyguardLocked == true
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(locked) { if (locked) screen = Screen.HOME }

    var pendingAction by remember { mutableStateOf<String?>(null) }

    fun startStandbyService() {
        if (!JarvisForegroundService.start(context)) {
            controller.reportError("백그라운드 서비스를 시작하지 못했습니다. 마이크 권한을 확인해 주세요.")
        }
    }

    fun toggleStandby() {
        if (hud.standby) {
            controller.stopStandby()
            return
        }
        when {
            !Perms.hasMic(context) -> pendingAction = "standby-mic"
            !Perms.hasNotifications(context) -> pendingAction = "standby-notif"
            else -> startStandbyService()
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permTick++
        val pending = pendingAction
        pendingAction = null
        if (!granted) {
            controller.reportError("마이크 권한이 거부되었습니다. 설정에서 허용해 주세요.")
        } else if (pending == "standby-mic") {
            toggleStandby()
        } else if (pending == "listen") {
            controller.listenNow()
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permTick++
        val pending = pendingAction
        pendingAction = null
        if (!granted) {
            controller.reportError("알림 권한이 거부되었습니다. 마이크 사용 상태를 알림으로 표시해야 하므로 대기 모드를 시작할 수 없습니다.")
        } else if (pending == "standby-notif") {
            startStandbyService()
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    LaunchedEffect(pendingAction) {
        when (pendingAction) {
            "standby-mic", "listen" -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            "standby-notif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                pendingAction = null
                startStandbyService()
            }
        }
    }

    val actions = remember {
        PermissionActions(
            requestMic = { pendingAction = "mic-only" },
            requestNotifications = { pendingAction = "notif-only" },
            requestLocation = {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                )
            },
            requestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
            requestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
            requestCalendar = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
            requestActivity = { activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
            requestBatteryOptimization = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            },
            requestWriteSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            },
            openAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            },
            requestOverlay = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            },
        )
    }
    LaunchedEffect(pendingAction) {
        if (pendingAction == "mic-only") micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (pendingAction == "notif-only" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Optional auto start of the background assistant (only while the app is visible, as Android requires).
    LaunchedEffect(booted, status.mic, status.notifications, settings.backgroundAssistant) {
        if (booted && !autoStarted && settings.backgroundAssistant && status.mic && status.notifications && !hud.standby) {
            autoStarted = true
            startStandbyService()
        }
    }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Camera swipes only run on the home screen, and only while it is visible.
    val cameraActive by container.gestures.cameraActive.collectAsState()
    DisposableEffect(settings.cameraGestures, status.camera, booted, screen, locked) {
        if (settings.cameraGestures && status.camera && booted && !locked && (screen == Screen.HOME || screen == Screen.HOLO)) {
            container.gestures.startCamera(lifecycleOwner)
        }
        onDispose { container.gestures.stopCamera() }
    }
    // Hologram workspace <-> screen state (voice can open it too).
    val holoState by container.holo.state.collectAsState()
    LaunchedEffect(holoState.visible) {
        if (holoState.visible && !locked && screen != Screen.HOLO) screen = Screen.HOLO
        if (!holoState.visible && screen == Screen.HOLO) screen = Screen.HOME
    }
    LaunchedEffect(screen) {
        container.gestures.pointerMode = screen == Screen.HOLO
        if (screen == Screen.HOLO) container.holo.show() else container.holo.hide()
    }
    val notes by container.notes.observeAll().collectAsState(initial = emptyList())
    val tasks by container.tasks.observeAll().collectAsState(initial = emptyList())
    val pendingReminders by container.reminders.observePending().collectAsState(initial = emptyList())
    val routines by container.routineRepo.observeAll().collectAsState(initial = emptyList())

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    if (!booted) {
        BootScreen(onFinished = { booted = true })
        return
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            hud = hud,
            level = level,
            messages = messages,
            micGranted = status.mic,
            onRequestMic = { pendingAction = "mic-only" },
            onMic = {
                if (!Perms.hasMic(context)) pendingAction = "listen" else controller.listenNow()
            },
            onToggleStandby = ::toggleStandby,
            onSubmitText = controller::submitText,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenHistory = { screen = Screen.HISTORY },
            onOpenNotes = { screen = Screen.NOTES },
            onOpenHolo = { screen = Screen.HOLO },
            cameraGestureActive = cameraActive,
            locked = locked,
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            repo = container.settings,
            speaker = container.speaker,
            permissions = status,
            actions = actions,
            proximityAvailable = container.gestures.proximityAvailable,
            routines = routines,
            onSaveRoutine = { n, st -> scope.launch { container.routineRepo.save(n, st) } },
            onDeleteRoutine = { id -> scope.launch { container.routineRepo.delete(id) } },
            onBack = { screen = Screen.HOME },
        )
        Screen.HOLO -> HologramScreen(
            holo = container.holo,
            hub = container.holoData,
            hud = hud,
            level = level,
            pointerFlow = container.gestures.pointer,
            pointerEvents = container.gestures.pointerEvents,
            trackingEngine = container.gestures.trackingEngine,
            handsEnabled = settings.cameraGestures && status.camera,
            handsPermitted = status.camera,
            parallax = settings.holoParallax,
            onAsk = { if (!Perms.hasMic(context)) pendingAction = "listen" else controller.listenNow() },
            onExit = { screen = Screen.HOME },
            onEnableHands = {
                if (!status.camera) actions.requestCamera()
                scope.launch { container.settings.put(com.jarvis.assistant.data.model.SettingKeys.CAMERA_GESTURES, true) }
            },
        )
        Screen.NOTES -> NotesScreen(
            notes = notes,
            onBack = { screen = Screen.HOME },
            onDelete = { id -> scope.launch { container.notes.delete(id) } },
            onClearAll = { scope.launch { container.notes.clear() } },
            tasks = tasks,
            reminders = pendingReminders,
            onToggleTask = { id, done -> scope.launch { container.tasks.setDone(id, done) } },
            onDeleteTask = { id -> scope.launch { container.tasks.delete(id) } },
            onCancelReminder = { id ->
                container.reminderScheduler.cancel(id)
                scope.launch { container.reminders.delete(id) }
            },
        )
        Screen.HISTORY -> ConversationScreen(
            messages = messages,
            onBack = { screen = Screen.HOME },
            onClear = { scope.launch { container.conversations.clearAll() } },
        )
    }
}
