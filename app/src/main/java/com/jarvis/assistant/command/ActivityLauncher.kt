package com.jarvis.assistant.command

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms

/**
 * Starts other apps. Android 10+ blocks activity starts from the background, so when JARVIS is not
 * on screen and has no "display over other apps" grant, the intent is parked behind a notification the
 * user can tap instead of failing silently.
 */
class ActivityLauncher(
    private val context: Context,
    private val visibility: AppVisibility,
) {
    enum class Outcome { STARTED, NOTIFIED, FAILED }

    private var notificationId = 2000

    fun launch(intent: Intent, title: String): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return Outcome.FAILED
        val canStartDirectly = visibility.isForeground || Perms.canOverlay(context)
        if (canStartDirectly) {
            return try {
                context.startActivity(intent)
                Outcome.STARTED
            } catch (e: ActivityNotFoundException) {
                Outcome.FAILED
            } catch (e: SecurityException) {
                JLog.w("Launcher", "Direct launch refused, falling back to notification", e)
                notifyTap(intent, title)
            }
        }
        return notifyTap(intent, title)
    }

    private fun notifyTap(intent: Intent, title: String): Outcome {
        if (!Perms.hasNotifications(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return Outcome.FAILED
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_actions), NotificationManager.IMPORTANCE_HIGH),
        )
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS")
            .setContentText("Tap to continue: $title")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(notificationId++, notification)
        if (notificationId > 2100) notificationId = 2000
        return Outcome.NOTIFIED
    }

    companion object {
        const val CHANNEL_ID = "jarvis_actions"
        val NOTIFIED_RESULT = ActionResult.ok(
            "I've left it in your notifications, sir. Tap it to continue.",
            "알림에 준비해 두었습니다. 눌러서 계속해 주세요.",
        )
    }
}
