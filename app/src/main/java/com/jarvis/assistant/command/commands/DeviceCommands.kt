package com.jarvis.assistant.command.commands

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun AiAction.num(name: String): Double? = param(name)?.toDoubleOrNull()

internal fun AiAction.kind(): String = type.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_")

private fun gb(bytes: Long) = bytes / 1_073_741_824.0

/** BRIGHTNESS_SET {level 0-100} / BRIGHTNESS_UP / BRIGHTNESS_DOWN. Needs the "modify system settings" grant. */
class BrightnessCommand(private val context: Context, private val launcher: ActivityLauncher) : Command {
    override val types = listOf("BRIGHTNESS_SET", "SET_BRIGHTNESS", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.canWriteSettings(context)) {
            launcher.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                "Allow brightness control",
            )
            return ActionResult.fail(
                "I need permission to change the brightness. Please allow it on the screen that just opened.",
                "밝기를 바꾸려면 권한이 필요합니다. 열린 화면에서 허용해 주세요.",
            )
        }
        val cr = context.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (action.kind()) {
            "BRIGHTNESS_UP" -> current + 50
            "BRIGHTNESS_DOWN" -> current - 50
            else -> {
                val pct = action.num("level") ?: return ActionResult.fail(
                    "What brightness would you like?", "밝기를 몇 퍼센트로 할까요?",
                )
                (pct.coerceIn(0.0, 100.0) * 2.55).roundToInt()
            }
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val pct = (target / 2.55).roundToInt()
        return ActionResult.ok("Brightness at $pct percent.", "밝기를 ${pct}%로 설정했습니다.")
    }
}

class MuteCommand(context: Context) : Command {
    override val types = listOf("MUTE", "UNMUTE", "MUTE_VOLUME")
    override val executesBeforeSpeech = true
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val unmute = action.kind() == "UNMUTE"
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (unmute) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
            AudioManager.FLAG_SHOW_UI,
        )
        return if (unmute) ActionResult.ok("Sound restored.", "소리를 다시 켰습니다.")
        else ActionResult.ok("Muted.", "음소거했습니다.")
    }
}

/** RINGER_NORMAL / RINGER_VIBRATE / RINGER_SILENT. Silent needs Do-Not-Disturb access on most phones. */
class RingerModeCommand(private val context: Context, private val launcher: ActivityLauncher) : Command {
    override val types = listOf("RINGER_NORMAL", "RINGER_VIBRATE", "RINGER_SILENT", "SET_RINGER_MODE", "SILENT_MODE", "VIBRATE_MODE")
    override val executesBeforeSpeech = true
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val mode = (action.param("mode") ?: action.kind()).lowercase()
        val target = when {
            mode.contains("silent") -> AudioManager.RINGER_MODE_SILENT
            mode.contains("vibrate") -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (target == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
            launcher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Do Not Disturb access")
            return ActionResult.fail(
                "Silent mode needs Do Not Disturb access. Please allow JARVIS on the screen that opened.",
                "무음 모드는 방해 금지 접근 권한이 필요합니다. 열린 화면에서 허용해 주세요.",
            )
        }
        return try {
            audio.ringerMode = target
            when (target) {
                AudioManager.RINGER_MODE_SILENT -> ActionResult.ok("Silent mode on.", "무음 모드로 전환했습니다.")
                AudioManager.RINGER_MODE_VIBRATE -> ActionResult.ok("Vibrate mode on.", "진동 모드로 전환했습니다.")
                else -> ActionResult.ok("Ringer on.", "소리 모드로 전환했습니다.")
            }
        } catch (e: SecurityException) {
            ActionResult.fail("The system refused that change.", "시스템이 변경을 허용하지 않았습니다.")
        }
    }
}

