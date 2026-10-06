package com.jarvis.assistant

import android.app.Application
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.calendar.CalendarReader
import com.jarvis.assistant.command.LocalIntentParser
import com.jarvis.assistant.command.commands.PlaceResolver
import com.jarvis.assistant.core.HudState
import com.jarvis.assistant.data.database.JarvisDatabase
import com.jarvis.assistant.data.model.ThemeMode
import com.jarvis.assistant.data.repository.NoteRepository
import com.jarvis.assistant.data.repository.ReminderRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.data.repository.TaskRepository
import com.jarvis.assistant.gesture.GestureManager
import com.jarvis.assistant.holo.FRect
import com.jarvis.assistant.holo.HoloAction
import com.jarvis.assistant.holo.HoloContent
import com.jarvis.assistant.holo.HoloDataHub
import com.jarvis.assistant.holo.HoloInteraction
import com.jarvis.assistant.holo.HoloLayout
import com.jarvis.assistant.holo.HoloPanel
import com.jarvis.assistant.holo.HoloWindow
import com.jarvis.assistant.holo.HologramController
import com.jarvis.assistant.holo.WindowCommand
import com.jarvis.assistant.security.SecureStorage
import com.jarvis.assistant.ui.holo.HologramScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.weather.EnvironmentProvider
import com.jarvis.assistant.weather.GeoPoint
import com.jarvis.assistant.weather.LocationProvider
import com.jarvis.assistant.weather.WeatherException
import com.jarvis.assistant.weather.WeatherProvider
import com.jarvis.assistant.weather.WeatherReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class HoloLayoutTest {
    private fun rect(w: HoloWindow) = FRect(w.x, w.y, w.x + w.w, w.y + w.h)

    @Test
    fun everyCountFromFourToElevenFitsWithoutOverlap() {
        for (n in HoloPanel.MIN_OPEN..HoloPanel.MAX_OPEN) {
            val windows = HoloLayout.arrange(HoloLayout.defaultPanels(n))
            assertEquals(n, windows.size)
            windows.forEach { w ->
                assertTrue("n=$n ${w.panel} inside", w.x >= 0f && w.y >= HoloLayout.TOP - 0.001f && w.x + w.w <= 1f && w.y + w.h <= HoloLayout.BOTTOM + 0.001f)
                assertTrue("n=$n ${w.panel} has size", w.w > 0.2f && w.h > 0.05f)
            }
            for (i in windows.indices) for (j in i + 1 until windows.size) {
                assertFalse("n=$n overlap ${windows[i].panel}/${windows[j].panel}", rect(windows[i]).overlaps(rect(windows[j])))
            }
        }
    }

    @Test
    fun defaultsAreClampedAndUnique() {
        assertEquals(HoloPanel.MIN_OPEN, HoloLayout.defaultPanels(1).size)
        assertEquals(HoloPanel.MAX_OPEN, HoloLayout.defaultPanels(99).size)
        assertEquals(HoloPanel.values().size, HoloPanel.values().toSet().size)
        assertEquals(11, HoloPanel.values().size)
        assertEquals(11, HoloPanel.DEFAULT_ORDER.toSet().size)
    }

    @Test
    fun panelsAreRecognisedFromSpeech() {
        assertEquals(HoloPanel.WEATHER, HoloPanel.fromSpoken("날씨"))
        assertEquals(HoloPanel.TASKS, HoloPanel.fromSpoken("할 일"))
        assertEquals(HoloPanel.AIR, HoloPanel.fromSpoken("미세먼지"))
        assertEquals(HoloPanel.CALENDAR, HoloPanel.fromSpoken("일정"))
        assertEquals(HoloPanel.MUSIC, HoloPanel.fromSpoken("music"))
        assertNull(HoloPanel.fromSpoken("고양이"))
    }

    @Test
    fun aiSummaryDescribesTheWindow() {
        val c = HoloContent(HoloContent.Status.READY, big = "18°", sub = "구름 조금 · 서울", lines = listOf("체감 17°", "비 20%"))
        val text = c.forAi(HoloPanel.WEATHER)
        assertTrue(text.contains("WEATHER") && text.contains("18°") && text.contains("비 20%"))
        assertTrue(HoloContent(HoloContent.Status.EMPTY).forAi(HoloPanel.NOTES).contains("empty"))
    }
}

