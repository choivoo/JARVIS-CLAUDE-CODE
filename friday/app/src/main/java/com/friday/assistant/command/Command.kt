package com.friday.assistant.command

import android.Manifest

enum class CommandCategory { SYSTEM, APP, MEDIA, COMMUNICATION, NOTIFICATION, CALENDAR, WEATHER, WEB, DEVICE, UTILITY }

/** 0 read, 1 light action, 2 changes data (confirm), 3 external communication (always confirm). */
object Risk { const val READ = 0; const val LIGHT = 1; const val WRITE = 2; const val EXTERNAL = 3 }

/**
 * The complete allow-list. The AI can only ever ask for one of these; nothing else is executed.
 * Each entry carries its category, risk level, the runtime permission it needs (if any) and an execution timeout.
 */
enum class CommandType(
    val category: CommandCategory,
    val risk: Int,
    val doc: String,
    val permission: String? = null,
    val needsNotificationAccess: Boolean = false,
    val timeoutMs: Long = 8_000,
) {
    GET_TIME(CommandCategory.SYSTEM, Risk.READ, "current time"),
    GET_DATE(CommandCategory.SYSTEM, Risk.READ, "today's date and weekday"),
    MORNING_BRIEF(CommandCategory.UTILITY, Risk.READ, "morning briefing: time, weather, events, battery, notifications", timeoutMs = 20_000),
    EVENING_BRIEF(CommandCategory.UTILITY, Risk.READ, "evening wrap-up: remaining events, tomorrow, weather", timeoutMs = 20_000),

    GET_BATTERY(CommandCategory.DEVICE, Risk.READ, "battery percentage"),
    GET_CHARGING_STATE(CommandCategory.DEVICE, Risk.READ, "whether the phone is charging"),
    GET_NETWORK_STATE(CommandCategory.DEVICE, Risk.READ, "internet / Wi-Fi / Bluetooth state"),
    GET_VOLUME(CommandCategory.DEVICE, Risk.READ, "media volume and ringer mode"),
    GET_STORAGE(CommandCategory.DEVICE, Risk.READ, "free storage"),
    GET_DEVICE_STATUS(CommandCategory.DEVICE, Risk.READ, "summary of battery, network, volume"),
    FLASHLIGHT_ON(CommandCategory.DEVICE, Risk.LIGHT, "turn flashlight on"),
    FLASHLIGHT_OFF(CommandCategory.DEVICE, Risk.LIGHT, "turn flashlight off"),
    VOLUME_UP(CommandCategory.DEVICE, Risk.LIGHT, "raise media volume"),
    VOLUME_DOWN(CommandCategory.DEVICE, Risk.LIGHT, "lower media volume"),
    SET_VOLUME(CommandCategory.DEVICE, Risk.LIGHT, "set media volume; params: percent (0-100)"),

    OPEN_APP(CommandCategory.APP, Risk.LIGHT, "open an installed app; params: target (app name)"),
    OPEN_SETTINGS(CommandCategory.APP, Risk.LIGHT, "open Android settings; params: target (wifi|bluetooth|display|sound|battery|location|apps|notifications|default)"),
    OPEN_APP_SETTINGS(CommandCategory.APP, Risk.LIGHT, "open the system settings page of an app; params: target (app name)"),
    SEARCH_INSTALLED_APPS(CommandCategory.APP, Risk.READ, "check which installed apps match; params: query"),
    OPEN_CAMERA(CommandCategory.APP, Risk.LIGHT, "open the camera"),
    OPEN_CLOCK(CommandCategory.APP, Risk.LIGHT, "open the clock / alarms"),
    OPEN_CALENDAR(CommandCategory.APP, Risk.LIGHT, "open the calendar app"),
    OPEN_MAPS(CommandCategory.APP, Risk.LIGHT, "open maps; params: query (optional place)"),
    OPEN_BROWSER(CommandCategory.APP, Risk.LIGHT, "open the web browser"),
    OPEN_YOUTUBE(CommandCategory.APP, Risk.LIGHT, "open YouTube"),
    YOUTUBE_SEARCH(CommandCategory.APP, Risk.LIGHT, "search YouTube; params: query"),

    OPEN_URL(CommandCategory.WEB, Risk.LIGHT, "open a web page; params: url (https only)"),
    WEB_SEARCH(CommandCategory.WEB, Risk.LIGHT, "open a browser search; params: query"),
    WEB_ANSWER(CommandCategory.WEB, Risk.READ, "search the web and get text results back to summarise; params: query", timeoutMs = 15_000),

    SET_ALARM(CommandCategory.UTILITY, Risk.LIGHT, "set an alarm; params: hour (0-23), minute (0-59), day (today|tomorrow), label"),

    PLAY_MEDIA(CommandCategory.MEDIA, Risk.LIGHT, "resume media playback"),
    PAUSE_MEDIA(CommandCategory.MEDIA, Risk.LIGHT, "pause media playback"),
    NEXT_MEDIA(CommandCategory.MEDIA, Risk.LIGHT, "next track"),
    PREVIOUS_MEDIA(CommandCategory.MEDIA, Risk.LIGHT, "previous track"),
    STOP_MEDIA(CommandCategory.MEDIA, Risk.LIGHT, "stop playback"),
    GET_MEDIA_STATE(CommandCategory.MEDIA, Risk.READ, "what is playing now (title, artist, app)"),

    WEATHER(CommandCategory.WEATHER, Risk.READ, "weather; params: city (optional), day (now|today|tomorrow)", timeoutMs = 15_000),

    CALL_CONTACT_REQUEST(CommandCategory.COMMUNICATION, Risk.EXTERNAL, "ask to call a contact; params: name"),
    MESSAGE_CONTACT_REQUEST(CommandCategory.COMMUNICATION, Risk.EXTERNAL, "ask to text a contact; params: name, body"),

    GET_NOTIFICATIONS(CommandCategory.NOTIFICATION, Risk.READ, "read recent notifications aloud", needsNotificationAccess = true),
    GET_NOTIFICATION_COUNT(CommandCategory.NOTIFICATION, Risk.READ, "number of recent notifications", needsNotificationAccess = true),
    READ_LATEST_NOTIFICATION(CommandCategory.NOTIFICATION, Risk.READ, "read the newest notification", needsNotificationAccess = true),
    READ_NOTIFICATIONS_FROM_APP(CommandCategory.NOTIFICATION, Risk.READ, "read notifications of one app; params: target (app name)", needsNotificationAccess = true),
    OPEN_NOTIFICATION(CommandCategory.NOTIFICATION, Risk.LIGHT, "open the newest notification (optionally of an app); params: target", needsNotificationAccess = true),
    DISMISS_NOTIFICATION(CommandCategory.NOTIFICATION, Risk.WRITE, "dismiss the newest notification (optionally of an app); params: target", needsNotificationAccess = true),

    GET_TODAY_EVENTS(CommandCategory.CALENDAR, Risk.READ, "today's calendar events", Manifest.permission.READ_CALENDAR),
    GET_TOMORROW_EVENTS(CommandCategory.CALENDAR, Risk.READ, "tomorrow's calendar events", Manifest.permission.READ_CALENDAR),
    GET_NEXT_EVENT(CommandCategory.CALENDAR, Risk.READ, "the next upcoming event", Manifest.permission.READ_CALENDAR),
    SEARCH_EVENTS(CommandCategory.CALENDAR, Risk.READ, "search upcoming events; params: query", Manifest.permission.READ_CALENDAR),
    CREATE_EVENT_REQUEST(CommandCategory.CALENDAR, Risk.WRITE, "ask to add a calendar event; params: title, date (yyyy-MM-dd), hour, minute, durationMin"),
    ;

    val needsConfirmation: Boolean get() = risk >= Risk.WRITE

    companion object {
        fun parse(raw: String?): CommandType? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

        fun promptCatalog(): String = entries.joinToString("\n") { "- ${it.name}: ${it.doc}" }
    }
}

