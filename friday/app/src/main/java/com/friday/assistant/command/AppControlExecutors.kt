package com.friday.assistant.command

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings

/** App-control commands beyond OPEN_APP: system pages, camera, clock, calendar, maps, browser, app lookup. */
class AppControlExecutors(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val apps: AppResolver,
) {
    fun all(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.OPEN_CAMERA to CommandExecutor { _, _ -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "the camera", "카메라") },
        CommandType.OPEN_CLOCK to CommandExecutor { _, _ -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "the clock", "시계") },
        CommandType.OPEN_CALENDAR to CommandExecutor { _, _ -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time")), "the calendar", "캘린더") },
        CommandType.OPEN_MAPS to CommandExecutor { c, _ -> maps(c.param("query")) },
        CommandType.OPEN_BROWSER to CommandExecutor { _, _ -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")), "the browser", "브라우저") },
        CommandType.OPEN_APP_SETTINGS to CommandExecutor { c, _ -> appSettings(c.param("target")) },
        CommandType.SEARCH_INSTALLED_APPS to CommandExecutor { c, _ -> search(c.param("query")) },
    )

    private fun launch(intent: Intent, en: String, ko: String): CommandResult = try {
        when (launcher.launch(intent, en)) {
            LaunchOutcome.STARTED -> CommandResult.ok("Opening $en.", "${ko}을(를) 엽니다.")
            LaunchOutcome.NOTIFIED -> CommandResult.ok("I'm in the background, so tap the notification to open $en.", "백그라운드 상태입니다. 알림을 눌러 ${ko}을(를) 여세요.")
        }
    } catch (e: ActivityNotFoundException) {
        CommandResult.failed("I couldn't find $en on this phone.", "${ko}을(를) 찾을 수 없습니다.")
    }

    private fun maps(query: String?): CommandResult {
        val uri = if (query == null) Uri.parse("geo:0,0") else Uri.parse("geo:0,0").buildUpon().appendQueryParameter("q", query).build()
        return launch(Intent(Intent.ACTION_VIEW, uri), if (query == null) "maps" else "maps for $query", "지도")
    }

    private fun appSettings(target: String?): CommandResult {
        val t = target ?: return CommandResult.failed("Which app's settings?", "어떤 앱의 설정을 열까요?")
        val app = apps.resolve(t) ?: return CommandResult.failed("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.")
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
        return launch(i, "${app.label} settings", "${app.label} 설정")
    }

    private fun search(query: String?): CommandResult {
        val q = query ?: return CommandResult.failed("Which app?", "어떤 앱을 찾을까요?")
        val app = apps.resolve(q)
        return if (app != null) CommandResult.ok("Yes, ${app.label} is installed.", "네, ${app.label} 앱이 설치되어 있습니다.")
        else CommandResult.ok("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.")
    }
}
