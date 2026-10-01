package com.jarvis.assistant.command.commands

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.Command

internal fun ActivityLauncher.Outcome.toResult(failure: ActionResult): ActionResult = when (this) {
    ActivityLauncher.Outcome.STARTED -> ActionResult.ok()
    ActivityLauncher.Outcome.NOTIFIED -> ActivityLauncher.NOTIFIED_RESULT
    ActivityLauncher.Outcome.FAILED -> failure
}

class OpenAppCommand(
    private val resolver: AppResolver,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf("OPEN_APP", "LAUNCH_APP", "OPEN_APPLICATION")

    override suspend fun execute(action: AiAction): ActionResult {
        val app = resolver.resolve(action.param("package"), action.param("app") ?: action.param("name"))
            ?: return ActionResult.fail("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.")
        return launcher.launch(app.launchIntent, app.label).toResult(
            ActionResult.fail("I couldn't open ${app.label}.", "${app.label}을(를) 열 수 없습니다."),
        )
    }
}

class OpenUrlCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("OPEN_URL", "OPEN_LINK", "OPEN_WEBSITE")

    override suspend fun execute(action: AiAction): ActionResult {
        val raw = action.param("url") ?: return ActionResult.fail("I need a web address.", "웹 주소가 필요합니다.")
        val uri = Uri.parse(if (raw.contains("://")) raw else "https://$raw")
        // Allow-list: only plain web links, never intent:, file:, content: or app schemes.
        if (uri.scheme != "https" && uri.scheme != "http" || uri.host.isNullOrEmpty()) {
            return ActionResult.fail("That doesn't look like a safe web address.", "안전한 웹 주소가 아닙니다.")
        }
        return launcher.launch(Intent(Intent.ACTION_VIEW, uri), uri.host.orEmpty()).toResult(
            ActionResult.fail("I couldn't open that page.", "해당 페이지를 열 수 없습니다."),
        )
    }
}

class OpenSettingsCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("OPEN_SETTINGS", "SETTINGS")

    private val targets: Map<String, Pair<String, Pair<String, String>>> = mapOf(
        "wifi" to (Settings.ACTION_WIFI_SETTINGS to ("Wi-Fi settings" to "와이파이 설정")),
        "bluetooth" to (Settings.ACTION_BLUETOOTH_SETTINGS to ("Bluetooth settings" to "블루투스 설정")),
        "display" to (Settings.ACTION_DISPLAY_SETTINGS to ("display settings" to "디스플레이 설정")),
        "sound" to (Settings.ACTION_SOUND_SETTINGS to ("sound settings" to "소리 설정")),
        "battery" to (Settings.ACTION_BATTERY_SAVER_SETTINGS to ("battery settings" to "배터리 설정")),
        "location" to (Settings.ACTION_LOCATION_SOURCE_SETTINGS to ("location settings" to "위치 설정")),
        "apps" to (Settings.ACTION_APPLICATION_SETTINGS to ("app settings" to "앱 설정")),
        "accessibility" to (Settings.ACTION_ACCESSIBILITY_SETTINGS to ("accessibility settings" to "접근성 설정")),
        "date" to (Settings.ACTION_DATE_SETTINGS to ("date and time settings" to "날짜 및 시간 설정")),
        "airplane" to (Settings.ACTION_AIRPLANE_MODE_SETTINGS to ("airplane mode settings" to "비행기 모드 설정")),
        "nfc" to (Settings.ACTION_NFC_SETTINGS to ("NFC settings" to "NFC 설정")),
    )

    override suspend fun execute(action: AiAction): ActionResult {
        val key = action.param("target")?.lowercase()
        val entry = targets[key]
        val intent = Intent(entry?.first ?: Settings.ACTION_SETTINGS)
        return launcher.launch(intent, entry?.second?.first ?: "Settings").toResult(
            ActionResult.fail("I couldn't open the settings.", "설정을 열 수 없습니다."),
        )
    }
}
