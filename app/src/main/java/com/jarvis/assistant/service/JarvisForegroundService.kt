package com.jarvis.assistant.service

import android.annotation.SuppressLint
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.container
import com.jarvis.assistant.core.AssistantPhase
import com.jarvis.assistant.core.HudState
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type: microphone) that keeps the wake-word loop alive while the app is in the
 * background. The ongoing notification is the user-visible indicator that the microphone is in use,
 * and it carries a Stop action. Nothing listens unless this service is running.
 */
class JarvisForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var started = false
    private var gesturesHeld = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_standby),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    // FOREGROUND_SERVICE_TYPE_MICROPHONE is API 30+; ServiceCompat drops it on Android 10 where no type is needed.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = container.controller
        if (intent?.action == ACTION_STOP) {
            controller.stopStandby()
            shutDown()
            return START_NOT_STICKY
        }
        if (!Perms.hasMic(this)) {
            controller.reportError("마이크 권한이 없어 백그라운드 어시스턴트를 시작할 수 없습니다.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(HudState(standby = true)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // e.g. Android 14+ refuses a microphone foreground service started from the background.
            JLog.e("Service", "Foreground start refused", e)
            controller.reportError("백그라운드 서비스를 시작하지 못했습니다. 앱을 연 상태에서 다시 시도해 주세요.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!controller.startStandby()) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!gesturesHeld) {
            gesturesHeld = true
            container.gestures.acquire()
        }
        if (!started) {
            started = true
            observer = scope.launch {
                var sawStandby = false
                controller.hud.map { it }.distinctUntilChanged { a, b ->
                    a.phase == b.phase && a.standby == b.standby && a.online == b.online
                }.collect { hud ->
                    if (hud.standby) sawStandby = true
                    if (sawStandby && !hud.standby) {
                        shutDown()
                    } else {
                        notify(hud)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(hud: HudState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(hud))
    }

    private fun buildNotification(hud: HudState): Notification {
        val text = when {
            hud.phase == AssistantPhase.LISTENING -> "Listening…"
            hud.phase == AssistantPhase.THINKING -> "Processing…"
            hud.phase == AssistantPhase.SPEAKING -> "Speaking…"
            hud.phase == AssistantPhase.EXECUTING -> "Executing…"
            hud.phase == AssistantPhase.ERROR -> "Error — tap to open JARVIS"
            !hud.online -> "Offline · listening for wake word"
            else -> "Listening for wake word · say “JARVIS”"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSubText("Microphone active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun releaseGestures() {
        if (gesturesHeld) {
            gesturesHeld = false
            container.gestures.release()
        }
    }

    private fun shutDown() {
        observer?.cancel()
        started = false
        releaseGestures()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseGestures()
        container.controller.stopStandby()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_standby"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.jarvis.assistant.action.START"
        private const val ACTION_STOP = "com.jarvis.assistant.action.STOP"

        /** Must be called while the app is visible (Android 14+ rule for microphone foreground services). */
        fun start(context: Context): Boolean {
            if (!Perms.hasMic(context)) return false
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, JarvisForegroundService::class.java).setAction(ACTION_START),
                )
                true
            } catch (e: Exception) {
                JLog.e("Service", "Could not start service", e)
                false
            }
        }
    }
}
