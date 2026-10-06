@file:SuppressLint("InlinedApi") // POST_NOTIFICATIONS is requested only when the user turns the Background Assistant on

package com.friday.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.friday.assistant.service.FridayService
import com.friday.assistant.ui.ConversationViewModel
import com.friday.assistant.ui.DiagnosticsViewModel
import com.friday.assistant.ui.HomeViewModel
import com.friday.assistant.ui.SettingsViewModel
import com.friday.assistant.ui.boot.BootScreen
import com.friday.assistant.ui.conversation.ConversationScreen
import com.friday.assistant.ui.diag.DiagnosticsScreen
import com.friday.assistant.ui.factory
import com.friday.assistant.ui.home.HomeScreen
import com.friday.assistant.ui.settings.SettingsActions
import com.friday.assistant.ui.settings.SettingsScreen
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.FridayTheme
import com.friday.assistant.util.Permissions

class MainActivity : ComponentActivity() {
    private val container get() = (application as FridayApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FridayRoot(container, this) }
    }

    override fun onStart() {
        super.onStart()
        container.appInForeground = true
        val s = container.settingsRepo.current
        if (s.backgroundAssistant && Permissions.mic(this) && !container.serviceRunning.value) FridayService.start(this)
    }

    override fun onStop() {
        container.appInForeground = false
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("HUD", Icons.Filled.Home), CHAT("LOG", Icons.AutoMirrored.Filled.Chat),
    DIAG("DIAG", Icons.Filled.BugReport), SETTINGS("SETUP", Icons.Filled.Settings),
}

@Composable
fun FridayRoot(container: AppContainer, activity: android.content.Context) {
    val settings by container.settingsRepo.settings.collectAsStateWithLifecycle()
    FridayTheme(settings.accent, settings.amoled) {
        var booted by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(if (settings.amoled) FridayColors.Amoled else FridayColors.Dark)) {
            if (!booted) BootScreen(onFinished = { booted = true })
            else MainShell(container, activity)
        }
    }
}

@Composable
private fun MainShell(container: AppContainer, context: android.content.Context) {
    val settings by container.settingsRepo.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val tabs = Tab.entries.filter { it != Tab.DIAG || settings.developerDiagnostics }
    if (tab !in tabs) tab = Tab.HOME

    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var granted by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke(); afterMic = null; granted++
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted++; afterPerm?.invoke(); afterPerm = null }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted++
        if (Permissions.mic(context)) {
            container.settingsRepo.update { it.copy(backgroundAssistant = true) }
            FridayService.start(context)
        } else {
            container.serviceError.value = "Microphone permission is required for the Background Assistant."
        }
    }

    val actions = remember(granted) {
        SettingsActions(
            setBackground = { on ->
                if (on) {
                    val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).filterNot { Permissions.has(context, it) }
                    if (needed.isEmpty()) {
                        container.settingsRepo.update { it.copy(backgroundAssistant = true) }
                        FridayService.start(context)
                    } else bgLauncher.launch(needed.toTypedArray())
                } else {
                    container.settingsRepo.update { it.copy(backgroundAssistant = false) }
                    FridayService.stop(context)
                }
            },
            requestPermission = { permLauncher.launch(it) },
            isGranted = { Permissions.has(context, it) },
        )
    }

    Scaffold(
        containerColor = if (settings.amoled) FridayColors.Amoled else FridayColors.Dark,
        bottomBar = {
            NavigationBar(containerColor = if (settings.amoled) FridayColors.Amoled else FridayColors.Dark, modifier = Modifier.navigationBarsPadding()) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = FridayColors.PanelEdge),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().statusBarsPadding().let { it }.then(Modifier.padding(pad))) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    viewModel(factory = factory { HomeViewModel(container) }),
                    onNeedMic = { go -> if (Permissions.mic(context)) go() else { afterMic = go; micLauncher.launch(Manifest.permission.RECORD_AUDIO) } },
                )
                Tab.CHAT -> ConversationScreen(viewModel(factory = factory { ConversationViewModel(container) }))
                Tab.DIAG -> DiagnosticsScreen(viewModel(factory = factory { DiagnosticsViewModel(container) }))
                Tab.SETTINGS -> SettingsScreen(viewModel(factory = factory { SettingsViewModel(container) }), actions)
            }
        }
    }
}
