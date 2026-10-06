package com.friday.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.friday.assistant.FridayApp
import com.friday.assistant.MainActivity
import com.friday.assistant.R
import com.friday.assistant.core.CoreState
import com.friday.assistant.proactive.AlertKind
import com.friday.assistant.util.Permissions
import java.time.LocalDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The visible "Background Assistant": a microphone foreground service with a persistent notification and a STOP button.
 * It never records covertly and stops whenever the user turns it off.
 */
class FridayService : Service() {
    private var coreJob: Job? = null
    private var proactiveJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as FridayApp).container
        if (intent?.action == ACTION_STOP) {
            container.settingsRepo.update { it.copy(backgroundAssistant = false) }
            shutdown()
            return START_NOT_STICKY
        }
        if (!Permissions.mic(this)) {
            container.serviceError.value = "Microphone permission is required for the Background Assistant."
            shutdown()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            container.serviceError.value = "Android refused to start the foreground service: ${e.javaClass.simpleName}"
            container.serviceRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        container.serviceError.value = null
        container.serviceRunning.value = true
        container.wake.start { remainder ->
            // A heard "FRIDAY" while FRIDAY itself is saying the word is just its own voice: keep listening.
            if (container.controller.shouldIgnoreWake()) container.wake.resume() else container.controller.onWake(remainder)
        }
        // The wake engine and the assistant share one microphone: the engine runs while idle, and while speaking so the
        // user can interrupt with "FRIDAY" (barge-in); it is released whenever the assistant itself is listening.
        coreJob?.cancel()
        coreJob = container.scope.launch {
            container.controller.core.collect { st ->
                val barge = st == CoreState.SPEAKING && container.settingsRepo.current.bargeIn
                if (st == CoreState.IDLE || barge) container.wake.resume() else container.wake.pause()
            }
        }
        startProactive(container)
        return START_NOT_STICKY
    }

    /** Event-driven alerts: the system's battery-low broadcast plus one coarse timer, both only while this service runs. */
    private fun startProactive(container: com.friday.assistant.AppContainer) {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { container.scope.launch { deliver(container, setOf(AlertKind.LOW_BATTERY)) } }
        }
        batteryReceiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_BATTERY_LOW), ContextCompat.RECEIVER_NOT_EXPORTED)
        proactiveJob?.cancel()
        proactiveJob = container.scope.launch {
            while (true) {
                delay(PROACTIVE_PERIOD_MS)
                val s = container.settingsRepo.current
                if (s.proactiveUpcomingEvent || s.proactiveWeather) deliver(container, setOf(AlertKind.UPCOMING_EVENT, AlertKind.WEATHER_WARNING))
            }
        }
    }

    private suspend fun deliver(container: com.friday.assistant.AppContainer, kinds: Set<AlertKind>) {
        val alerts = runCatching { container.proactive.evaluate(LocalDateTime.now(), kinds) }.getOrDefault(emptyList())
        if (alerts.isEmpty() || !Permissions.notifications(this)) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "FRIDAY alerts", NotificationManager.IMPORTANCE_DEFAULT))
        alerts.forEach { a ->
            nm.notify(
                a.id.hashCode(),
                NotificationCompat.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_stat_friday)
                    .setContentTitle("FRIDAY").setContentText(a.subtitle).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build(),
            )
        }
    }

    private fun teardown() {
        val container = (application as FridayApp).container
        coreJob?.cancel(); proactiveJob?.cancel()
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        container.wake.stop()
        container.serviceRunning.value = false
    }

    private fun shutdown() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.friday.assistant.STOP"
        private const val CHANNEL = "friday_listening"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_CHANNEL = "friday_alerts"
        private const val PROACTIVE_PERIOD_MS = 15 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FridayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FridayService::class.java))
        }

        fun buildNotification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "FRIDAY background assistant", NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, FridayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_friday)
                .setContentTitle("FRIDAY")
                .setContentText("Listening for wake word")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(0, "STOP", stop)
                .build()
        }
    }
}