class StreamVolumeCommand(context: Context) : Command {
    override val types = listOf("SET_RING_VOLUME", "SET_ALARM_VOLUME", "SET_NOTIFICATION_VOLUME")
    override val executesBeforeSpeech = true
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val (stream, nameEn, nameKo) = when (action.kind()) {
            "SET_ALARM_VOLUME" -> Triple(AudioManager.STREAM_ALARM, "Alarm", "알람")
            "SET_NOTIFICATION_VOLUME" -> Triple(AudioManager.STREAM_NOTIFICATION, "Notification", "알림")
            else -> Triple(AudioManager.STREAM_RING, "Ringtone", "벨소리")
        }
        val pct = action.num("level") ?: return ActionResult.fail("Which level?", "몇 퍼센트로 할까요?")
        val max = audio.getStreamMaxVolume(stream)
        return try {
            audio.setStreamVolume(stream, (pct.coerceIn(0.0, 100.0) * max / 100).roundToInt(), AudioManager.FLAG_SHOW_UI)
            ActionResult.ok("$nameEn volume set to ${pct.roundToInt()} percent.", "$nameKo 볼륨을 ${pct.roundToInt()}%로 설정했습니다.")
        } catch (e: SecurityException) {
            ActionResult.fail("The system refused that change.", "시스템이 변경을 허용하지 않았습니다.")
        }
    }
}

class StorageInfoCommand : Command {
    override val types = listOf("STORAGE_INFO", "STORAGE", "FREE_SPACE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = gb(stat.totalBytes)
        val free = gb(stat.availableBytes)
        val usedPct = ((1 - free / total) * 100).roundToInt()
        return ActionResult.ok(
            "You have %.1f gigabytes free out of %.0f. Storage is %d percent full.".format(free, total, usedPct),
            "저장 공간은 총 %.0fGB 중 %.1fGB 남았습니다. (사용률 %d%%)".format(total, free, usedPct),
        )
    }
}

class MemoryInfoCommand(context: Context) : Command {
    override val types = listOf("MEMORY_INFO", "RAM_INFO", "MEMORY")
    override val executesBeforeSpeech = true
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override suspend fun execute(action: AiAction): ActionResult {
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val total = gb(info.totalMem)
        val free = gb(info.availMem)
        return ActionResult.ok(
            "%.1f of %.1f gigabytes of memory are available.".format(free, total),
            "메모리 %.1fGB 중 %.1fGB를 사용할 수 있습니다.".format(total, free),
        )
    }
}

class DeviceInfoCommand : Command {
    override val types = listOf("DEVICE_INFO", "PHONE_INFO", "ABOUT_PHONE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return ActionResult.ok(
            "This is a $name running Android ${Build.VERSION.RELEASE}.",
            "$name, 안드로이드 ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})입니다.",
        )
    }
}

class NetworkInfoCommand(context: Context) : Command {
    override val types = listOf("NETWORK_INFO", "NETWORK_STATUS", "CONNECTION_INFO")
    override val executesBeforeSpeech = true
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun execute(action: AiAction): ActionResult {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ActionResult.ok("You are offline.", "인터넷에 연결되어 있지 않습니다.")
        }
        val (en, ko) = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi" to "와이파이"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data" to "모바일 데이터"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet" to "이더넷"
            else -> "an unknown network" to "알 수 없는 네트워크"
        }
        val mbps = caps.linkDownstreamBandwidthKbps / 1000
        return ActionResult.ok(
            "You are online through $en, with an estimated $mbps megabits per second.",
            "$ko 로 연결되어 있으며 예상 속도는 약 ${mbps}Mbps입니다.",
        )
    }
}

class BatteryDetailCommand(private val context: Context) : Command {
    override val types = listOf("BATTERY_DETAIL", "BATTERY_HEALTH", "CHARGE_TIME", "BATTERY_TEMPERATURE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ActionResult.fail("I can't read the battery right now.", "배터리 정보를 읽을 수 없습니다.")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val (plugEn, plugKo) = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charging from a wall adapter" to "어댑터로 충전 중"
            BatteryManager.BATTERY_PLUGGED_USB -> "charging over USB" to "USB로 충전 중"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging wirelessly" to "무선 충전 중"
            else -> "running on battery" to "배터리 사용 중"
        }
        val (healthEn, healthKo) = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good" to "양호"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating" to "과열"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold" to "저온"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead" to "수명 다함"
            else -> "unknown" to "알 수 없음"
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val remaining = if (plugged != 0) bm.computeChargeTimeRemaining() else -1L
        val extraEn = if (remaining > 0) " About ${remaining / 60_000} minutes until full." else ""
        val extraKo = if (remaining > 0) " 완충까지 약 ${remaining / 60_000}분 남았습니다." else ""
        return ActionResult.ok(
            "Battery is at $level percent, $plugEn, $temp degrees, health $healthEn.$extraEn",
            "배터리 ${level}%, $plugKo, ${temp}°C, 상태 $healthKo.$extraKo",
        )
    }
}

