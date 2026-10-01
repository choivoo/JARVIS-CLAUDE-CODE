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
        setContent {
            val settings by container.settings.settings.collectAsState()
            JarvisTheme(settings.theme) {
                JarvisRoot(container, settings)
            }
        }
    }
}

private enum class Screen { HOME, SETTINGS, HISTORY }

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
        )
    }

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
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            repo = container.settings,
            speaker = container.speaker,
            permissions = status,
            actions = actions,
            onBack = { screen = Screen.HOME },
        )
        Screen.HISTORY -> ConversationScreen(
            messages = messages,
            onBack = { screen = Screen.HOME },
            onClear = { scope.launch { container.conversations.clearAll() } },
        )
    }
}
