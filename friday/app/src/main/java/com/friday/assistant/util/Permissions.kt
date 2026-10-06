@file:SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only checked on API 33+ (guarded below)

package com.friday.assistant.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    fun has(context: Context, permission: String) =
        (permission == Manifest.permission.POST_NOTIFICATIONS && android.os.Build.VERSION.SDK_INT < 33) ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun mic(context: Context) = has(context, Manifest.permission.RECORD_AUDIO)
    /** POST_NOTIFICATIONS only exists from Android 13; before that notifications need no runtime grant. */
    fun notifications(context: Context) =
        android.os.Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS)

    val required: List<Pair<String, String>> = listOf(
        Manifest.permission.RECORD_AUDIO to "Hear your voice",
        Manifest.permission.POST_NOTIFICATIONS to "Show the Background Assistant notification (Android 13+)",
    )

    /** Optional permissions, each with the reason shown to the user. */
    val optional: List<Pair<String, String>> = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION to "Weather for your current location",
        Manifest.permission.READ_CONTACTS to "Find contacts to call or message",
        Manifest.permission.CALL_PHONE to "Place calls directly after you confirm",
    )
}