class HoloInteractionTest {
    private class Recorder : HoloInteraction.Callbacks {
        val log = mutableListOf<String>()
        var windows = HoloLayout.arrange(listOf(HoloPanel.WEATHER, HoloPanel.TASKS, HoloPanel.MUSIC, HoloPanel.SYSTEM))
        override fun focus(panel: HoloPanel?) { log += "focus:$panel" }
        override fun move(panel: HoloPanel, x: Float, y: Float) {
            log += "move:$panel"
            windows = windows.map { if (it.panel == panel) it.copy(x = x, y = y) else it }
        }
        override fun close(panel: HoloPanel) { log += "close:$panel" }
        override fun toggleMaximize(panel: HoloPanel) { log += "max:$panel" }
        override fun refresh(panel: HoloPanel) { log += "refresh:$panel" }
        override fun action(panel: HoloPanel, actionId: String) { log += "action:$panel:$actionId" }
        override fun bringToFront(panel: HoloPanel) { log += "front:$panel" }
        override fun hover(panel: HoloPanel?, x: Float, y: Float) { log += "hover:$panel" }
    }

    private fun setup(): Pair<Recorder, HoloInteraction> {
        val r = Recorder()
        val i = HoloInteraction({ r.windows }, r)
        i.actionsFor = { p -> if (p == HoloPanel.MUSIC) listOf(HoloAction("prev", "<"), HoloAction("toggle", "|>"), HoloAction("next", ">")) else emptyList() }
        return r to i
    }

    private fun centre(w: HoloWindow) = (w.x + w.w / 2) to (w.y + w.h / 2)

