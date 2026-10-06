package com.friday.assistant.core

import com.friday.assistant.ai.AIProvider
import com.friday.assistant.ai.AiReply
import com.friday.assistant.ai.AiReplyParser
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.PromptBuilder
import com.friday.assistant.ai.Role
import com.friday.assistant.command.Answer
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.LocalIntentParser
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.command.YesNoParser
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.stt.SpeechRecognizerEngine
import com.friday.assistant.stt.SttResult
import com.friday.assistant.tts.Speaker
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The whole FRIDAY pipeline:
 * wake / tap -> STT -> (local shortcut | AI) -> CommandRouter -> result -> AI phrasing -> English TTS + Korean subtitle.
 * One interaction at a time: a new one cancels the previous and silences the speaker immediately.
 */
class FridayController(
    private val scope: CoroutineScope,
    private val settings: () -> FridaySettings,
    private val stt: SpeechRecognizerEngine,
    private val aiProvider: () -> AIProvider,
    private val router: CommandRouter,
    private val speaker: Speaker,
    private val repo: ConversationRepository,
    private val isOnline: () -> Boolean,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val _core = MutableStateFlow(CoreState.IDLE)
    val core: StateFlow<CoreState> = _core.asStateFlow()
    private val _subtitle = MutableStateFlow(SubtitleState())
    val subtitle: StateFlow<SubtitleState> = _subtitle.asStateFlow()
    val partial: StateFlow<String> get() = stt.partial
    val micLevel: StateFlow<Float> get() = stt.level
    val amplitude: StateFlow<Float> get() = speaker.amplitude

    private var job: Job? = null
    private var pending: Command? = null

    // ---- entry points ---------------------------------------------------------------------------------------

    /** Wake word heard. [remainder] is anything said after "FRIDAY" in the same utterance. */
    fun onWake(remainder: String) = begin {
        if (remainder.trim().length >= 2) {
            process(remainder.trim())
        } else {
            say(Spoken("Yes?", "네, 말씀하세요."))
            listenAndProcess()
        }
    }

    /** Mic button. */
    fun startListening() = begin { listenAndProcess() }

    /** Typed input from the conversation screen. */
    fun submitText(text: String) { if (text.isNotBlank()) begin { process(text.trim()) } }

    /** Stop button: silence and return to idle. */
    fun stop() {
        job?.cancel()
        job = null
        stt.cancel()
        speaker.stop()
        pending = null
        _subtitle.value = SubtitleState()
        _core.value = CoreState.IDLE
    }

    private fun begin(block: suspend () -> Unit) {
        job?.cancel()
        stt.cancel()
        speaker.stop()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(Spoken("Something unexpected happened.", "예상치 못한 오류가 발생했습니다."))
            }
        }
    }

    // ---- pipeline -------------------------------------------------------------------------------------------

    private suspend fun listenAndProcess() {
        _core.value = CoreState.LISTENING
        _subtitle.value = SubtitleState()
        when (val r = stt.listenOnce()) {
            is SttResult.Text -> process(r.text)
            SttResult.Empty -> { pending = null; fail(Errors.empty, speak = false) }
            is SttResult.Failure -> { pending = null; fail(Errors.stt(r.error), speak = false) }
        }
    }

    private suspend fun process(text: String) {
        _core.value = CoreState.THINKING
        _subtitle.value = SubtitleState(user = text)
        val s = settings()
        if (s.memoryEnabled) runCatching { repo.addUser(text) }

        pending?.let { cmd ->
            pending = null
            when (YesNoParser.parse(text)) {
                Answer.YES -> { _core.value = CoreState.EXECUTING; reply(router.execute(cmd, confirmed = true).toSpoken()); return }
                Answer.NO -> { reply(Errors.cancelled); return }
                Answer.UNKNOWN -> Unit // treat as a fresh request
            }
        }

        LocalIntentParser.parse(text, now())?.let { cmd ->
            _core.value = CoreState.EXECUTING
            handleResult(router.execute(cmd), allowAi = false, userText = text)
            return
        }
        if (!isOnline()) {
            _core.value = CoreState.OFFLINE
            reply(Errors.offlineNoLocal)
            return
        }
        askAi(text, s)
    }

    private suspend fun askAi(text: String, s: FridaySettings) {
        val provider = try { aiProvider() } catch (e: Exception) { fail(Errors.ai(e)); return }
        val system = ChatMessage(Role.SYSTEM, PromptBuilder.systemPrompt(now().format(ISO), s.defaultCity))
        val history = if (s.memoryEnabled) runCatching { repo.context() }.getOrDefault(emptyList()) else listOf(ChatMessage(Role.USER, text))
        val messages = listOf(system) + history.ifEmpty { listOf(ChatMessage(Role.USER, text)) }
        val raw = try { provider.complete(messages) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(Errors.ai(e)); return }
        val ai = AiReplyParser.parse(raw)
        val action = ai.action
        if (action == null) { reply(Spoken(ai.speech, ai.subtitle)); return }

        _core.value = CoreState.EXECUTING
        val result = router.execute(action)
        handleResult(result, allowAi = true, userText = text, messages = messages, rawAi = raw, provider = provider, ai = ai)
    }

    private suspend fun handleResult(
        result: CommandResult,
        allowAi: Boolean,
        userText: String,
        messages: List<ChatMessage> = emptyList(),
        rawAi: String = "",
        provider: AIProvider? = null,
        ai: AiReply? = null,
    ) {
        if (result.status == ResultStatus.NEEDS_CONFIRMATION) {
            pending = result.pending
            reply(result.toSpoken(), listenAfter = true)
            return
        }
        // Data-bearing results (weather, web) are phrased by the AI; everything else uses the exact, truthful result text.
        val data = result.data
        if (allowAi && result.ok && data != null && provider != null) {
            _core.value = CoreState.THINKING
            val phrased = try {
                val follow = messages + ChatMessage(Role.ASSISTANT, rawAi) +
                    ChatMessage(Role.USER, PromptBuilder.toolResultPrompt(ai?.action?.type ?: "tool", data))
                AiReplyParser.parse(provider.complete(follow))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            reply(if (phrased != null) Spoken(phrased.speech, phrased.subtitle) else result.toSpoken())
        } else {
            reply(result.toSpoken())
        }
    }

    // ---- output ---------------------------------------------------------------------------------------------

    private suspend fun reply(line: Spoken, listenAfter: Boolean = false) {
        if (settings().memoryEnabled) runCatching { repo.addAssistant(line.speech, line.subtitle) }
        say(line)
        val again = listenAfter || pending != null || settings().autoListen && !line.speech.isBlank() && line.speech.trimEnd().endsWith("?")
        if (again) listenAndProcess() else _core.value = CoreState.IDLE
    }

    /** Shows the Korean subtitle while the English voice speaks, then hides it. */
    private suspend fun say(line: Spoken) {
        val user = _subtitle.value.user
        val speak = settings().voiceFeedback
        _subtitle.value = SubtitleState(user = user, subtitle = line.subtitle, speaking = speak)
        _core.value = CoreState.SPEAKING
        try {
            if (speak) {
                try { speaker.speak(line.speech) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(readTimeMs(line.subtitle)) }
            } else {
                delay(readTimeMs(line.subtitle))
            }
        } finally {
            _subtitle.value = SubtitleState(user = user)
        }
    }

    private suspend fun fail(line: Spoken, speak: Boolean = true) {
        _core.value = CoreState.ERROR
        _subtitle.value = SubtitleState(subtitle = line.subtitle)
        if (speak && settings().voiceFeedback) {
            try { speaker.speak(line.speech) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* subtitle already shown */ }
        } else delay(readTimeMs(line.subtitle))
        _subtitle.value = SubtitleState()
        delay(1_200)
        _core.value = CoreState.IDLE
    }

    private fun CommandResult.toSpoken() = Spoken(speech, subtitle)
    private fun readTimeMs(text: String) = (1_800L + text.length * 70L).coerceAtMost(8_000L)

    private companion object { val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm") }
}
