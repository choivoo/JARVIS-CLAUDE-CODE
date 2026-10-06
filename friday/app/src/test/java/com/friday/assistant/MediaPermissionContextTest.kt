package com.friday.assistant

import android.Manifest
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.LocalIntentParser
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.core.ConfirmationManager
import com.friday.assistant.core.LatencyTracker
import com.friday.assistant.media.MediaAction
import com.friday.assistant.media.MediaExecutors
import com.friday.assistant.media.MediaSessionInfo
import com.friday.assistant.permission.PermKind
import com.friday.assistant.permission.PermStatus
import com.friday.assistant.permission.PermissionCenter
import com.friday.assistant.permission.PermissionSnapshot
import com.friday.assistant.proactive.AlertKind
import com.friday.assistant.proactive.LowBatterySource
import com.friday.assistant.proactive.ProactiveEngine
import com.friday.assistant.proactive.UpcomingEventSource
import com.friday.assistant.proactive.WeatherWarningSource
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.weather.DayForecast
import com.friday.assistant.weather.WeatherReport
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionContextTest {
    // ---- media ----------------------------------------------------------------------------------------------

    private fun media(b: FakeMedia) = CommandRouter(MediaExecutors(b, settleMs = 0).all())

    @Test fun controlsUseTheSessionWhenAvailable() = runTest {
        val b = FakeMedia()
        val r = media(b)
        assertTrue(r.execute(Command(CommandType.PLAY_MEDIA)).ok)
        assertTrue(r.execute(Command(CommandType.NEXT_MEDIA)).ok)
        assertTrue(r.execute(Command(CommandType.PAUSE_MEDIA)).ok)
        assertEquals(listOf(MediaAction.PLAY, MediaAction.NEXT, MediaAction.PAUSE), b.transports)
        assertTrue("no key events needed", b.keys.isEmpty())
    }

    @Test fun fallsBackToMediaKeysAndDoesNotClaimSuccessWithoutProof() = runTest {
        val b = FakeMedia(transportWorks = false)
        val play = media(b).execute(Command(CommandType.PLAY_MEDIA))
        assertEquals(listOf(MediaAction.PLAY), b.keys)
        assertEquals(ResultStatus.FAILED, play.status)
        assertTrue(play.speech.contains("nothing started"))
        b.musicActive = true
        val pause = media(b).execute(Command(CommandType.PAUSE_MEDIA))
        assertEquals("audio still playing after a pause key is a failure", ResultStatus.FAILED, pause.status)
        assertTrue(media(b).execute(Command(CommandType.NEXT_MEDIA)).ok)
    }

    @Test fun stopCommandExists() = runTest {
        val b = FakeMedia(musicActive = true)
        assertTrue(media(b).execute(Command(CommandType.STOP_MEDIA)).ok)
        assertEquals(listOf(MediaAction.STOP), b.transports)
    }

    @Test fun mediaStateWithAccess() = runTest {
        val b = FakeMedia(session = MediaSessionInfo("p", "Spotify", "Blinding Lights", "The Weeknd", true))
        val s = media(b).execute(Command(CommandType.GET_MEDIA_STATE))
        assertEquals("Now playing Blinding Lights by The Weeknd on Spotify.", s.speech)
        assertTrue(s.subtitle.contains("재생 중") && s.subtitle.contains("The Weeknd"))
        assertEquals("Blinding Lights", s.card!!.title)
    }

    @Test fun koreanTitleIsShownNotSpoken() = runTest {
        val s = media(FakeMedia(session = MediaSessionInfo("p", "YouTube Music", "봄날", "BTS", true))).execute(Command(CommandType.GET_MEDIA_STATE))
        assertFalse(s.speech.any { it in '가'..'힣' })
        assertTrue(s.subtitle.contains("봄날"))
    }

    @Test fun mediaStateWithoutAccessExplainsWhy() = runTest {
        val playing = media(FakeMedia(access = false, musicActive = true)).execute(Command(CommandType.GET_MEDIA_STATE))
        assertTrue(playing.speech.contains("Notification Access"))
        assertTrue(media(FakeMedia(access = false)).execute(Command(CommandType.GET_MEDIA_STATE)).speech.contains("Nothing"))
        assertTrue(media(FakeMedia(access = true, session = null)).execute(Command(CommandType.GET_MEDIA_STATE)).speech.contains("Nothing is playing"))
    }

    @Test fun musicCommandsAreRecognisedLocally() {
        fun t(s: String) = LocalIntentParser.parse(s)?.type
        assertEquals(CommandType.GET_MEDIA_STATE, t("지금 무슨 노래야?"))
        assertEquals(CommandType.PLAY_MEDIA, t("음악 틀어줘"))
        assertEquals(CommandType.PAUSE_MEDIA, t("음악 멈춰"))
        assertEquals(CommandType.NEXT_MEDIA, t("다음 곡"))
        assertEquals(CommandType.PREVIOUS_MEDIA, t("이전 곡"))
        val yt = LocalIntentParser.parse("유튜브 뮤직 열어줘")!!
        assertEquals(CommandType.OPEN_APP, yt.type); assertEquals("유튜브 뮤직", yt.param("target"))
        assertEquals("스포티파이", LocalIntentParser.parse("스포티파이 열어줘")!!.param("target"))
    }

    // ---- more local intents ---------------------------------------------------------------------------------

    @Test fun notificationDeviceAndAppIntents() {
        fun c(s: String) = LocalIntentParser.parse(s)
        assertEquals(CommandType.GET_NOTIFICATIONS, c("새 알림 있어?")?.type)
        assertEquals(CommandType.GET_NOTIFICATION_COUNT, c("알림 몇 개야?")?.type)
        assertEquals(CommandType.READ_LATEST_NOTIFICATION, c("알림 읽어줘")?.type)
        val k = c("카카오톡 알림 읽어줘")!!
        assertEquals(CommandType.READ_NOTIFICATIONS_FROM_APP, k.type); assertEquals("카카오톡", k.param("target"))
        assertEquals(CommandType.DISMISS_NOTIFICATION, c("알림 지워줘")?.type)
        assertEquals(CommandType.GET_CHARGING_STATE, c("충전 중이야?")?.type)
        assertEquals(CommandType.GET_BATTERY, c("충전 얼마나 남았어?")?.type)
        assertEquals(CommandType.GET_NETWORK_STATE, c("인터넷 연결됐어?")?.type)
        assertEquals(CommandType.GET_VOLUME, c("볼륨 몇 퍼센트야?")?.type)
        assertEquals(CommandType.GET_STORAGE, c("저장 공간 얼마나 남았어?")?.type)
        assertEquals(CommandType.OPEN_CAMERA, c("카메라 열어줘")?.type)
        assertEquals(CommandType.OPEN_CLOCK, c("시계 열어줘")?.type)
        assertEquals(CommandType.OPEN_CALENDAR, c("캘린더 열어줘")?.type)
        assertEquals(CommandType.OPEN_MAPS, c("지도 열어줘")?.type)
        assertEquals(CommandType.OPEN_BROWSER, c("브라우저 열어줘")?.type)
        assertEquals("wifi", c("와이파이 설정 열어줘")?.param("target"))
        val appSettings = c("카카오톡 설정 열어줘")!!
        assertEquals(CommandType.OPEN_APP_SETTINGS, appSettings.type); assertEquals("카카오톡", appSettings.param("target"))
        assertEquals(CommandType.OPEN_SETTINGS, c("설정 열어줘")?.type)
        val q = c("카카오톡 앱 설치돼 있어?")!!
        assertEquals(CommandType.SEARCH_INSTALLED_APPS, q.type); assertEquals("카카오톡", q.param("query"))
        assertNull("needs the AI", c("그럼 우산 필요할까?"))
    }

    // ---- confirmation ---------------------------------------------------------------------------------------

    @Test fun confirmationManagerOutcomes() {
        var t = 0L
        val m = ConfirmationManager({ t }, ttlMs = 30_000)
        val cmd = Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to "엄마"))
        assertEquals(ConfirmationManager.Outcome.Nothing, m.resolve("응"))
        m.hold(cmd); assertTrue(m.hasPending)
        assertEquals(ConfirmationManager.Outcome.Confirmed(cmd), m.resolve("응, 걸어줘"))
        assertFalse("single use", m.hasPending)
        m.hold(cmd); assertEquals(ConfirmationManager.Outcome.Declined, m.resolve("아니"))
        m.hold(cmd); assertEquals(ConfirmationManager.Outcome.NewRequest, m.resolve("오늘 날씨 알려줘"))
        m.hold(cmd); t = 31_000
        assertFalse("expired", m.hasPending)
        assertEquals("a late yes must never confirm", ConfirmationManager.Outcome.NewRequest, m.resolve("응"))
        m.hold(cmd); m.clear(); assertEquals(ConfirmationManager.Outcome.Nothing, m.resolve("응"))
    }

    // ---- latency --------------------------------------------------------------------------------------------

    @Test fun latencyOnlyContainsMeasuredStages() {
        var t = 0L
        val l = LatencyTracker { t }
        l.wakeDetected(); t = 120; l.listeningStarted()
        t = 1_620; l.sttFinished()
        l.aiFinished(400); l.commandFinished(35)
        t = 1_700; l.ttsRequested(); t = 1_950; l.firstAudio()
        val r = l.last!!
        assertEquals(120L, r.wake); assertEquals(1_500L, r.stt); assertEquals(400L, r.ai); assertEquals(35L, r.command)
        assertEquals(250L, r.ttsFirstAudio); assertEquals(330L, r.total)

        l.reset(); l.textReceived(); t = 3_000; l.ttsRequested(); t = 3_100; l.firstAudio()
        val typed = l.last!!
        assertNull("no wake word was used", typed.wake); assertNull("typed input has no STT", typed.stt); assertNull("no AI call", typed.ai)
        assertEquals(100L, typed.ttsFirstAudio)
    }

    // ---- permission center ----------------------------------------------------------------------------------

    private fun snap(sdk: Int = 34, granted: Set<String> = emptySet(), asked: Set<String> = emptySet(), listener: Boolean = false, overlay: Boolean = false) =
        PermissionSnapshot(sdk, { it in granted }, { it in asked }, listener, overlay)

    private fun status(items: List<com.friday.assistant.permission.PermItem>, id: String) = items.first { it.id == id }.status

    @Test fun statusesDistinguishNotRequestedDeniedGrantedAndSettings() {
        val none = PermissionCenter.items(snap())
        assertEquals(PermStatus.NOT_REQUESTED, status(none, "mic"))
        assertEquals(PermStatus.SETTINGS_REQUIRED, status(none, "listener"))
        assertEquals(PermStatus.SETTINGS_REQUIRED, status(none, "overlay"))
        assertEquals(PermStatus.NOT_REQUESTED, status(none, "fgs"))

        val denied = PermissionCenter.items(snap(asked = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)))
        assertEquals(PermStatus.DENIED, status(denied, "mic"))
        assertEquals(PermStatus.DENIED, status(denied, "calendar"))
        assertEquals(PermStatus.NOT_REQUESTED, status(denied, "calendar_w"))

        val ok = PermissionCenter.items(snap(granted = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), listener = true))
        assertEquals(PermStatus.GRANTED, status(ok, "mic"))
        assertEquals(PermStatus.GRANTED, status(ok, "fgs"))
        assertEquals(PermStatus.GRANTED, status(ok, "listener"))
        assertEquals(PermKind.SPECIAL, ok.first { it.id == "listener" }.kind)
        assertTrue(ok.first { it.id == "mic" }.required)
        assertTrue("every item explains why", ok.all { it.why.isNotBlank() })
    }

    @Test fun notificationPermissionDoesNotExistBeforeAndroid13() {
        val old = PermissionCenter.items(snap(sdk = 31, granted = setOf(Manifest.permission.RECORD_AUDIO)))
        assertEquals(PermStatus.GRANTED, status(old, "notif"))
        assertEquals("background assistant is ready on Android 12 with just the mic", PermStatus.GRANTED, status(old, "fgs"))
        assertEquals(PermStatus.NOT_REQUESTED, status(PermissionCenter.items(snap(sdk = 34)), "notif"))
    }

    // ---- proactive ------------------------------------------------------------------------------------------

    private val now = LocalDateTime.of(2026, 10, 6, 9, 0)
    private fun engine(s: FridaySettings, battery: Int = 10, charging: Boolean = false, cal: FakeCalendar = FakeCalendar(listOf(ev("회의", 2026, 10, 6, 9, 10))), rain: Int = 90) =
        ProactiveEngine(
            { s },
            mapOf(
                AlertKind.LOW_BATTERY to LowBatterySource(FakeDevice(FakeDevice.defaultStatus.copy(batteryPercent = battery, charging = charging))),
                AlertKind.UPCOMING_EVENT to UpcomingEventSource(cal),
                AlertKind.WEATHER_WARNING to WeatherWarningSource { WeatherReport("S", 20.0, 20.0, "rain", DayForecast(20.0, 10.0, rain, "rain"), null) },
            ),
        )

    @Test fun allOffByDefault() = runTest {
        assertTrue(engine(FridaySettings()).evaluate(now).isEmpty())
    }

    @Test fun eachSourceFiresOnlyWhenEnabledAndNeeded() = runTest {
        val all = FridaySettings(proactiveLowBattery = true, proactiveUpcomingEvent = true, proactiveWeather = true)
        val kinds = engine(all).evaluate(now).map { it.kind }.toSet()
        assertEquals(setOf(AlertKind.LOW_BATTERY, AlertKind.UPCOMING_EVENT, AlertKind.WEATHER_WARNING), kinds)
        assertTrue("charging suppresses the low battery alert", engine(all, charging = true).evaluate(now).none { it.kind == AlertKind.LOW_BATTERY })
        assertTrue(engine(all, battery = 80).evaluate(now).none { it.kind == AlertKind.LOW_BATTERY })
        assertTrue(engine(all, rain = 30).evaluate(now).none { it.kind == AlertKind.WEATHER_WARNING })
        assertTrue(engine(all, cal = FakeCalendar(read = false)).evaluate(now).none { it.kind == AlertKind.UPCOMING_EVENT })
        assertEquals(listOf(AlertKind.LOW_BATTERY), engine(FridaySettings(proactiveLowBattery = true)).evaluate(now).map { it.kind })
    }

    @Test fun alertsAreNotRepeatedAndCanBeScoped() = runTest {
        val e = engine(FridaySettings(proactiveLowBattery = true, proactiveUpcomingEvent = true))
        assertEquals(2, e.evaluate(now).size)
        assertTrue(e.evaluate(now.plusMinutes(1)).isEmpty())
        e.reset()
        assertEquals(1, e.evaluate(now, only = setOf(AlertKind.LOW_BATTERY)).size)
    }
}
