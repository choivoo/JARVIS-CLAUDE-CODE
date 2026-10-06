package com.jarvis.assistant.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.container
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fires reminders and re-arms the pending ones after reboot / app update. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> fire(app, intent.getLongExtra(EXTRA_ID, -1))
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> rearm(app)
                }
            } catch (e: Exception) {
                JLog.e("Reminder", "Receiver failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, id: Long) {
        val container = context.container
        val reminder = container.reminders.get(id) ?: return
        if (reminder.fired) return
        container.reminders.markFired(id)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "JARVIS reminders", NotificationManager.IMPORTANCE_HIGH),
        )
        if (Perms.hasNotifications(context)) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                (4000 + id % 900).toInt(),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_jarvis)
                    .setContentTitle("JARVIS reminder")
                    .setContentText(reminder.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
        if (container.settings.current().speakReminders) {
            container.controller.announce(
                "Pardon the interruption. A reminder: ${reminder.text}",
                "알림입니다: ${reminder.text}",
            )
        }
    }

    private suspend fun rearm(context: Context) {
        val container = context.container
        val now = System.currentTimeMillis()
        container.reminders.pending().forEach { r ->
            if (r.triggerAt <= now) fire(context, r.id) else container.reminderScheduler.schedule(r)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.jarvis.assistant.action.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        private const val CHANNEL = "jarvis_reminders"
    }
}
