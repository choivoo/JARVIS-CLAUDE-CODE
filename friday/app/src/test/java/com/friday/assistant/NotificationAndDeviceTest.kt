package com.friday.assistant

import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.device.AndroidDeviceStatusProvider
import com.friday.assistant.device.DeviceExecutors
import com.friday.assistant.device.DeviceStatus
import com.friday.assistant.device.NetworkKind
import com.friday.assistant.device.Ringer
import com.friday.assistant.notification.NotificationExecutors
import com.friday.assistant.notification.NotificationHub
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationAndDeviceTest {
    private val items = listOf(
        notif("k1", "KakaoTalk", "민수", "오늘 저녁에 볼까?", 1_000),
        notif("k2", "Gmail", "Boss", "Please review the report", 3_000),
        notif("k3", "카카오톡", "엄마", "밥 먹었니", 2_000),
    )
    private fun router(src: FakeNotifications = FakeNotifications(items = items), access: FakeAccess = FakeAccess(notificationAccess = true)) =
        CommandRouter(NotificationExecutors(src).all(), access)

    @Test fun countAndListUseEnglishSpeechAndKoreanSubtitle() = runTest {
        val c = router().execute(Command(CommandType.GET_NOTIFICATION_COUNT))
        assertEquals("You have three recent notifications.", c.speech)
        assertEquals("최근 알림이 3개 있습니다.", c.subtitle)
        val l = router().execute(Command(CommandType.GET_NOTIFICATIONS))
        assertTrue(l.speech.startsWith("You have three recent notifications, from"))
        assertTrue(l.subtitle.contains("Gmail"))
        assertNotNull(l.card)
    }

    @Test fun emptyInbox() = runTest {
        val r = router(FakeNotifications(items = emptyList())).execute(Command(CommandType.GET_NOTIFICATIONS))
        assertEquals("You have no recent notifications.", r.speech)
        assertEquals("최근 알림이 없습니다.", r.subtitle)
    }

    @Test fun latestLatinTextIsSpokenButKoreanTextOnlyShown() = runTest {
        val latest = router().execute(Command(CommandType.READ_LATEST_NOTIFICATION))   // Gmail, newest
        assertTrue(latest.speech.contains("Please review the report"))
        val kakao = router().execute(Command(CommandType.READ_NOTIFICATIONS_FROM_APP, mapOf("target" to "카카오톡")))
        assertFalse("Hangul must not be read by the English voice", kakao.speech.any { it in '가'..'힣' })
        assertTrue(kakao.speech.contains("KakaoTalk"))
        assertTrue(kakao.subtitle.contains("엄마") && kakao.subtitle.contains("밥 먹었니"))
    }

    @Test fun contentIsNeverHandedToTheAi() = runTest {
        listOf(CommandType.GET_NOTIFICATIONS, CommandType.READ_LATEST_NOTIFICATION, CommandType.GET_NOTIFICATION_COUNT).forEach {
            assertNull("$it must not expose data for the AI", router().execute(Command(it)).data)
        }
    }

    @Test fun noMatchingAppIsHonest() = runTest {
        val r = router().execute(Command(CommandType.READ_NOTIFICATIONS_FROM_APP, mapOf("target" to "Spotify")))
        assertTrue(r.speech.contains("don't see"))
    }

    @Test fun openUsesTheNotificationIntent() = runTest {
        val src = FakeNotifications(items = items)
        assertTrue(router(src).execute(Command(CommandType.OPEN_NOTIFICATION)).ok)
        assertEquals(listOf("k2"), src.opened)
    }

    @Test fun dismissNeedsConfirmationAndOnlyThenActs() = runTest {
        val src = FakeNotifications(items = items)
        val r = router(src)
        val ask = r.execute(Command(CommandType.DISMISS_NOTIFICATION, mapOf("target" to "Gmail")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, ask.status)
        assertTrue(src.dismissed.isEmpty())
        assertTrue(r.execute(ask.pending!!, confirmed = true).ok)
        assertEquals(listOf("k2"), src.dismissed)
    }

    @Test fun withoutNotificationAccessNothingIsRead() = runTest {
        val src = FakeNotifications(enabled = false, items = items)
        val r = router(src, FakeAccess(notificationAccess = false)).execute(Command(CommandType.GET_NOTIFICATIONS))
        assertEquals(ResultStatus.NEEDS_PERMISSION, r.status)
        assertFalse(r.subtitle.contains("민수"))
    }

    @Test fun hubKeepsOnlyAMemoryBufferAndAccessIsOffByDefault() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val hub = NotificationHub(ctx)
        assertFalse("Notification Access must be OFF by default", hub.accessEnabled())
        assertTrue(hub.recent().isEmpty())
        assertFalse(hub.open("nope")); assertFalse(hub.dismiss("nope"))
        // access flag is read from the system setting the user controls
        val flat = NotificationHub.componentName(ctx).flattenToString()
        android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", "other/Svc:$flat")
        assertTrue(hub.accessEnabled())
        android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", "other/Svc")
        assertFalse(hub.accessEnabled())
    }

    @Test fun speakableAppNamesAndScriptDetection() {
        assertEquals("KakaoTalk", NotificationExecutors.speakable("카카오톡"))
        assertEquals("Gmail", NotificationExecutors.speakable("Gmail"))
        assertTrue(NotificationExecutors.isMostlyLatin("Hello 123"))
        assertFalse(NotificationExecutors.isMostlyLatin("안녕하세요"))
    }

    // ---- device status --------------------------------------------------------------------------------------

    private fun dev(s: DeviceStatus) = CommandRouter(DeviceExecutors(FakeDevice(s)).all())

    @Test fun batteryAndChargingSentences() = runTest {
        val base = FakeDevice.defaultStatus
        val b = dev(base).execute(Command(CommandType.GET_BATTERY))
        assertEquals("Your battery is currently at seventy-two percent.", b.speech)
        assertEquals("현재 배터리는 72%입니다.", b.subtitle)
        val ch = dev(base.copy(charging = true)).execute(Command(CommandType.GET_CHARGING_STATE))
        assertTrue(ch.speech.startsWith("Yes, it's charging"))
        assertTrue(dev(base).execute(Command(CommandType.GET_CHARGING_STATE)).speech.startsWith("No"))
        assertTrue(dev(base.copy(charging = null)).execute(Command(CommandType.GET_CHARGING_STATE)).status == ResultStatus.FAILED)
        assertTrue(dev(base.copy(batteryPercent = null)).execute(Command(CommandType.GET_BATTERY)).status == ResultStatus.FAILED)
    }

    @Test fun networkStateIsHonestAboutUnknowns() = runTest {
        val base = FakeDevice.defaultStatus
        assertTrue(dev(base).execute(Command(CommandType.GET_NETWORK_STATE)).speech.contains("Wi-Fi"))
        val off = dev(base.copy(network = NetworkKind.NONE, wifiEnabled = false, bluetoothEnabled = true)).execute(Command(CommandType.GET_NETWORK_STATE))
        assertTrue(off.speech.contains("offline") && off.speech.contains("Bluetooth is on"))
        assertFalse("unknown Bluetooth state must not be invented", dev(base).execute(Command(CommandType.GET_NETWORK_STATE)).speech.contains("Bluetooth"))
    }

    @Test fun volumeStorageAndSummary() = runTest {
        val base = FakeDevice.defaultStatus.copy(ringer = Ringer.VIBRATE)
        val v = dev(base).execute(Command(CommandType.GET_VOLUME))
        assertTrue(v.speech.contains("forty percent") && v.speech.contains("vibrate"))
        assertTrue(v.subtitle.contains("진동"))
        val st = dev(base).execute(Command(CommandType.GET_STORAGE))
        assertTrue(st.subtitle.contains("20.0GB"))
        val sum = dev(base).execute(Command(CommandType.GET_DEVICE_STATUS))
        assertTrue(sum.speech.contains("online") && sum.speech.contains("battery"))
    }

    @Test fun realProviderReadsWithoutCrashing() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = AndroidDeviceStatusProvider(ctx).read()
        assertTrue(s.volumePercent in 0..100)
        assertTrue(s.freeBytes >= 0) // Robolectric's StatFs reports zeros, so sizes are only checked on a device
    }
}
