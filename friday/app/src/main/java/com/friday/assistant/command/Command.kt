package com.friday.assistant.command

/** The complete allow-list. The AI can only ever ask for one of these; nothing else is executed. */
enum class CommandType(val doc: String, val needsConfirmation: Boolean = false) {
    GET_TIME("current time"),
    GET_DATE("today's date and weekday"),
    GET_BATTERY("battery percentage"),
    OPEN_APP("open an installed app; params: target (app name)"),
    OPEN_URL("open a web page; params: url (https only)"),
    WEB_SEARCH("open a browser search; params: query"),
    WEB_ANSWER("search the web and get text results back to summarise; params: query"),
    YOUTUBE_SEARCH("search YouTube; params: query"),
    OPEN_YOUTUBE("open YouTube"),
    SET_ALARM("set an alarm; params: hour (0-23), minute (0-59), day (today|tomorrow), label"),
    FLASHLIGHT_ON("turn flashlight on"),
    FLASHLIGHT_OFF("turn flashlight off"),
    VOLUME_UP("raise media volume"),
    VOLUME_DOWN("lower media volume"),
    SET_VOLUME("set media volume; params: percent (0-100)"),
    PLAY_MEDIA("resume media playback"),
    PAUSE_MEDIA("pause media playback"),
    NEXT_MEDIA("next track"),
    PREVIOUS_MEDIA("previous track"),
    WEATHER("weather; params: city (optional), day (now|today|tomorrow)"),
    OPEN_SETTINGS("open Android settings; params: target (wifi|bluetooth|display|sound|battery|location|apps|default)"),
    CALL_CONTACT_REQUEST("ask to call a contact; params: name", needsConfirmation = true),
    MESSAGE_CONTACT_REQUEST("ask to text a contact; params: name, body", needsConfirmation = true);

    companion object {
        fun parse(raw: String?): CommandType? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

        fun promptCatalog(): String = entries.joinToString("\n") { "- ${it.name}: ${it.doc}" }
    }
}

data class Command(val type: CommandType, val params: Map<String, String> = emptyMap()) {
    fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
}

enum class ResultStatus { OK, FAILED, UNSUPPORTED, NEEDS_CONFIRMATION, NEEDS_PERMISSION }

/**
 * Outcome of a command. `speech` (English) and `subtitle` (Korean) are always filled so the
 * assistant can answer even when the AI is unavailable. `data` is raw material for the AI to phrase.
 */
data class CommandResult(
    val status: ResultStatus,
    val speech: String,
    val subtitle: String,
    val data: String? = null,
    val pending: Command? = null,
) {
    val ok get() = status == ResultStatus.OK

    companion object {
        fun ok(speech: String, subtitle: String, data: String? = null) = CommandResult(ResultStatus.OK, speech, subtitle, data)
        fun failed(speech: String, subtitle: String) = CommandResult(ResultStatus.FAILED, speech, subtitle)
        fun unsupported(what: String) = CommandResult(
            ResultStatus.UNSUPPORTED, "I can't do that yet.", "아직 지원하지 않는 명령입니다: $what",
        )
        fun confirm(speech: String, subtitle: String, pending: Command) =
            CommandResult(ResultStatus.NEEDS_CONFIRMATION, speech, subtitle, pending = pending)
        fun permission(speech: String, subtitle: String) = CommandResult(ResultStatus.NEEDS_PERMISSION, speech, subtitle)
    }
}
