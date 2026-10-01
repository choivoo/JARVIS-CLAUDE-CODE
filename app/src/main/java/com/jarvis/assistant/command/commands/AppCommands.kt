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

    override suspend fun execute(action: AiAction): ActionResult =
        launcher.launch(Intent(Settings.ACTION_SETTINGS), "Settings").toResult(
            ActionResult.fail("I couldn't open the settings.", "설정을 열 수 없습니다."),
        )
}