class UptimeCommand : Command {
    override val types = listOf("UPTIME", "DEVICE_UPTIME")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val minutes = SystemClock.elapsedRealtime() / 60_000
        val d = minutes / 1440
        val h = minutes % 1440 / 60
        val m = minutes % 60
        return ActionResult.ok(
            "The phone has been running for $d days, $h hours and $m minutes.",
            "마지막 재부팅 이후 ${d}일 ${h}시간 ${m}분 동안 켜져 있었습니다.",
        )
    }
}

/** Flashlight Morse "SOS". */
class SosFlashCommand(context: Context) : Command {
    override val types = listOf("FLASHLIGHT_SOS", "SOS_FLASH", "SOS")
    override val executesBeforeSpeech = true
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override suspend fun execute(action: AiAction): ActionResult {
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult.fail("This device has no flashlight.", "이 기기에는 손전등이 없습니다.")
        val pattern = listOf(200, 200, 200, 600, 600, 600, 600, 600, 600, 600, 200, 200, 200)
        try {
            for ((index, ms) in pattern.withIndex()) {
                camera.setTorchMode(id, true)
                delay(ms.toLong())
                camera.setTorchMode(id, false)
                delay(if (index == 2 || index == 5 || index == 8) 500 else 200)
            }
        } finally {
            try {
                camera.setTorchMode(id, false)
            } catch (_: Exception) {
            }
        }
        return ActionResult.ok("SOS signal sent.", "SOS 신호를 보냈습니다.")
    }
}

/** "Where's my phone": rings loudly on the alarm stream until stopped (or 60 seconds). */
class FindPhoneCommand(private val context: Context) : Command {
    override val types = listOf("FIND_PHONE", "RING_PHONE", "STOP_FIND_PHONE", "STOP_RINGING")
    override val executesBeforeSpeech = true
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var autoStop: Job? = null

    override suspend fun execute(action: AiAction): ActionResult {
        if (action.kind().startsWith("STOP")) {
            stop()
            return ActionResult.ok("Alarm stopped.", "소리를 멈췄습니다.")
        }
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return ActionResult.fail("I couldn't find a ringtone.", "재생할 벨소리를 찾지 못했습니다.")
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
        // Self-stop so a forgotten alarm can't ring forever (outside the command timeout).
        autoStop?.cancel()
        autoStop = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            delay(60_000)
            stop()
        }
        return ActionResult.ok(
            "Ringing at full volume. Say stop to silence it.",
            "최대 볼륨으로 울리는 중입니다. 멈추려면 '멈춰'라고 말씀하세요.",
        )
    }

    private fun stop() {
        autoStop?.cancel()
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        if (savedVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
    }
}

/** Extra transport keys beyond play / pause / next / previous. */
class MusicKeysCommand(context: Context) : Command {
    override val types = listOf("MUSIC_STOP", "MUSIC_FORWARD", "MUSIC_REWIND", "FAST_FORWARD", "REWIND")
    override val executesBeforeSpeech = true
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val (key, en, ko) = when (action.kind()) {
            "MUSIC_STOP" -> Triple(KeyEvent.KEYCODE_MEDIA_STOP, "Stopped.", "정지했습니다.")
            "MUSIC_REWIND", "REWIND" -> Triple(KeyEvent.KEYCODE_MEDIA_REWIND, "Rewinding.", "되감습니다.")
            else -> Triple(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "Fast forwarding.", "빨리 감습니다.")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ActionResult.ok(en, ko)
    }
}
