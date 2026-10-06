package com.friday.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.command.ActivityLauncher
import com.friday.assistant.command.AndroidExecutors
import com.friday.assistant.command.AppResolver
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ContactResolver
import com.friday.assistant.command.LocationHelper
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.search.SearchHit
import com.friday.assistant.search.WebSearchProvider
import com.friday.assistant.weather.DayForecast
import com.friday.assistant.weather.WeatherException
import com.friday.assistant.weather.WeatherProvider
import com.friday.assistant.weather.WeatherReport
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real Android implementations, exercised against Robolectric's system services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidExecutorsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var weatherResult: () -> WeatherReport = {
        WeatherReport("Seoul", 18.4, 16.0, "partly cloudy", DayForecast(22.0, 12.0, 70, "rain"), DayForecast(24.0, 13.0, 10, "clear sky"))
    }
    private var searchResult: () -> List<SearchHit> = { listOf(SearchHit("원신 5.0 업데이트", "새 지역 추가", "https://x")) }

    private fun router(now: LocalDateTime = LocalDateTime.of(2026, 10, 6, 9, 0), foreground: Boolean = true): CommandRouter {
        val weather = object : WeatherProvider { override suspend fun fetch(city: String?, latitude: Double?, longitude: Double?) = weatherResult() }
        val search = object : WebSearchProvider { override suspend fun search(query: String, limit: Int) = searchResult() }
        val launcher = ActivityLauncher(app) { foreground }
        val apps = AppResolver(app)
        val all = LinkedHashMap<CommandType, com.friday.assistant.command.CommandExecutor>()
        all += AndroidExecutors(app, launcher, apps, weather, search, ContactResolver(app), LocationHelper(app), { "Seoul" }) { now }.all()
        all += com.friday.assistant.command.AppControlExecutors(app, launcher, apps).all()
        all += com.friday.assistant.device.DeviceExecutors(FakeDevice()).all()
        all += com.friday.assistant.media.MediaExecutors(FakeMedia(), settleMs = 0).all()
        all += com.friday.assistant.notification.NotificationExecutors(FakeNotifications()).all()
        val ctx = com.friday.assistant.core.ContextEngine()
        all += com.friday.assistant.calendar.CalendarExecutors(FakeCalendar(), ctx, { _, _, _ -> true }, { now }).all()
        all += com.friday.assistant.brief.BriefingComposer({ now }, { weatherResult() }, FakeCalendar(), FakeNotifications(), FakeDevice()).executors()
        return CommandRouter(all)
    }

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test fun everyAllowListedTypeHasAnExecutor() = runTest {
        val missing = CommandType.entries.filter {
            router().execute(Command(it)).status == ResultStatus.UNSUPPORTED
        }
        assertTrue("no executor for $missing", missing.isEmpty())
    }

    @Test fun timeAndDate() = runTest {
        val r = router()
        assertEquals("It's 9:00 AM.", r.execute(Command(CommandType.GET_TIME)).speech)
        assertEquals("현재 시각은 오전 9시 0분입니다.", r.execute(Command(CommandType.GET_TIME)).subtitle)
        val d = r.execute(Command(CommandType.GET_DATE))
        assertEquals("Today is Tuesday, October 6.", d.speech)
        assertEquals("오늘은 2026년 10월 6일 화요일입니다.", d.subtitle)
    }

    @Test fun alarmTomorrowMorningIsExact() = runTest {
        val res = router().execute(Command(CommandType.SET_ALARM, mapOf("hour" to "7", "minute" to "0", "dayOffset" to "1")))
        assertTrue(res.ok)
        val i = started()!!
        assertEquals(AlarmClock.ACTION_SET_ALARM, i.action)
        assertEquals(7, i.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(0, i.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test fun alarmThatCannotBeExactAsksClockAppToConfirm() = runTest {
        // 05:00 now; "tomorrow 7:00" is NOT the next occurrence (that is today 7:00).
        val res = router(LocalDateTime.of(2026, 10, 6, 5, 0)).execute(Command(CommandType.SET_ALARM, mapOf("hour" to "7", "minute" to "0", "dayOffset" to "1")))
        assertTrue(res.ok)
        assertFalse(started()!!.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(res.speech.contains("confirm"))
    }

    @Test fun alarmWithoutTimeAsks() = runTest {
        assertEquals(ResultStatus.FAILED, router().execute(Command(CommandType.SET_ALARM)).status)
    }

    @Test fun openUrlOnlyAllowsHttp() = runTest {
        assertEquals(ResultStatus.FAILED, router().execute(Command(CommandType.OPEN_URL, mapOf("url" to "javascript:alert(1)"))).status)
        assertEquals(ResultStatus.FAILED, router().execute(Command(CommandType.OPEN_URL, mapOf("url" to "file:///etc/passwd"))).status)
        assertNull(started())
        assertTrue(router().execute(Command(CommandType.OPEN_URL, mapOf("url" to "https://example.com"))).ok)
        assertEquals(Intent.ACTION_VIEW, started()!!.action)
    }

    @Test fun unknownAppIsReportedHonestly() = runTest {
        val r = router().execute(Command(CommandType.OPEN_APP, mapOf("target" to "존재하지않는앱")))
        assertEquals(ResultStatus.FAILED, r.status)
        assertEquals("I couldn't find that application.", r.speech)
        assertEquals("해당 앱을 찾을 수 없습니다.", r.subtitle)
    }

    @Test fun youtubeFallsBackToBrowserWhenAppMissing() = runTest {
        assertTrue(router().execute(Command(CommandType.YOUTUBE_SEARCH, mapOf("query" to "포켓몬"))).ok)
        val i = started()!!
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("youtube.com", i.data!!.host!!.removePrefix("www."))
        assertEquals("포켓몬", i.data!!.getQueryParameter("search_query"))
    }

    @Test fun settingsTargets() = runTest {
        router().execute(Command(CommandType.OPEN_SETTINGS, mapOf("target" to "wifi")))
        assertEquals(android.provider.Settings.ACTION_WIFI_SETTINGS, started()!!.action)
        router().execute(Command(CommandType.OPEN_SETTINGS))
        assertEquals(android.provider.Settings.ACTION_SETTINGS, started()!!.action)
    }

    @Test fun volumeCommandsReportRealLevel() = runTest {
        val r = router().execute(Command(CommandType.SET_VOLUME, mapOf("percent" to "100")))
        assertTrue(r.ok)
        assertTrue(r.subtitle.contains("100%"))
        val down = router().execute(Command(CommandType.VOLUME_DOWN))
        assertTrue(down.ok)
    }

    @Test fun weatherProducesBilingualTextAndToolData() = runTest {
        val r = router().execute(Command(CommandType.WEATHER, mapOf("day" to "today")))
        assertTrue(r.ok)
        assertTrue(r.speech, r.speech.contains("eighteen degrees") && r.speech.contains("seventy percent chance of rain"))
        assertTrue(r.subtitle, r.subtitle.contains("18°C") && r.subtitle.contains("강수확률 70%"))
        assertNotNull(r.data)
        val tomorrow = router().execute(Command(CommandType.WEATHER, mapOf("day" to "tomorrow")))
        assertTrue(tomorrow.subtitle.contains("내일") && tomorrow.subtitle.contains("24°C"))
    }

    @Test fun weatherOfflineIsExplained() = runTest {
        weatherResult = { throw WeatherException("x", noInternet = true) }
        val r = router().execute(Command(CommandType.WEATHER))
        assertEquals(ResultStatus.FAILED, r.status)
        assertTrue(r.subtitle.contains("인터넷"))
    }

    @Test fun webAnswerReturnsDataForTheAi() = runTest {
        val r = router().execute(Command(CommandType.WEB_ANSWER, mapOf("query" to "원신 업데이트")))
        assertTrue(r.ok)
        assertTrue(r.data!!.contains("원신 5.0 업데이트"))
        assertNull(started())
    }

    @Test fun webAnswerFallsBackToBrowser() = runTest {
        searchResult = { emptyList() }
        assertTrue(router().execute(Command(CommandType.WEB_ANSWER, mapOf("query" to "zzz"))).ok)
        assertEquals("zzz", started()!!.data!!.getQueryParameter("q"))
        searchResult = { throw IOException("down") }
        assertTrue(router().execute(Command(CommandType.WEB_ANSWER, mapOf("query" to "yyy"))).ok)
        assertEquals("yyy", started()!!.data!!.getQueryParameter("q"))
    }

    @Test fun callIsAlwaysConfirmedFirstThenDials() = runTest {
        val r = router()
        val ask = r.execute(Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to "010-1234-5678")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, ask.status)
        assertNull("nothing may be dialled before confirmation", started())
        val go = r.execute(ask.pending!!, confirmed = true)
        assertTrue(go.ok)
        val i = started()!!
        assertEquals(Intent.ACTION_DIAL, i.action) // CALL_PHONE not granted -> dialer only
        assertEquals("tel:01012345678", i.data.toString().replace("-", ""))
    }

    @Test fun callWithCallPermissionPlacesTheCallAfterConfirm() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val r = router()
        val ask = r.execute(Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to "01012345678")))
        r.execute(ask.pending!!, confirmed = true)
        assertEquals(Intent.ACTION_CALL, started()!!.action)
    }

    @Test fun contactLookupNeedsPermission() = runTest {
        val r = router().execute(Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to "엄마")))
        assertEquals(ResultStatus.NEEDS_PERMISSION, r.status)
        assertNull(started())
    }

    @Test fun messageIsPreparedNeverSentSilently() = runTest {
        val r = router()
        val ask = r.execute(Command(CommandType.MESSAGE_CONTACT_REQUEST, mapOf("name" to "01012345678", "body" to "곧 도착해")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, ask.status)
        assertNull(started())
        assertTrue(r.execute(ask.pending!!, confirmed = true).ok)
        val i = started()!!
        assertEquals(Intent.ACTION_SENDTO, i.action)
        assertEquals("곧 도착해", i.getStringExtra("sms_body"))
        assertEquals(ResultStatus.FAILED, r.execute(Command(CommandType.MESSAGE_CONTACT_REQUEST, mapOf("name" to "010"))).status)
    }

    @Test fun backgroundLaunchPostsNotificationInsteadOfBypassingAndroid() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
        shadowOf(app.packageManager).addResolveInfoForIntent(view, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.browser"; name = "B" }
        })
        val r = router(foreground = false).execute(Command(CommandType.OPEN_URL, mapOf("url" to "https://example.com")))
        assertTrue(r.ok)
        assertTrue(r.speech.contains("notification"))
        assertNull("no activity may be started from the background", started())
    }

    @Test fun flashlightWithoutHardwareFailsCleanly() = runTest {
        val r = router().execute(Command(CommandType.FLASHLIGHT_ON))
        assertTrue(r.status == ResultStatus.FAILED || r.status == ResultStatus.OK)
    }
}