data class Command(val type: CommandType, val params: Map<String, String> = emptyMap()) {
    fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
}

enum class ResultStatus { OK, FAILED, UNSUPPORTED, NEEDS_CONFIRMATION, NEEDS_PERMISSION }

enum class CardKind { WEATHER, CALENDAR, MEDIA, NOTIFICATION, DEVICE }

/** Optional panel the HUD shows next to the core after a command. Only shown when there is something to show. */
data class InfoCard(val kind: CardKind, val title: String, val lines: List<String>)

/**
 * Outcome of a command. `speech` (English) and `subtitle` (Korean) are always filled so the assistant can answer
 * even when the AI is unavailable. `data` is raw material the AI may phrase; it is null for private content
 * (notifications, calendar details), which therefore never leaves the device. `contextNote` is a tiny non-private
 * summary the ContextEngine may remember for follow-ups.
 */
data class CommandResult(
    val status: ResultStatus,
    val speech: String,
    val subtitle: String,
    val data: String? = null,
    val pending: Command? = null,
    val card: InfoCard? = null,
    val contextNote: String? = null,
    val warning: Boolean = false,
) {
    val ok get() = status == ResultStatus.OK

    companion object {
        fun ok(speech: String, subtitle: String, data: String? = null, card: InfoCard? = null, contextNote: String? = null) =
            CommandResult(ResultStatus.OK, speech, subtitle, data, card = card, contextNote = contextNote)
        fun failed(speech: String, subtitle: String) = CommandResult(ResultStatus.FAILED, speech, subtitle)
        fun unsupported(what: String) = CommandResult(
            ResultStatus.UNSUPPORTED, "I can't do that yet.", "아직 지원하지 않는 명령입니다: $what",
        )
        fun confirm(speech: String, subtitle: String, pending: Command) =
            CommandResult(ResultStatus.NEEDS_CONFIRMATION, speech, subtitle, pending = pending)
        fun permission(speech: String, subtitle: String) = CommandResult(ResultStatus.NEEDS_PERMISSION, speech, subtitle)
    }
}
