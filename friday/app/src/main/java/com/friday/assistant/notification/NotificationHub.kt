package com.friday.assistant.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationInfo(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

/** What the commands need from notifications. Implemented by [NotificationHub]; faked in tests. */
interface NotificationSource {
    fun accessEnabled(): Boolean
    /** Newest first. */
    fun recent(limit: Int = 20): List<NotificationInfo>
    fun open(key: String): Boolean
    fun dismiss(key: String): Boolean
}

/**
 * In-memory view of the notifications Android's listener service reports. Off by default (the user must grant
 * Notification Access in system settings). Nothing is written to disk or sent to any server; the buffer is lost
 * when the process ends.
 */
class NotificationHub(private val context: Context) : NotificationSource {
    private val items = LinkedHashMap<String, NotificationInfo>()
    private val raw = HashMap<String, StatusBarNotification>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    @Volatile private var listener: NotificationListenerService? = null

    override fun accessEnabled(): Boolean = isAccessGranted(context)

    @Synchronized fun attach(service: NotificationListenerService) {
        listener = service
        runCatching { service.activeNotifications }.getOrNull()?.forEach { onPosted(it) }
    }

    @Synchronized fun detach() { listener = null }

    @Synchronized fun onPosted(sbn: StatusBarNotification) {
        val info = parse(sbn) ?: return
        raw[info.key] = sbn
        items.remove(info.key)
        items[info.key] = info
        while (items.size > MAX) { val first = items.keys.first(); items.remove(first); raw.remove(first) }
        _count.value = items.size
    }

    @Synchronized fun onRemoved(key: String) { items.remove(key); raw.remove(key); _count.value = items.size }

    @Synchronized override fun recent(limit: Int): List<NotificationInfo> =
        items.values.sortedByDescending { it.postedAt }.take(limit)

    @Synchronized override fun open(key: String): Boolean {
        val pi = raw[key]?.notification?.contentIntent ?: return false
        return try { pi.send(); true } catch (e: Exception) { false }
    }

    @Synchronized override fun dismiss(key: String): Boolean {
        val l = listener ?: return false
        return try { l.cancelNotification(key); true } catch (e: Exception) { false }
    }

    private fun parse(sbn: StatusBarNotification): NotificationInfo? {
        if (sbn.packageName == context.packageName) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return NotificationInfo(sbn.key, sbn.packageName, appLabel(sbn.packageName), title.trim(), text.trim(), sbn.postTime)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    companion object {
        const val MAX = 30
        fun componentName(context: Context) = ComponentName(context, FridayNotificationService::class.java)

        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = componentName(context).flattenToString()
            return enabled.split(':').any { it == me }
        }
    }
}

/** Bridges Android's NotificationListenerService into the in-memory [NotificationHub]. */
class FridayNotificationService : NotificationListenerService() {
    private val hub get() = (application as com.friday.assistant.FridayApp).container.notifications

    override fun onListenerConnected() { hub.attach(this) }
    override fun onListenerDisconnected() { hub.detach() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { hub.onPosted(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { hub.onRemoved(sbn.key) }
}
