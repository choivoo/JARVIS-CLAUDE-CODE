@file:SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only evaluated on API 33+ (minSdk guard in items())

package com.friday.assistant.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.friday.assistant.notification.NotificationHub

enum class PermStatus { GRANTED, DENIED, NOT_REQUESTED, SETTINGS_REQUIRED }

enum class PermKind {
    /** A normal runtime permission: can be requested with the system dialog. */
    RUNTIME,
    /** Special access that only the system settings screens can grant. */
    SPECIAL,
    /** Not a permission itself: whether a feature has everything it needs. */
    DERIVED,
}

data class PermItem(
    val id: String,
    val title: String,
    val why: String,
    val status: PermStatus,
    val kind: PermKind,
    /** Manifest permission for RUNTIME items. */
    val permission: String? = null,
    val required: Boolean = false,
)

/** What the center needs to know; real Android state in production, plain values in tests. */
class PermissionSnapshot(
    val sdk: Int,
    val granted: (String) -> Boolean,
    val requestedBefore: (String) -> Boolean,
    val notificationAccess: Boolean,
    val overlay: Boolean,
)

/**
 * Computes the status of everything FRIDAY can use. NOT_REQUESTED means we never asked; DENIED means the user said no
 * (the system dialog may no longer appear, so the settings button is offered too); SETTINGS_REQUIRED means only a
 * system settings screen can grant it.
 */
object PermissionCenter {
    fun items(s: PermissionSnapshot): List<PermItem> {
        fun runtime(id: String, title: String, why: String, perm: String, required: Boolean = false, minSdk: Int = 1): PermItem {
            val status = when {
                s.sdk < minSdk || s.granted(perm) -> PermStatus.GRANTED
                s.requestedBefore(perm) -> PermStatus.DENIED
                else -> PermStatus.NOT_REQUESTED
            }
            return PermItem(id, title, why, status, PermKind.RUNTIME, perm, required)
        }
        val mic = runtime("mic", "Microphone", "Hear your voice and the wake word", Manifest.permission.RECORD_AUDIO, required = true)
        val notif = runtime("notif", "Notifications", "Show the Background Assistant notification (Android 13+)", Manifest.permission.POST_NOTIFICATIONS, minSdk = 33)
        val service = PermItem(
            "fgs", "Foreground service (microphone)", "The Background Assistant needs the microphone permission and a visible notification",
            if (mic.status == PermStatus.GRANTED && notif.status == PermStatus.GRANTED) PermStatus.GRANTED
            else if (mic.status == PermStatus.NOT_REQUESTED) PermStatus.NOT_REQUESTED else PermStatus.DENIED,
            PermKind.DERIVED,
        )
        return listOf(
            mic, notif, service,
            PermItem(
                "listener", "Notification Access", "Read recent notifications and see what media is playing. Off by default; you enable it in system settings.",
                if (s.notificationAccess) PermStatus.GRANTED else PermStatus.SETTINGS_REQUIRED, PermKind.SPECIAL,
            ),
            runtime("location", "Location", "Weather for where you are (last known position only)", Manifest.permission.ACCESS_COARSE_LOCATION),
            runtime("calendar", "Calendar (read)", "Tell you about your events", Manifest.permission.READ_CALENDAR),
            runtime("calendar_w", "Calendar (write)", "Add events after you confirm", Manifest.permission.WRITE_CALENDAR),
            runtime("contacts", "Contacts", "Find people to call or message", Manifest.permission.READ_CONTACTS),
            runtime("phone", "Phone calls", "Place a call directly after you confirm", Manifest.permission.CALL_PHONE),
            PermItem(
                "overlay", "Display over other apps", "Show FRIDAY's Korean subtitles on top of other apps while it is in the background",
                if (s.overlay) PermStatus.GRANTED else PermStatus.SETTINGS_REQUIRED, PermKind.SPECIAL,
            ),
        )
    }

    /** The settings screen that can change [item] (special access) or show the app's permissions (denied runtime items). */
    fun settingsIntent(context: Context, item: PermItem): Intent = when (item.id) {
        "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        "notif", "fgs" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun snapshot(context: Context): PermissionSnapshot {
        val prefs = context.getSharedPreferences("friday_perm", Context.MODE_PRIVATE)
        return PermissionSnapshot(
            sdk = Build.VERSION.SDK_INT,
            granted = { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            requestedBefore = { prefs.getBoolean(it, false) },
            notificationAccess = NotificationHub.isAccessGranted(context),
            overlay = Settings.canDrawOverlays(context),
        )
    }

    fun markRequested(context: Context, permission: String) {
        context.getSharedPreferences("friday_perm", Context.MODE_PRIVATE).edit().putBoolean(permission, true).apply()
    }
}