    @Test
    fun clickOnTheBodyTogglesMaximise() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.WEATHER }
        val (x, y) = centre(w)
        i.onDown(x, y)
        assertTrue(i.onUp(x, y))
        assertTrue(r.log.contains("max:WEATHER"))
    }

    @Test
    fun clickOnTheCornerClosesAndOnTheHeaderRefreshes() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.TASKS }
        val headerY = w.y + w.h * 0.05f
        i.onDown(w.x + w.w - 0.01f, headerY); i.onUp(w.x + w.w - 0.01f, headerY)
        assertTrue(r.log.contains("close:TASKS"))
        i.onDown(w.x + w.w * 0.3f, headerY); i.onUp(w.x + w.w * 0.3f, headerY)
        assertTrue(r.log.contains("refresh:TASKS"))
    }

    @Test
    fun actionButtonsMapToTheirSlot() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.MUSIC }
        val y = w.y + w.h * 0.9f
        fun tap(fx: Float) { val x = w.x + w.w * fx; i.onDown(x, y); i.onUp(x, y) }
        tap(0.1f); tap(0.5f); tap(0.9f)
        assertEquals(listOf("action:MUSIC:prev", "action:MUSIC:toggle", "action:MUSIC:next"), r.log.filter { it.startsWith("action") })
    }

    @Test
    fun draggingMovesTheWindowAndIsNotAClick() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.SYSTEM }
        val (sx, sy) = centre(w)
        i.onDown(sx, sy)
        for (k in 1..6) i.onMove(sx - 0.02f * k, sy - 0.02f * k)
        assertFalse(i.onUp(sx - 0.12f, sy - 0.12f))
        val moved = r.windows.first { it.panel == HoloPanel.SYSTEM }
        assertTrue("moved left/up: ${w.x}->${moved.x}", moved.x < w.x - 0.05f && moved.y < w.y - 0.05f)
        assertTrue(r.log.none { it.startsWith("max:") || it.startsWith("close:") })
    }

    @Test
    fun draggedWindowStaysOnScreen() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.WEATHER }
        val (sx, sy) = centre(w)
        i.onDown(sx, sy)
        i.onMove(5f, 5f); i.onMove(6f, 6f)
        i.onUp(6f, 6f)
        val m = r.windows.first { it.panel == HoloPanel.WEATHER }
        assertTrue(m.x + m.w <= 1.0001f && m.y + m.h <= 1.0001f)
    }

    @Test
    fun hoverReportsTheWindowUnderThePointer() {
        val (r, i) = setup()
        val w = r.windows.first { it.panel == HoloPanel.MUSIC }
        val (x, y) = centre(w)
        i.onHover(x, y)
        i.onHover(0.5f, 0.995f)
        assertEquals(listOf("hover:MUSIC", "hover:null"), r.log.filter { it.startsWith("hover") })
    }

    @Test
    fun emptyAreaDefocuses() {
        val (r, i) = setup()
        i.onDown(0.5f, 0.995f)
        assertTrue(r.log.contains("focus:null"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HologramControllerTest {
    private lateinit var app: Application
    private lateinit var db: JarvisDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    }

    @After
    fun tearDown() = db.close()

    private fun controller(scope: kotlinx.coroutines.CoroutineScope): HologramController {
        val settings = SettingsRepository(db.settingsDao(), SecureStorage(app), scope)
        return HologramController(scope, settings)
    }

    @Test
    fun openCloseArrangeAndFocus() = runTest {
        val c = controller(backgroundScope)
        c.openDeck(HoloLayout.defaultPanels(5))
        assertEquals(5, c.state.value.windows.size)
        c.open(HoloPanel.CLOCK)
        assertEquals(6, c.state.value.windows.size)
        assertEquals(HoloPanel.CLOCK, c.state.value.focused)
        assertFalse(c.state.value.windows.map { it.panel }.groupingBy { it }.eachCount().any { it.value > 1 })
        c.close(HoloPanel.CLOCK)
        assertEquals(5, c.state.value.windows.size)
        assertNull(c.state.value.focused)
        c.closeAll()
        assertTrue(c.state.value.windows.isEmpty())
    }

    @Test
    fun neverMoreThanElevenWindows() = runTest {
        val c = controller(backgroundScope)
        c.openDeck(HoloPanel.values().toList())
        assertEquals(11, c.state.value.windows.size)
        c.open(HoloPanel.WEATHER)
        assertEquals(11, c.state.value.windows.size)
    }

    @Test
    fun maximiseAndRestoreRoundTrip() = runTest {
        val c = controller(backgroundScope)
        c.openDeck(HoloLayout.defaultPanels(4))
        val before = c.state.value.windows.first { it.panel == HoloPanel.WEATHER }
        c.toggleMaximize(HoloPanel.WEATHER)
        val big = c.state.value.windows.first { it.panel == HoloPanel.WEATHER }
        assertTrue(big.maximized && big.w > before.w && big.h > before.h)
        c.restoreAll()
        val back = c.state.value.windows.first { it.panel == HoloPanel.WEATHER }
        assertFalse(back.maximized)
        assertEquals(before.x, back.x, 1e-6f)
        assertEquals(before.w, back.w, 1e-6f)
    }

    @Test
    fun moveToPointerCentresTheWindowThere() = runTest {
        val c = controller(backgroundScope)
        c.openDeck(HoloLayout.defaultPanels(4))
        assertFalse(c.moveToPointer(HoloPanel.WEATHER))      // no pointer known yet
        c.hover(null, 0.7f, 0.5f)
        assertTrue(c.moveToPointer(HoloPanel.WEATHER))
        val w = c.state.value.windows.first { it.panel == HoloPanel.WEATHER }
        assertEquals(0.7f, w.x + w.w / 2, 0.06f)    // clamped to the screen edge at most
    }

    @Test
    fun hoverWinsOverFocusAsTheTarget() = runTest {
        val c = controller(backgroundScope)
        c.openDeck(HoloLayout.defaultPanels(4))
        c.focus(HoloPanel.TASKS)
        assertEquals(HoloPanel.TASKS, c.state.value.target)
        c.hover(HoloPanel.WEATHER, 0.1f, 0.2f)
        assertEquals(HoloPanel.WEATHER, c.state.value.target)
    }

    // ---- voice -------------------------------------------------------------------------------
    private class Fake : WeatherProvider {
        override suspend fun forCity(city: String): WeatherReport = throw WeatherException(WeatherException.Kind.NETWORK, "x")
        override suspend fun locate(city: String): GeoPoint = throw WeatherException(WeatherException.Kind.NETWORK, "x")
        override suspend fun forPoint(lat: Double, lon: Double, label: String): WeatherReport =
            throw WeatherException(WeatherException.Kind.NETWORK, "x")
    }

    private fun hub(scope: kotlinx.coroutines.CoroutineScope): HoloDataHub {
        val settings = SettingsRepository(db.settingsDao(), SecureStorage(app), scope)
        return HoloDataHub(
            app, scope, Fake(), PlaceResolver(Fake(), LocationProvider(app), settings), EnvironmentProvider(),
            TaskRepository(db.taskDao()), NoteRepository(db.noteDao()), ReminderRepository(db.reminderDao()), CalendarReader(app),
        )
    }

    @Test
    fun windowCommandsDriveTheWorkspace() = runTest {
        val c = controller(backgroundScope)
        val cmd = WindowCommand(c, hub(backgroundScope))
        cmd.execute(AiAction("HOLOGRAM_OPEN", mapOf("count" to "5")))
        assertTrue(c.state.value.visible)
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        cmd.execute(AiAction("WINDOW_OPEN", mapOf("panel" to "음악")))
        assertTrue(c.state.value.openPanels.contains(HoloPanel.MUSIC))
        c.hover(HoloPanel.MUSIC, 0.5f, 0.5f)
        val r = cmd.execute(AiAction("WINDOW_CLOSE"))                   // "이거 닫아줘" = the pointed window
        assertTrue(r.success)
        assertFalse(c.state.value.openPanels.contains(HoloPanel.MUSIC))
        assertFalse(cmd.execute(AiAction("WINDOW_CLOSE")).success)      // nothing pointed at
        cmd.execute(AiAction("WINDOW_CLOSE_ALL"))
        assertTrue(c.state.value.windows.isEmpty())
        cmd.execute(AiAction("HOLOGRAM_CLOSE"))
        assertFalse(c.state.value.visible)
    }

    @Test
    fun contentLoadsPerWindowAndShowsErrorsGracefully() = runTest {
        val h = hub(backgroundScope)
        h.ensureLoaded(listOf(HoloPanel.TASKS, HoloPanel.WEATHER, HoloPanel.CLOCK))
        assertEquals(HoloContent.Status.LOADING, h.contentOf(HoloPanel.WEATHER).status)
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(HoloContent.Status.EMPTY, h.contentOf(HoloPanel.TASKS).status)       // no tasks yet
        assertEquals(HoloContent.Status.ERROR, h.contentOf(HoloPanel.WEATHER).status)      // offline weather
        assertEquals(HoloContent.Status.READY, h.contentOf(HoloPanel.CLOCK).status)
        runBlocking { TaskRepository(db.taskDao()).add("todo", "우유") }
        h.refresh(HoloPanel.TASKS)
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        assertTrue(h.contentOf(HoloPanel.TASKS).lines.any { it.contains("우유") })
    }

    @Test
    fun spokenWindowCommandsAreUnderstoodLocally() {
        fun p(t: String) = LocalIntentParser.parse(t)?.action
        assertEquals("HOLOGRAM_OPEN", p("홀로그램 켜줘")?.type)
        assertEquals("HOLOGRAM_CLOSE", p("홀로그램 꺼줘")?.type)
        assertEquals("WINDOW_OPEN", p("날씨 창 열어줘")?.type)
        assertEquals("날씨", p("날씨 창 열어줘")?.param("panel"))
        assertEquals("WINDOW_CLOSE", p("메모 창 닫아줘")?.type)
        assertEquals("WINDOW_CLOSE", p("이거 닫아줘")?.type)
        assertEquals("WINDOW_CLOSE_ALL", p("창 전부 닫아줘")?.type)
        assertEquals("WINDOW_ARRANGE", p("창 정리해줘")?.type)
        assertEquals("WINDOW_MAXIMIZE", p("이거 크게 해줘")?.type)
        assertEquals("WINDOW_MOVE_HERE", p("이거 여기로 옮겨줘")?.type)
        assertEquals("WINDOW_REFRESH", p("이거 새로고침해줘")?.type)
    }
}

/** Renders the real workspace with native drawing: boot-up, content, toolbar and the air cursor. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class HologramScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workspaceBootsRendersAndAcceptsPointerEvents() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java).allowMainThreadQueries().build()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        val settings = SettingsRepository(db.settingsDao(), SecureStorage(app), scope)
        val holo = HologramController(scope, settings)
        val fake = object : WeatherProvider {
            override suspend fun forCity(city: String): WeatherReport = throw WeatherException(WeatherException.Kind.NETWORK, "x")
            override suspend fun locate(city: String): GeoPoint = throw WeatherException(WeatherException.Kind.NETWORK, "x")
            override suspend fun forPoint(lat: Double, lon: Double, label: String): WeatherReport =
                throw WeatherException(WeatherException.Kind.NETWORK, "x")
        }
        val hub = HoloDataHub(
            app, scope, fake, PlaceResolver(fake, LocationProvider(app), settings), EnvironmentProvider(),
            TaskRepository(db.taskDao()), NoteRepository(db.noteDao()), ReminderRepository(db.reminderDao()), CalendarReader(app),
        )
        holo.openDeck(HoloLayout.defaultPanels(11))
        val pointer = MutableStateFlow(GestureManager.Pointer(true, 0.5f, 0.4f, com.jarvis.assistant.gesture.HandPose.POINT, false, 0.5f))
        val events = MutableSharedFlow<GestureManager.PointerEvent>(extraBufferCapacity = 8)
        val tracking = MutableStateFlow("MediaPipe hand tracking")
        val level = mutableFloatStateOf(0.2f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            JarvisTheme(ThemeMode.AMOLED) {
                HologramScreen(
                    holo = holo, hub = hub, hud = HudState(), level = level,
                    pointerFlow = pointer, pointerEvents = events, trackingEngine = tracking,
                    handsEnabled = true, handsPermitted = true, parallax = false,
                    onAsk = {}, onExit = {}, onEnableHands = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText("HOLOGRAPHIC PROJECTOR").assertExists()
        compose.onNodeWithText("OPEN").assertExists()
        compose.onNodeWithText("11 / 11 WINDOWS · MEDIAPIPE HAND TRACKING").assertExists()
        // Air cursor: pinch on the first window's body toggles maximise.
        val w = holo.state.value.windows.first()
        events.tryEmit(GestureManager.PointerEvent.Down(w.x + w.w / 2, w.y + w.h / 2))
        events.tryEmit(GestureManager.PointerEvent.Up(w.x + w.w / 2, w.y + w.h / 2))
        compose.mainClock.advanceTimeBy(500)
        assertTrue(holo.state.value.windows.first { it.panel == w.panel }.maximized)
        assertNotNull(holo.state.value.focused)
        db.close()
    }
}
