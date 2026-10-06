package com.friday.assistant.command

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactHit(val displayName: String, val number: String)

class ContactResolver(private val context: Context) {
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Looks up the first contact matching any of the spoken names. Requires READ_CONTACTS. */
    fun find(spoken: String): ContactHit? {
        for (name in candidates(spoken)) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null,
            )?.use { c ->
                if (c.moveToFirst()) return ContactHit(c.getString(0), c.getString(1))
            }
        }
        return null
    }

    companion object {
        fun candidates(spoken: String): List<String> {
            val s = spoken.trim().removeSuffix("한테").removeSuffix("에게").trim()
            val family = mapOf(
                "엄마" to listOf("엄마", "어머니", "mom", "mother"),
                "어머니" to listOf("어머니", "엄마", "mom", "mother"),
                "아빠" to listOf("아빠", "아버지", "dad", "father"),
                "아버지" to listOf("아버지", "아빠", "dad", "father"),
            )
            return family[s] ?: listOf(s)
        }
    }
}

class LocationHelper(private val context: Context) {
    data class Coords(val lat: Double, val lon: Double)

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known location only; never starts continuous tracking. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Coords? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return Coords(loc.latitude, loc.longitude)
    }
}
