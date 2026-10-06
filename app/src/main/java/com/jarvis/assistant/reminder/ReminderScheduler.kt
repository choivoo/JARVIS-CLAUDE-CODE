package com.jarvis.assistant.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.data.database.ReminderEntity
import com.jarvis.assistant.util.JLog

/**
 * Schedules reminders with [AlarmManager.setAlarmClock]: exact, works in Doze, and needs no special
 * permission. Pending reminders are re-armed after a reboot or app update by [ReminderReceiver].
 */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(reminder: ReminderEntity) {
        try {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.triggerAt, show), pendingFor(reminder.id))
        } catch (e: Exception) {
            JLog.w("Reminder", "Could not schedule reminder", e)
        }
    }

    fun cancel(id: Long) {
        alarms.cancel(pendingFor(id))
    }

    private fun pendingFor(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id % Int.MAX_VALUE).toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
