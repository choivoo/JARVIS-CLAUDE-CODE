package com.friday.assistant.core

import com.friday.assistant.ai.AIProvider
import com.friday.assistant.ai.AiException
import com.friday.assistant.ai.AiReply
import com.friday.assistant.ai.AiReplyParser
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.PromptBuilder
import com.friday.assistant.ai.Role
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.FollowUpResolver
import com.friday.assistant.command.InfoCard
import com.friday.assistant.command.LocalIntentParser
import com.friday.assistant.command.ResultStatus
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.stt.SpeechRecognizerEngine
import com.friday.assistant.stt.SttResult
import com.friday.assistant.voice.Emphasis
import com.friday.assistant.voice.Speaker
import com.friday.assistant.voice.SpeechRequest
import com.friday.assistant.voice.SubtitleSynchronizer
import com.friday.assistant.wake.WakeWordMatcher
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole FRIDAY pipeline:
 * wake / tap -> STT -> context -> (local shortcut | AI) -> CommandRouter -> result -> response -> English TTS + Korean subtitle
 * -> follow-up listening. One interaction at a time: a new one cancels the previous and silences the speaker.
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
    val contextEngine: ContextEngine = ContextEngine(),
    private val confirmations: ConfirmationManager = ConfirmationManager(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val aiTimeoutMs: Long = 25_000,
    private val aiRetryDelayMs: Long = 600,
) {
    private val _core = MutableStateFlow(CoreState.IDLE)
    val core: StateFlow<CoreState> = _core.asStateFlow()
    private val _subtitle = MutableStateFlow(SubtitleState())
    val subtitle: StateFlow<SubtitleState> = _subtitle.asStateFlow()
    private val _card = MutableStateFlow<InfoCard?>(null)
    val card: StateFlow<InfoCard?> = _card.asStateFlow()
    private val _latency = MutableStateFlow<LatencyReport?>(null)
    /** Timings of the most recent answered interaction; fields never measured stay null. */
    val latency: StateFlow<LatencyReport?> = _latency.asStateFlow()
    private val _contextSize = MutableStateFlow(0)
    val contextSize: StateFlow<Int> = _contextSize.asStateFlow()
    val partial: StateFlow<String> get() = stt.partial
    val micLevel: StateFlow<Float> get() = stt.level
    val amplitude: StateFlow<Float> get() = speaker.amplitude

    private val tracker = LatencyTracker(clockMs)
    private var job: Job? = null
    private var subtitleJob: Job? = null
    private var cardJob: Job? = null

    // ---- entry points ---------------------------------------------------------------------------------------

    /** Wake word heard. [remainder] is anything said after "FRIDAY" in the same utterance. Also acts as barge-in. */
    fun onWake(remainder: String) {
        tracker.wakeDetected()
        begin(resetLatency = false) {
            if (remainder.trim().length >= 2) {
                process(remainder.trim())
            } else {
                say(Spoken("Yes?", "네, 말씀하세요."))
                listenAndProcess()
            }
        }
    }

    /** True when a heard "FRIDAY" is probably FRIDAY's own voice saying the word, so it must not be treated as a call. */
    fun shouldIgnoreWake(): Boolean = _core.value == CoreState.SPEAKING && WakeWordMatcher.match(speaker.currentSpeech) != null

    /** Mic button. */
    fun startListening() = begin { listenAndProcess() }

    /** Typed input from the conversation screen. */
    fun submitText(text: String) { if (text.isNotBlank()) begin { process(text.trim()) } }

    /** Stop button / "그만": silence and return to idle. */
    fun stop() {
        job?.cancel()
        job = null
        subtitleJob?.cancel()
        stt.cancel()
        speaker.stop()
        confirmations.clear()
        _subtitle.value = SubtitleState()
        _core.value = CoreState.IDLE
    }

    private fun begin(resetLatency: Boolean = true, block: suspend () -> Unit) {
        job?.cancel()
        subtitleJob?.cancel()
        stt.cancel()
        speaker.stop()
        if (resetLatency) tracker.reset()
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

    private suspend fun listenAndProcess(followUpMs: Long? = null) {
        _core.value = CoreState.LISTENING
        if (followUpMs == null) _subtitle.value = SubtitleState()
        tracker.listeningStarted()
        when (val r = stt.listenOnce(followUpMs)) {
            is SttResult.Text -> { tracker.sttFinished(); process(r.text) }
            SttResult.Empty -> {
                confirmations.clear()
                if (followUpMs != null) _core.value = CoreState.IDLE // nobody spoke in the follow-up window: just go quiet
                else fail(Errors.empty, speak = false)
            }
            is SttResult.Failure -> { confirmations.clear(); fail(Errors.stt(r.error), speak = false) }
        }
    }

    private suspend fun process(text: String) {
        tracker.textReceived()
        _core.value = CoreState.THINKING
        _subtitle.value = SubtitleState(user = text)
        val s = settings()
        if (s.memoryEnabled) runCatching { repo.addUser(text) }

        when (val o = confirmations.resolve(text)) {
            is ConfirmationManager.Outcome.Confirmed -> { _core.value = CoreState.EXECUTING; handleResult(o.command.type, runCommand(o.command, confirmed = true)); return }
            ConfirmationManager.Outcome.Declined -> { reply(Errors.cancelled); return }
            else -> Unit // a new request: carry on
        }

        if (isStopPhrase(text)) { stop(); return }

        FollowUpResolver.resolve(text, contextEngine)?.let { reply(it); return }

        LocalIntentParser.parse(text, now())?.let { cmd ->
            _core.value = CoreState.EXECUTING
            handleResult(cmd.type, runCommand(cmd), allowAi = false)
            return
        }
        if (!isOnline()) {
            _core.value = CoreState.OFFLINE
            reply(Errors.offlineNoLocal, followUp = false)
            return
        }
        askAi(text, s)
    }

    private suspend fun runCommand(cmd: Command, confirmed: Boolean = false): CommandResult {
        val t0 = clockMs()
        return router.execute(cmd, confirmed).also { tracker.commandFinished(clockMs() - t0) }
    }

    private suspend fun askAi(text: String, s: FridaySettings) {
        val provider = try { aiProvider() } catch (e: Exception) { fail(Errors.ai(e)); return }
        val prompt = PromptBuilder.systemPrompt(now().format(ISO), s.defaultCity) +
            (contextEngine.promptBlock()?.let { "\n$it" } ?: "")
        val system = ChatMessage(Role.SYSTEM, prompt)
        val history = if (s.memoryEnabled) runCatching { repo.context() }.getOrDefault(emptyList()) else listOf(ChatMessage(Role.USER, text))
        val messages = listOf(system) + history.ifEmpty { listOf(ChatMessage(Role.USER, text)) }
        val raw = try { complete(provider, messages) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(Errors.ai(e)); return }
        val ai = AiReplyParser.parse(raw)
        val action = ai.action
        if (action == null) { reply(Spoken(ai.speech, ai.subtitle)); return }

        _core.value = CoreState.EXECUTING
        val cmd = router.toCommand(action)
        if (cmd == null) { reply(CommandResult.unsupported(action.type).toSpoken()); return }
        handleResult(cmd.type, runCommand(cmd), allowAi = true, messages = messages, rawAi = raw, provider = provider)
    }

    /** One AI call with a timeout; a second attempt only for timeouts and server errors, never for key/rate-limit problems. */
    private suspend fun complete(provider: AIProvider, messages: List<ChatMessage>): String {
        var last: Exception? = null
        repeat(2) { attempt ->
            val t0 = clockMs()
            try {
                val out = withTimeoutOrNull(aiTimeoutMs) { provider.complete(messages) } ?: throw AiException.Timeout()
                tracker.aiFinished(clockMs() - t0)
                return out
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tracker.aiFinished(clockMs() - t0)
                last = e
                val retryable = e is AiException.Timeout || (e is AiException.Server && e.code >= 500)
                if (!retryable || attempt == 1) throw e
                delay(aiRetryDelayMs)
            }
        }
        throw last ?: AiException.BadResponse("no response")
    }

    private suspend fun handleResult(
        type: CommandType,
        result: CommandResult,
        allowAi: Boolean = false,
        messages: List<ChatMessage> = emptyList(),
        rawAi: String = "",
        provider: AIProvider? = null,
    ) {
        result.card?.let { showCard(it) }
        if (result.status == ResultStatus.NEEDS_CONFIRMATION) {
            result.pending?.let { confirmations.hold(it) }
            reply(result.toSpoken(), followUp = false)
            return
        }
        (result.data ?: result.contextNote)?.let { contextEngine.remember(type.name.lowercase(), it); _contextSize.value = contextEngine.size }
        // Data-bearing results (weather, web) are phrased by the AI; everything else uses the exact, truthful result text.
        val data = result.data
        if (allowAi && result.ok && data != null && provider != null) {
            _core.value = CoreState.THINKING
            val phrased = try {
                val follow = messages + ChatMessage(Role.ASSISTANT, rawAi) +
                    ChatMessage(Role.USER, PromptBuilder.toolResultPrompt(type.name, data))
                AiReplyParser.parse(complete(provider, follow))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            reply(if (phrased != null) Spoken(phrased.speech, phrased.subtitle) else result.toSpoken(), result.warning)
        } else {
            reply(result.toSpoken(), result.warning)
        }
    }

    private fun showCard(card: InfoCard) {
        cardJob?.cancel()
        _card.value = card
        cardJob = scope.launch { delay(CARD_MS); _card.value = null }
    }

    // ---- output ---------------------------------------------------------------------------------------------

    private suspend fun reply(line: Spoken, warning: Boolean = false, followUp: Boolean = true) {
        if (settings().memoryEnabled) runCatching { repo.addAssistant(line.speech, line.subtitle) }
        say(line, if (warning) Emphasis.WARNING else Emphasis.NORMAL)
        val s = settings()
        val question = line.speech.trimEnd().endsWith("?")
        when {
            confirmations.hasPending -> listenAndProcess()
            followUp && s.followUpEnabled -> listenAndProcess(followUpMs = s.followUpTimeoutSec * 1_000L)
            s.autoListen && question -> listenAndProcess()
            else -> _core.value = CoreState.IDLE
        }
    }

    /** Speaks sentence by sentence and shows each sentence's Korean subtitle while it is spoken. */
    private suspend fun say(line: Spoken, emphasis: Emphasis = Emphasis.NORMAL) {
        subtitleJob?.cancel()
        val s = settings()
        val user = _subtitle.value.user
        val chunks = SubtitleSynchronizer.split(line.speech, line.subtitle)
        _core.value = CoreState.SPEAKING
        _subtitle.value = SubtitleState(user, chunks.firstOrNull()?.subtitle ?: line.subtitle, speaking = s.voiceFeedback)
        tracker.ttsRequested()
        try {
            if (s.voiceFeedback) {
                speaker.speak(
                    SpeechRequest(
                        chunks, emphasis,
                        onChunkStart = { i -> _subtitle.value = SubtitleState(user, chunks[i].subtitle, speaking = true) },
                        onFirstAudio = { tracker.firstAudio(); _latency.value = tracker.last },
                    ),
                )
            } else {
                delay(readTimeMs(line.subtitle))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(readTimeMs(line.subtitle)) // voice failed: the subtitle alone carries the reply
        }
        // Keep the last sentence on screen for a moment, then let it disappear (unless the user wants it kept).
        val shown = chunks.lastOrNull()?.subtitle ?: line.subtitle
        _subtitle.value = SubtitleState(user, shown, speaking = false)
        if (!s.alwaysShowSubtitle) {
            subtitleJob = scope.launch {
                delay(s.subtitleDurationMs.toLong())
                _subtitle.value = SubtitleState()
            }
        }
    }

    private suspend fun fail(line: Spoken, speak: Boolean = true) {
        _core.value = CoreState.ERROR
        _subtitle.value = SubtitleState(subtitle = line.subtitle)
        if (speak && settings().voiceFeedback) {
            try {
                speaker.speak(SpeechRequest(SubtitleSynchronizer.split(line.speech, line.subtitle), Emphasis.WARNING))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* subtitle already shown */ }
        } else delay(readTimeMs(line.subtitle))
        _subtitle.value = SubtitleState()
        delay(1_200)
        _core.value = CoreState.IDLE
    }

    private fun isStopPhrase(text: String) = text.replace(" ", "").trimEnd('.', '!', '?') in STOP_PHRASES

    private fun CommandResult.toSpoken() = Spoken(speech, subtitle)
    private fun readTimeMs(text: String) = (1_800L + text.length * 70L).coerceAtMost(8_000L)

    private companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm")
        const val CARD_MS = 12_000L
        val STOP_PHRASES = setOf("그만", "그만해", "그만해줘", "조용", "조용히", "조용히해", "스톱", "stop", "중지", "취소", "됐어")
    }
}
