package com.jarvis.assistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.core.HudState
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.database.MessageEntity
import com.jarvis.assistant.data.database.Role
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.model.ThemeMode
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.security.SecureStorage
import com.jarvis.assistant.tts.AndroidTTSProvider
import com.jarvis.assistant.tts.JarvisSpeaker
import com.jarvis.assistant.tts.PcmPlayer
import com.jarvis.assistant.ui.boot.BootScreen
import com.jarvis.assistant.ui.conversation.ConversationScreen
import com.jarvis.assistant.ui.home.HomeScreen
import com.jarvis.assistant.ui.settings.PermissionActions
import com.jarvis.assistant.ui.settings.PermissionStatus
import com.jarvis.assistant.ui.settings.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.util.AudioLevelBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real screens (with native drawing) to catch composition and draw-phase crashes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @org.junit.Before
    fun manualClock() {
        // The core animates forever, so the test clock must be driven by hand.
        compose.mainClock.autoAdvance = false
    }

    private val messages = listOf(
        MessageEntity(2, 1, Role.JARVIS, "Certainly. I'll open YouTube.", "물론입니다. 유튜브를 엽니다.", null, 2),
        MessageEntity(1, 1, Role.USER, "유튜브 열어줘", null, null, 1),
    )

    @Test
    fun homeScreenRendersEveryPhaseAndTheme() {
        val level = mutableFloatStateOf(0.6f)
        var phase by mutableStateOf(AssistantPhase.IDLE)
        var theme by mutableStateOf(ThemeMode.AMOLED)
        compose.setContent {
            JarvisTheme(theme) {
                HomeScreen(
                    hud = HudState(
                        phase = phase,
                        standby = true,
                        subtitle = "물론입니다. 유튜브를 엽니다.",
                        notice = if (phase == AssistantPhase.ERROR) "마이크 권한이 필요합니다." else null,
                        partial = if (phase == AssistantPhase.LISTENING) "유튜브 열" else "",
                    ),
                    level = level,
                    messages = messages,
                    micGranted = true,
                    onRequestMic = {}, onMic = {}, onToggleStandby = {}, onSubmitText = {},
                    onOpenSettings = {}, onOpenHistory = {},
                )
            }
        }
        for (t in ThemeMode.values()) {
            for (p in AssistantPhase.values()) {
                theme = t
                phase = p
                compose.mainClock.advanceTimeBy(700)
            }
        }
        compose.onNodeWithText("J.A.R.V.I.S").assertIsDisplayed()
    }

    @Test
    fun homeShowsPermissionPanelWithoutMicrophone() {
        compose.setContent {
            JarvisTheme(ThemeMode.AMOLED) {
                HomeScreen(
                    hud = HudState(), level = mutableFloatStateOf(0f), messages = emptyList(), micGranted = false,
                    onRequestMic = {}, onMic = {}, onToggleStandby = {}, onSubmitText = {},
                    onOpenSettings = {}, onOpenHistory = {},
                )
            }
        }
        compose.onNodeWithText("MICROPHONE ACCESS REQUIRED").assertIsDisplayed()
        compose.onNodeWithText("SYSTEM READY").assertIsDisplayed()
    }

    @Test
    fun micButtonInvokesCallback() {
        var taps = 0
        compose.setContent {
            JarvisTheme(ThemeMode.AMOLED) {
                HomeScreen(
                    hud = HudState(), level = mutableFloatStateOf(0f), messages = emptyList(), micGranted = true,
                    onRequestMic = {}, onMic = { taps++ }, onToggleStandby = {}, onSubmitText = {},
                    onOpenSettings = {}, onOpenHistory = {},
                )
            }
        }
        compose.onNodeWithText("SPEAK").performClick()
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, taps)
    }

    @Test
    fun bootSequenceFinishes() {
        var done = false
        compose.setContent { JarvisTheme(ThemeMode.AMOLED) { BootScreen(onFinished = { done = true }) } }
        compose.mainClock.advanceTimeBy(6_000)
        assertEquals(true, done)
        compose.onNodeWithText("JARVIS READY").assertIsDisplayed()
    }

    @Test
    fun conversationScreenListsMessages() {
        compose.setContent {
            JarvisTheme(ThemeMode.AMOLED) { ConversationScreen(messages, onBack = {}, onClear = {}) }
        }
        compose.onNodeWithText("MEMORY LOG").assertIsDisplayed()
        compose.onNodeWithText("물론입니다. 유튜브를 엽니다.").assertIsDisplayed()
    }

    @Test
    fun settingsScreenRenders() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val repo = SettingsRepository(db.settingsDao(), SecureStorage(app), scope)
        val levels = AudioLevelBus()
        val speaker = JarvisSpeaker(app, repo, AndroidTTSProvider(app), emptyMap(), PcmPlayer(levels), levels)
        compose.setContent {
            JarvisTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = AppSettings(),
                    repo = repo,
                    speaker = speaker,
                    permissions = PermissionStatus(mic = true, notifications = false, location = false, overlay = false),
                    actions = PermissionActions({}, {}, {}, {}, {}),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
        compose.onNodeWithText("AI CORE").assertIsDisplayed()
        db.close()
    }
}
