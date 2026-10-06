package com.friday.assistant.command

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.friday.assistant.R
import com.friday.assistant.util.Permissions

enum class LaunchOutcome { STARTED, NOTIFIED }

/**
 * Android 10+ blocks activities started from the background. When FRIDAY is not on screen we do not try to
 * bypass that: we post a tap-to-open notification instead and report it honestly.
 */
class ActivityLauncher(
    private val context: Context,
    private val isForeground: () -> Boolean,
) {
    /** Throws [android.content.ActivityNotFoundException] when nothing can handle [intent]. */
    fun launch(intent: Intent, label: String): LaunchOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isForeground()) {
            context.startActivity(intent)
            return LaunchOutcome.STARTED
        }
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty() && intent.`package` == null && intent.component == null) {
            throw android.content.ActivityNotFoundException()
        }
        notify(intent, label)
        return LaunchOutcome.NOTIFIED
    }

    private fun notify(intent: Intent, label: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "FRIDAY actions", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, label.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_friday)
            .setContentTitle("FRIDAY")
            .setContentText("Tap to open $label")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (Permissions.notifications(context)) {
            nm.notify(ACTION_NOTIFICATION_ID, n)
        } else {
            throw SecurityException("notification permission")
        }
    }

    private companion object {
        const val CHANNEL = "friday_actions"
        const val ACTION_NOTIFICATION_ID = 2002
    }
}
