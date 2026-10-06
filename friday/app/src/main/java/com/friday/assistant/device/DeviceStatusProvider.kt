package com.friday.assistant.device

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

enum class NetworkKind { WIFI, CELLULAR, ETHERNET, OTHER, NONE }
enum class Ringer { NORMAL, VIBRATE, SILENT }

data class DeviceStatus(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val network: NetworkKind,
    val wifiEnabled: Boolean?,
    /** null when Android does not let this app tell. */
    val bluetoothEnabled: Boolean?,
    val volumePercent: Int,
    val ringer: Ringer,
    val freeBytes: Long,
    val totalBytes: Long,
)

interface DeviceStatusProvider { fun read(): DeviceStatus }

/** Reads only what a normal app is allowed to see; anything restricted is reported as unknown, never guessed. */
class AndroidDeviceStatusProvider(private val context: Context) : DeviceStatusProvider {
    override fun read(): DeviceStatus {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 }
        if (pct == null && sticky != null) {
            val l = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && sc > 0) pct = l * 100 / sc
        }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
        val wifi = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }.getOrNull()
        val bt = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val ringer = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> Ringer.SILENT; AudioManager.RINGER_MODE_VIBRATE -> Ringer.VIBRATE; else -> Ringer.NORMAL }

        val stat = StatFs(Environment.getDataDirectory().path)
        return DeviceStatus(pct, charging, net, wifi, bt, vol, ringer, stat.availableBytes, stat.totalBytes)
    }
}
