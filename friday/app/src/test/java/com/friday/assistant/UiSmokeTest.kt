package com.friday.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.friday.assistant.core.CoreState
import com.friday.assistant.data.MessageEntity
import com.friday.assistant.diag.DiagResult
import com.friday.assistant.diag.DiagStatus
import com.friday.assistant.diag.DiagTest
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.ui.boot.BootScreen
import com.friday.assistant.ui.conversation.ConversationContent
import com.friday.assistant.ui.diag.DiagnosticsContent
import com.friday.assistant.ui.home.HomeContent
import com.friday.assistant.ui.settings.SettingsActions
import com.friday.assistant.ui.settings.SettingsContent
import com.friday.assistant.ui.theme.FridayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens. The theme sets reduce-motion so the HUD draws static frames and Compose can reach idle
 * (infinite animations would otherwise make the test clock wait forever).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { FridayTheme(reduceMotion = true) { content() } }

    @Test fun startupSequenceFinishes() {
        var finished = false
        show { BootScreen(onFinished = { finished = true }, stepMs = 10L) }
        repeat(60) { compose.mainClock.advanceTimeBy(16) } // frame by frame so each boot line is rendered
        compose.waitForIdle()
        assertTrue(finished)
        compose.onNodeWithText("FRIDAY ONLINE").assertExists()
    }

    @Test fun homeHudRendersEveryCoreState() {
        var state by mutableStateOf(CoreState.IDLE)
        show { HomeContent(state, "배터리 얼마나 남았어?", "현재 배터리는 72%입니다.", true, "FRIDAY", { 0.5f }, {}, {}) }
        CoreState.entries.forEach { s -> state = s; compose.waitForIdle() }
        compose.onNodeWithText("현재 배터리는 72%입니다.").assertIsDisplayed()
        compose.onNodeWithText("“배터리 얼마나 남았어?”").assertIsDisplayed()
    }

    @Test fun homeMicButtonInvokesCallback() {
        var clicks = 0
        show { HomeContent(CoreState.IDLE, "", "", false, "FRIDAY", { 0f }, { clicks++ }, {}) }
        compose.onNodeWithContentDescription("Talk to FRIDAY").performClick()
        assertEquals(1, clicks)
    }

    @Test fun homeStopButtonWhileSpeaking() {
        var stops = 0
        show { HomeContent(CoreState.SPEAKING, "", "안녕하세요.", true, "FRIDAY", { 0.3f }, {}, { stops++ }) }
        compose.onNodeWithContentDescription("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test fun settingsRenders() {
        val actions = SettingsActions({}, {}, {})
        show {
            SettingsContent(FridaySettings(), hasAiKey = false, hasTtsKey = false, voices = listOf("en-us-x-tpf-local"), message = null,
                actions = actions, update = {}, saveAiKey = {}, saveTtsKey = {}, loadVoices = {}, preview = {})
        }
        compose.onNodeWithText("Background Assistant").assertExists()
        compose.onNodeWithText("AI Model").assertExists()
    }

    @Test fun conversationRendersMessagesAndEmptyState() {
        val msgs = listOf(
            MessageEntity(1, 1, "user", "유튜브 열어줘", "", 1),
            MessageEntity(2, 1, "assistant", "Opening YouTube.", "유튜브를 실행합니다.", 2),
        )
        var list by mutableStateOf(msgs)
        show { ConversationContent(list, {}, {}) }
        compose.onNodeWithText("유튜브를 실행합니다.").assertIsDisplayed()
        list = emptyList()
        compose.waitForIdle()
        compose.onNodeWithText("대화 기록이 없습니다.").assertIsDisplayed()
    }

    @Test fun diagnosticsRendersResults() {
        val tests = listOf(DiagTest("a", "Microphone Test", "hint"), DiagTest("b", "AI API Test", "hint"), DiagTest("c", "Weather Test", "hint"))
        val results = mapOf(
            "a" to DiagResult(DiagStatus.PASS, "ok"), "b" to DiagResult(DiagStatus.NOT_CONFIGURED, "No API key"), "c" to DiagResult(DiagStatus.FAIL, "offline"),
        )
        show { DiagnosticsContent(tests, results, null, {}, {}) }
        compose.onNodeWithText("PASS").assertIsDisplayed()
        compose.onNodeWithText("NOT CONFIGURED").assertIsDisplayed()
        compose.onNodeWithText("FAIL").assertIsDisplayed()
    }

    @Test fun ambientModeShowsTimeAndLeavesOnTap() {
        var exited = 0
        show { com.friday.assistant.ui.ambient.AmbientScreen(CoreState.IDLE, "", 1f, { 0f }, { exited++ }, { java.time.LocalTime.of(7, 32) }) }
        compose.onNodeWithText("07:32").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ambient mode. Tap to leave.").performClick()
        assertEquals(1, exited)
    }

    @Test fun contextCardAppearsOnlyWhenThereIsOne() {
        var card by mutableStateOf<com.friday.assistant.command.InfoCard?>(null)
        show { HomeContent(CoreState.IDLE, "", "", false, "FRIDAY", { 0f }, {}, {}, card = card) }
        compose.onNodeWithContentDescription("CALENDAR card").assertDoesNotExist()
        card = com.friday.assistant.command.InfoCard(com.friday.assistant.command.CardKind.CALENDAR, "오늘 일정 2", listOf("오전 10시 30분 Team sync"))
        compose.waitForIdle()
        compose.onNodeWithText("오늘 일정 2").assertIsDisplayed()
        compose.onNodeWithText("오전 10시 30분 Team sync").assertIsDisplayed()
    }

    @Test fun permissionCenterRowsOfferTheRightButtons() {
        val perms = listOf(
            com.friday.assistant.permission.PermItem("mic", "Microphone", "why", com.friday.assistant.permission.PermStatus.DENIED, com.friday.assistant.permission.PermKind.RUNTIME, "android.permission.RECORD_AUDIO", true),
            com.friday.assistant.permission.PermItem("listener", "Notification Access", "why", com.friday.assistant.permission.PermStatus.SETTINGS_REQUIRED, com.friday.assistant.permission.PermKind.SPECIAL),
        )
        var requested = ""; var opened = ""
        val actions = SettingsActions({}, { requested = it }, { opened = it.id })
        show {
            SettingsContent(FridaySettings(), false, false, emptyList(), null, actions, {}, {}, {}, {}, {}, perms = perms)
        }
        compose.onNodeWithText("OPEN SETTINGS").performScrollTo().performClick()
        assertEquals("listener", opened)
        compose.onNodeWithText("ALLOW").performScrollTo().performClick()
        assertEquals("android.permission.RECORD_AUDIO", requested)
        compose.onNodeWithText("DENIED").assertExists()
        compose.onNodeWithText("SETTINGS REQUIRED").assertExists()
    }

    @Test fun wholeSwitchRowIsTheTouchTarget() {
        var settings by mutableStateOf(FridaySettings(followUpEnabled = true))
        show {
            SettingsContent(settings, false, false, emptyList(), null, SettingsActions({}, {}, {}), { t -> settings = t(settings) }, {}, {}, {}, {})
        }
        // tapping the label text (not the small switch) must toggle
        compose.onNodeWithText("Follow-up Mode").performScrollTo().performClick()
        assertEquals(false, settings.followUpEnabled)
        compose.onNodeWithText("Keep listening after an answer, no need to say FRIDAY again").performScrollTo().performClick()
        assertEquals(true, settings.followUpEnabled)
    }

    @Test fun diagnosticsShowsLatencyAndDeviceTestStatus() {
        val tests = listOf(DiagTest("f", "Follow-up Mode", "hint"))
        val results = mapOf("f" to DiagResult(DiagStatus.DEVICE_TEST_REQUIRED, "Say FRIDAY twice"))
        show { DiagnosticsContent(tests, results, null, {}, {}, com.friday.assistant.core.LatencyReport(ai = 300, total = 900)) }
        compose.onNodeWithText("DEVICE TEST REQUIRED").assertIsDisplayed()
        compose.onNodeWithText("300 ms").assertIsDisplayed()
        compose.onNodeWithText("900 ms").assertIsDisplayed()
        compose.onNodeWithText("Wake latency").assertIsDisplayed()
    }

    @Test fun iconButtonsHaveDescriptions() {
        show { HomeContent(CoreState.IDLE, "", "", false, "FRIDAY", { 0f }, {}, {}) }
        compose.onNodeWithContentDescription("Talk to FRIDAY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ambient mode").assertIsDisplayed()
    }
}
