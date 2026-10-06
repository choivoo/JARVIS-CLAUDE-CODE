package com.jarvis.assistant.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Coarse device location without Google Play Services. Returns null whenever it is unavailable. */
class LocationProvider(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    suspend fun currentOrNull(): Location? {
        if (!Perms.hasLocation(context)) return null
        return try {
            val providers = manager.getProviders(true)
            val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < MAX_AGE_MS }
                .maxByOrNull { it.time }
            recent ?: freshFix(providers)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            JLog.w("Location", "Location lookup failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(providers: List<String>): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return null
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
    }

    private companion object {
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
