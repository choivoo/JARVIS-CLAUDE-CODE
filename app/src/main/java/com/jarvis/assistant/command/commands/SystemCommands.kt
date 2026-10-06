package com.jarvis.assistant.command.commands

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.util.Perms
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class GetTimeCommand : Command {
    override val types = listOf("GET_TIME", "TIME", "CURRENT_TIME")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val now = LocalTime.now()
        val en = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        val hour12 = if (now.hour % 12 == 0) 12 else now.hour % 12
        val period = if (now.hour < 12) "오전" else "오후"
        val ko = if (now.minute == 0) "지금은 $period ${hour12}시입니다." else "지금은 $period ${hour12}시 ${now.minute}분입니다."
        return ActionResult.ok("It's $en.", ko)
    }
}

class GetBatteryCommand(private val context: Context) : Command {
    override val types = listOf("GET_BATTERY", "BATTERY", "BATTERY_LEVEL")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ActionResult.fail("I can't read the battery right now.", "배터리 정보를 읽을 수 없습니다.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return ActionResult.fail("I can't read the battery right now.", "배터리 정보를 읽을 수 없습니다.")
        }
        val percent = (level * 100f / scale).roundToInt()
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) {
            ActionResult.ok("Battery is at $percent percent and charging.", "배터리는 ${percent}%이며 충전 중입니다.")
        } else {
            ActionResult.ok("Battery is at $percent percent.", "배터리는 ${percent}% 남았습니다.")
        }
    }
}

class VolumeCommand(context: Context) : Command {
    override val types = listOf("SET_VOLUME", "VOLUME_UP", "VOLUME_DOWN", "VOLUME")
    override val executesBeforeSpeech = true

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val normalized = action.type.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_")
        val target: Int = when (normalized) {
            "VOLUME_UP" -> (audio.getStreamVolume(stream) + maxOf(1, max / 7)).coerceAtMost(max)
            "VOLUME_DOWN" -> (audio.getStreamVolume(stream) - maxOf(1, max / 7)).coerceAtLeast(0)
            else -> {
                val percent = action.param("level")?.toDoubleOrNull()?.roundToInt()
                    ?: return ActionResult.fail("Which volume level would you like?", "원하는 볼륨을 말씀해 주세요.")
                (percent.coerceIn(0, 100) * max / 100f).roundToInt()
            }
        }
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        val shown = (target * 100f / max).roundToInt()
        return ActionResult.ok("Volume set to $shown percent.", "볼륨을 ${shown}%로 설정했습니다.")
    }
}

class FlashlightCommand(context: Context) : Command {
    override val types = listOf("FLASHLIGHT_ON", "FLASHLIGHT_OFF", "FLASHLIGHT", "TORCH_ON", "TORCH_OFF")
    override val executesBeforeSpeech = true

    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override suspend fun execute(action: AiAction): ActionResult {
        val type = action.type.uppercase()
        val enable = !type.endsWith("OFF") && action.param("state")?.lowercase() != "off"
        val id = camera.cameraIdList.firstOrNull { cameraId ->
            val c = camera.getCameraCharacteristics(cameraId)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ActionResult.fail("This device has no flashlight.", "이 기기에는 손전등이 없습니다.")
        camera.setTorchMode(id, enable)
        return if (enable) {
            ActionResult.ok("Flashlight on.", "손전등을 켰습니다.")
        } else {
            ActionResult.ok("Flashlight off.", "손전등을 껐습니다.")
        }
    }
}

class NotificationCommand(private val context: Context) : Command {
    override val types = listOf("NOTIFICATION", "NOTIFY", "SHOW_NOTIFICATION")

    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.hasNotifications(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return ActionResult.fail(
                "Notifications are disabled for me. Please allow them in settings.",
                "알림 권한이 꺼져 있습니다. 설정에서 허용해 주세요.",
            )
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("jarvis_notes", "JARVIS notes", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = NotificationCompat.Builder(context, "jarvis_notes")
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle((action.param("title") ?: "JARVIS").take(80))
            .setContentText((action.param("text") ?: action.param("message") ?: "").take(300))
            .setAutoCancel(true)
            .build()
        manager.notify(3000 + (System.currentTimeMillis() % 50).toInt(), notification)
        return ActionResult.ok("Notification posted.", "알림을 보냈습니다.")
    }
}
