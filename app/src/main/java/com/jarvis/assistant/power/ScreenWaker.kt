package com.jarvis.assistant.power

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.command.AppVisibility
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms

/**
 * Lets JARVIS answer while the phone is asleep: turns the screen on and shows the HUD above the
 * lock screen (the lock itself is never bypassed; [MainActivity] hides private content while locked).
 * If Android refuses a background activity start, a full-screen notification does the same job.
 */
class ScreenWaker(
    private val context: Context,
    private val visibility: AppVisibility,
) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    fun screenIsOffOrLocked(): Boolean = !power.isInteractive || keyguard.isKeyguardLocked

    /** Returns true when the screen had to be woken. */
    @Suppress("DEPRECATION")
    fun wakeAndShow(): Boolean {
        if (!screenIsOffOrLocked()) return false
        try {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "JARVIS:wake",
            ).acquire(20_000)
        } catch (e: Exception) {
            JLog.w("ScreenWaker", "Could not turn the screen on", e)
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_WAKE_UI, true)
        val canStart = visibility.isForeground || Perms.canOverlay(context)
        if (canStart) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                JLog.w("ScreenWaker", "Direct start refused", e)
            }
        }
        postFullScreenNotification(intent)
        return true
    }

    private fun postFullScreenNotification(intent: Intent) {
        if (!Perms.hasNotifications(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "JARVIS wake", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
        val pending = PendingIntent.getActivity(
            context, 77, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            7700,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("JARVIS")
                .setContentText("Listening…")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL = "jarvis_wake"
    }
}
