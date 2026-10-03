package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Perms {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasMic(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun hasLocation(context: Context) =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasContacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    fun hasCamera(context: Context) = granted(context, Manifest.permission.CAMERA)

    fun hasCalendar(context: Context) = granted(context, Manifest.permission.READ_CALENDAR)

    fun hasActivityRecognition(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    fun ignoresBatteryOptimizations(context: Context) =
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun canOverlay(context: Context) = Settings.canDrawOverlays(context)
}
