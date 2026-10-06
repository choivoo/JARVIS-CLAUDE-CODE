package com.friday.assistant

import com.friday.assistant.ai.AIProvider
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.stt.SpeechRecognizerEngine
import com.friday.assistant.stt.SttResult
import com.friday.assistant.stt.SttState
import com.friday.assistant.voice.Speaker
import com.friday.assistant.voice.SpeechRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Replays queued answers; the last one repeats. Records every prompt. */
class FakeAi(vararg answers: Any) : AIProvider {
    override val name = "fake"
    private val queue = answers.toMutableList()
    val prompts = mutableListOf<List<ChatMessage>>()
    override suspend fun complete(messages: List<ChatMessage>): String {
        prompts += messages
        val next = if (queue.size > 1) queue.removeAt(0) else queue.first()
        if (next is Throwable) throw next
        return next as String
    }
}

/** Each listenOnce() returns the next scripted result; `gate` can hold it open to observe LISTENING. */
class FakeStt(private vararg val results: SttResult) : SpeechRecognizerEngine {
    private var i = 0
    var gate: CompletableDeferred<Unit>? = null
    override val state: StateFlow<SttState> = MutableStateFlow(SttState.IDLE)
    override val partial: StateFlow<String> = MutableStateFlow("")
    override val level: StateFlow<Float> = MutableStateFlow(0f)
    var timeouts = mutableListOf<Long?>()
    /** After the scripted results are used up the "user" stays silent, like a real follow-up window with nobody talking. */
    override suspend fun listenOnce(timeoutMs: Long?): SttResult {
        timeouts += timeoutMs
        gate?.await()
        return if (i < results.size) results[i++] else SttResult.Empty
    }
    override fun cancel() {}
}

class FakeSpeaker : Speaker {
    /** One entry per speak() call: the English sentences joined. */
    val spoken = mutableListOf<String>()
    val emphases = mutableListOf<com.friday.assistant.voice.Emphasis>()
    val koreanRequests = mutableListOf<Boolean>()
    var gate: CompletableDeferred<Unit>? = null
    var stops = 0
    override val amplitude: StateFlow<Float> = MutableStateFlow(0f)
    override var currentSpeech: String = ""
    override suspend fun speak(request: SpeechRequest) {
        val text = request.chunks.joinToString(" ") { it.speech }
        spoken += text
        emphases += request.emphasis
        koreanRequests += request.korean
        currentSpeech = text
        try {
            request.chunks.forEachIndexed { i, _ ->
                request.onChunkStart(i)
                if (i == 0) { request.onFirstAudio(); gate?.await() }
            }
        } finally { currentSpeech = "" }
    }
    override fun stop() { stops++ }
}

class RecordingExecutor(val result: (Command, Boolean) -> CommandResult) : CommandExecutor {
    val calls = mutableListOf<Pair<Command, Boolean>>()
    override suspend fun execute(command: Command, confirmed: Boolean): CommandResult {
        calls += command to confirmed
        return result(command, confirmed)
    }
}

fun routerWith(vararg pairs: Pair<CommandType, CommandExecutor>) =
    com.friday.assistant.command.CommandRouter(mapOf(*pairs))

class RecordingConfirmable(
    val prepare: (Command) -> CommandResult,
    val perform: (Command) -> CommandResult,
) : com.friday.assistant.command.ConfirmableExecutor() {
    val calls = mutableListOf<Pair<Command, Boolean>>()
    override suspend fun prepare(command: Command): CommandResult { calls += command to false; return prepare.invoke(command) }
    override suspend fun perform(command: Command): CommandResult { calls += command to true; return perform.invoke(command) }
}

class FakeCalendar(
    var events: List<com.friday.assistant.calendar.EventInfo> = emptyList(),
    var read: Boolean = true,
    var write: Boolean = false,
) : com.friday.assistant.calendar.CalendarProvider {
    val inserted = mutableListOf<Triple<String, java.time.LocalDateTime, java.time.LocalDateTime>>()
    override fun canRead() = read
    override fun canWrite() = write
    override fun events(from: java.time.LocalDateTime, to: java.time.LocalDateTime) =
        events.filter { it.start < to && it.end > from }.sortedBy { it.start }
    override fun insert(title: String, start: java.time.LocalDateTime, end: java.time.LocalDateTime): Boolean { inserted += Triple(title, start, end); return true }
}

fun ev(title: String, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, durMin: Long = 60) =
    java.time.LocalDateTime.of(y, mo, d, h, mi).let { com.friday.assistant.calendar.EventInfo(title, it, it.plusMinutes(durMin), false) }

class FakeNotifications(var enabled: Boolean = true, var items: List<com.friday.assistant.notification.NotificationInfo> = emptyList()) : com.friday.assistant.notification.NotificationSource {
    val opened = mutableListOf<String>(); val dismissed = mutableListOf<String>()
    override fun accessEnabled() = enabled
    override fun recent(limit: Int) = items.sortedByDescending { it.postedAt }.take(limit)
    override fun open(key: String) = opened.add(key)
    override fun dismiss(key: String) = dismissed.add(key)
}

fun notif(key: String, app: String, title: String, text: String, at: Long) =
    com.friday.assistant.notification.NotificationInfo(key, "pkg.$key", app, title, text, at)

class FakeDevice(var status: com.friday.assistant.device.DeviceStatus = defaultStatus) : com.friday.assistant.device.DeviceStatusProvider {
    override fun read() = status
    companion object {
        val defaultStatus = com.friday.assistant.device.DeviceStatus(
            72, false, com.friday.assistant.device.NetworkKind.WIFI, true, null, 40,
            com.friday.assistant.device.Ringer.NORMAL, 20_000_000_000, 100_000_000_000,
        )
    }
}

class FakeMedia(
    var access: Boolean = true, var session: com.friday.assistant.media.MediaSessionInfo? = null,
    var musicActive: Boolean = false, var transportWorks: Boolean = true,
) : com.friday.assistant.media.MediaBackend {
    val transports = mutableListOf<com.friday.assistant.media.MediaAction>()
    val keys = mutableListOf<com.friday.assistant.media.MediaAction>()
    override fun hasSessionAccess() = access
    override fun activeSession() = session
    override fun transport(action: com.friday.assistant.media.MediaAction): Boolean {
        if (!transportWorks) return false
        transports += action
        when (action) { com.friday.assistant.media.MediaAction.PLAY -> musicActive = true; com.friday.assistant.media.MediaAction.PAUSE, com.friday.assistant.media.MediaAction.STOP -> musicActive = false; else -> Unit }
        return true
    }
    override fun mediaKey(action: com.friday.assistant.media.MediaAction) { keys += action }
    override fun isMusicActive() = musicActive
}

class FakeAccess(var permissions: Set<String> = emptySet(), var notificationAccess: Boolean = false) : com.friday.assistant.command.AccessChecker {
    override fun hasPermission(permission: String) = permission in permissions
    override fun hasNotificationAccess() = notificationAccess
}
