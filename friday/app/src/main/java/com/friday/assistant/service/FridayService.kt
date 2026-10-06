package com.friday.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.friday.assistant.FridayApp
import com.friday.assistant.MainActivity
import com.friday.assistant.R
import com.friday.assistant.core.CoreState
import com.friday.assistant.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The visible "Background Assistant": a microphone foreground service with a persistent notification and a STOP button.
 * It never records covertly and stops whenever the user turns it off.
 */
class FridayService : Service() {
    private var resumeJob: Job? = null

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
            container.controller.onWake(remainder)
            resumeJob?.cancel()
            resumeJob = container.scope.launch {
                delay(600) // let the controller leave IDLE first
                container.controller.core.first { it == CoreState.IDLE }
                container.wake.resume()
            }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        val container = (application as FridayApp).container
        resumeJob?.cancel()
        container.wake.stop()
        container.serviceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val container = (application as FridayApp).container
        container.wake.stop()
        container.serviceRunning.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.friday.assistant.STOP"
        private const val CHANNEL = "friday_listening"
        private const val NOTIFICATION_ID = 1001

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
