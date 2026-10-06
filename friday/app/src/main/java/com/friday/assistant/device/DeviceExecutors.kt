package com.friday.assistant.device

import com.friday.assistant.command.CardKind
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.InfoCard
import com.friday.assistant.util.EnglishNumbers

/** Read-only device information. Battery keeps its original executor in AndroidExecutors. */
class DeviceExecutors(private val provider: DeviceStatusProvider) {
    fun all(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.GET_BATTERY to CommandExecutor { _, _ -> battery() },
        CommandType.GET_CHARGING_STATE to CommandExecutor { _, _ -> charging() },
        CommandType.GET_NETWORK_STATE to CommandExecutor { _, _ -> network() },
        CommandType.GET_VOLUME to CommandExecutor { _, _ -> volume() },
        CommandType.GET_STORAGE to CommandExecutor { _, _ -> storage() },
        CommandType.GET_DEVICE_STATUS to CommandExecutor { _, _ -> summary() },
    )

    private fun battery(): CommandResult {
        val s = provider.read()
        val p = s.batteryPercent ?: return CommandResult.failed("I couldn't read the battery level.", "배터리 잔량을 읽지 못했습니다.")
        val ch = s.charging == true
        return CommandResult.ok(
            "Your battery is currently at ${EnglishNumbers.words(p)} percent${if (ch) " and charging" else ""}.",
            "현재 배터리는 ${p}%입니다${if (ch) " (충전 중)" else ""}.",
            card = InfoCard(CardKind.DEVICE, "배터리 $p%", listOf(if (ch) "충전 중" else "방전 중")),
            contextNote = "battery $p%",
        )
    }

    private fun charging(): CommandResult {
        val s = provider.read()
        val ch = s.charging ?: return CommandResult.failed("I can't tell whether it's charging.", "충전 상태를 확인할 수 없습니다.")
        val p = s.batteryPercent
        return if (ch) CommandResult.ok("Yes, it's charging${p?.let { " at ${EnglishNumbers.words(it)} percent" } ?: ""}.", "네, 충전 중입니다${p?.let { " (${it}%)" } ?: ""}.")
        else CommandResult.ok("No, it's not charging.", "아니요, 충전 중이 아닙니다.")
    }

    private fun network(): CommandResult {
        val s = provider.read()
        val (en, ko) = when (s.network) {
            NetworkKind.WIFI -> "You're connected to the internet over Wi-Fi." to "Wi-Fi로 인터넷에 연결되어 있습니다."
            NetworkKind.CELLULAR -> "You're online over mobile data." to "모바일 데이터로 인터넷에 연결되어 있습니다."
            NetworkKind.ETHERNET -> "You're online over a wired connection." to "유선으로 인터넷에 연결되어 있습니다."
            NetworkKind.OTHER -> "You're online." to "인터넷에 연결되어 있습니다."
            NetworkKind.NONE -> "You're offline right now." to "현재 인터넷에 연결되어 있지 않습니다."
        }
        val bt = when (s.bluetoothEnabled) { true -> " Bluetooth is on."; false -> " Bluetooth is off."; null -> "" }
        val btKo = when (s.bluetoothEnabled) { true -> " 블루투스 켜짐."; false -> " 블루투스 꺼짐."; null -> "" }
        val wifi = if (s.wifiEnabled == false) " Wi-Fi is switched off." else ""
        val wifiKo = if (s.wifiEnabled == false) " Wi-Fi는 꺼져 있습니다." else ""
        return CommandResult.ok(en + wifi + bt, ko + wifiKo + btKo)
    }

    private fun volume(): CommandResult {
        val s = provider.read()
        val r = when (s.ringer) { Ringer.NORMAL -> "normal" to "소리"; Ringer.VIBRATE -> "vibrate" to "진동"; Ringer.SILENT -> "silent" to "무음" }
        return CommandResult.ok(
            "Media volume is ${EnglishNumbers.words(s.volumePercent)} percent and the ringer is on ${r.first}.",
            "미디어 볼륨은 ${s.volumePercent}%이고 벨소리 모드는 ${r.second}입니다.",
        )
    }

    private fun storage(): CommandResult {
        val s = provider.read()
        val freeGb = s.freeBytes / 1_000_000_000.0
        val pct = if (s.totalBytes > 0) (s.freeBytes * 100 / s.totalBytes).toInt() else 0
        return CommandResult.ok(
            "You have about ${EnglishNumbers.words(Math.round(freeGb).toInt())} gigabytes free, ${EnglishNumbers.words(pct)} percent of the storage.",
            "여유 저장 공간은 약 ${"%.1f".format(freeGb)}GB(${pct}%)입니다.",
        )
    }

    private fun summary(): CommandResult {
        val s = provider.read()
        val parts = mutableListOf<String>(); val partsKo = mutableListOf<String>()
        s.batteryPercent?.let { parts += "battery ${EnglishNumbers.words(it)} percent${if (s.charging == true) " and charging" else ""}"; partsKo += "배터리 ${it}%" + if (s.charging == true) "(충전 중)" else "" }
        parts += if (s.network == NetworkKind.NONE) "offline" else "online"
        partsKo += if (s.network == NetworkKind.NONE) "오프라인" else "온라인"
        parts += "volume ${EnglishNumbers.words(s.volumePercent)} percent"; partsKo += "볼륨 ${s.volumePercent}%"
        return CommandResult.ok(
            "Here's your status: " + parts.joinToString(", ") + ".", "기기 상태: " + partsKo.joinToString(", "),
            card = InfoCard(CardKind.DEVICE, "기기 상태", partsKo),
        )
    }
}
