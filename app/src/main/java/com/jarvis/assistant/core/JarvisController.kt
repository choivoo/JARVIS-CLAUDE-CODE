package com.jarvis.assistant.core

import android.content.Context
import com.jarvis.assistant.ai.AIProvider
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.ai.AiConfig
import com.jarvis.assistant.ai.AiError
import com.jarvis.assistant.ai.AiException
import com.jarvis.assistant.ai.AiReply
import com.jarvis.assistant.ai.AiReplyParser
import com.jarvis.assistant.ai.ChatTurn
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.CommandExecutor
import com.jarvis.assistant.command.LocalIntentParser
import com.jarvis.assistant.data.database.MessageEntity
import com.jarvis.assistant.data.database.Role
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.speech.ListenCallbacks
import com.jarvis.assistant.speech.ListenRequest
import com.jarvis.assistant.speech.SpeechRecognizerEngine
import com.jarvis.assistant.speech.SttError
import com.jarvis.assistant.speech.SttResult
import com.jarvis.assistant.speech.WakeDetection
import com.jarvis.assistant.speech.WakeError
import com.jarvis.assistant.speech.WakeWordEngine
import com.jarvis.assistant.speech.WakeWordException
import com.jarvis.assistant.tts.SpeechOutput
import com.jarvis.assistant.tts.TtsException
import com.jarvis.assistant.audio.UiSounds
import com.jarvis.assistant.routine.RoutineBook
import com.jarvis.assistant.util.AudioLevelBus
import com.jarvis.assistant.util.Haptics
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * The assistant's state machine: wake word -> listening -> STT -> AI -> command -> TTS + subtitle.
 * One coroutine ("runner") owns the pipeline at any time; starting a new one cancels the old one,
 * which also releases the microphone and silences the speaker.
 */
class JarvisController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val conversations: ConversationRepository,
    private val aiProviders: Map<AiProviderType, AIProvider>,
    private val recognizer: SpeechRecognizerEngine,
    private val wakeEngine: WakeWordEngine,
    private val speaker: SpeechOutput,
    private val executor: CommandExecutor,
    network: NetworkMonitor,
    private val levels: AudioLevelBus,
    private val routines: RoutineBook? = null,
    private val sounds: UiSounds? = null,
    private val screenWaker: com.jarvis.assistant.power.ScreenWaker? = null,
    /** Describes what the user is pointing at on screen; appended to the AI's view of the request. */
    private val contextProvider: (() -> String?)? = null,
) {
    private val _hud = MutableStateFlow(HudState(online = network.checkNow()))
    val hud: StateFlow<HudState> = _hud

    private var runner: Job? = null
    private var subtitleJob: Job? = null
    private var noticeJob: Job? = null

    @Volatile
    private var standby = false

    private var lastSpoken: Pair<String, String?>? = null

    init {
        scope.launch { network.online.collect { online -> _hud.update { it.copy(online = online) } } }
    }

    // ------------------------------------------------------------------ public API

    /** Starts wake-word standby. Returns false (and shows why) if the microphone is not permitted. */
    fun startStandby(): Boolean {
        if (!Perms.hasMic(context)) {
            showError("마이크 권한이 필요합니다. 권한을 허용한 뒤 다시 시도해 주세요.")
            return false
        }
        if (standby && runner?.isActive == true) return true
        standby = true
        _hud.update { it.copy(standby = true) }
        schedule(null)
        return true
    }

    fun stopStandby() {
        standby = false
        runner?.cancel()
        runner = null
        levels.set(0f)
        _hud.update { it.copy(standby = false, phase = AssistantPhase.IDLE, partial = "") }
    }

    /** Mic button: interrupts whatever is going on, or starts a single listen when idle. */
    fun listenNow() {
        if (settingsRepo.settings.value.wakeScreen) screenWaker?.wakeAndShow()
        when (_hud.value.phase) {
            AssistantPhase.IDLE, AssistantPhase.ERROR -> schedule { interaction(null) }
            else -> schedule(null) // interrupt, then fall back to standby if it was active
        }
    }

    /** Keyboard input path (same pipeline as speech). */
    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        schedule { interaction(clean.take(MAX_UTTERANCE)) }
    }

    /** Stops speaking / listening / thinking and returns to standby (or idle). */
    fun interrupt() = schedule(null)

    /**
     * Runs one command directly (air gestures, quick-action chips). Silent actions only flash a
     * notice so they never cut JARVIS off; spoken ones go through the normal pipeline.
     */
    fun quickAction(action: AiAction, speak: Boolean) {
        if (speak) {
            schedule {
                _hud.update { it.copy(phase = AssistantPhase.EXECUTING) }
                val result = executor.execute(action)
                val line = result.speech
                if (line != null) say(line, result.subtitle, null)
            }
        } else {
            scope.launch {
                val result = executor.execute(action)
                (result.subtitle ?: result.speech)?.let { showNotice(it) }
            }
        }
    }

    /** Speaks something unprompted (fired reminders, routines). Interrupts whatever is going on. */
    fun announce(speech: String, subtitle: String?) {
        schedule { say(speech, subtitle, null) }
    }

    fun reportError(message: String) = showError(message)

    fun dismissNotice() {
        _hud.update { it.copy(notice = null, phase = if (it.phase == AssistantPhase.ERROR) AssistantPhase.IDLE else it.phase) }
    }

    // ------------------------------------------------------------------ runner

    private fun schedule(first: (suspend () -> Unit)?) {
        val previous = runner
        runner = scope.launch {
            previous?.cancelAndJoin()
            _hud.update { it.copy(phase = AssistantPhase.IDLE, partial = "") }
            levels.set(0f)
            guarded { first?.invoke() }
            while (isActive && standby) {
                guarded { standbyLoop() }
                if (standby) delay(2_000)
            }
            if (isActive) {
                _hud.update { if (it.phase == AssistantPhase.ERROR) it else it.copy(phase = AssistantPhase.IDLE, partial = "") }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JLog.e("Controller", "Unexpected pipeline failure", e)
            showError("예기치 않은 오류가 발생했습니다. 다시 시도해 주세요.")
        }
    }

    // ------------------------------------------------------------------ wake word

    private suspend fun standbyLoop() {
        var transientFailures = 0
        while (standby) {
            val settings = settingsRepo.current()
            if (!settings.wakeWordEnabled) {
                delay(1_500)
                continue
            }
            val detection: WakeDetection = try {
                wakeEngine.awaitWake(settings.inputLanguage, settings.wakeSensitivity, settings.wakeMode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WakeWordException) {
                when (e.kind) {
                    WakeError.PERMISSION -> {
                        standbyFailed("마이크 권한이 없어 대기 모드를 종료했습니다. 권한을 허용해 주세요.")
                        return
                    }
                    WakeError.UNAVAILABLE -> {
                        standbyFailed("이 기기에서 음성 인식 서비스를 사용할 수 없습니다. Google 앱을 확인해 주세요.")
                        return
                    }
                    WakeError.TRANSIENT -> {
                        transientFailures++
                        if (transientFailures >= 3) {
                            standbyFailed("음성 인식이 계속 실패하여 대기 모드를 종료했습니다.")
                            return
                        }
                        delay(5_000)
                        continue
                    }
                }
            }
            transientFailures = 0
            JLog.d("Controller", "Wake word detected")
            if (settings.wakeHaptic) Haptics.tick(context)
            sounds?.play(UiSounds.Kind.WAKE)
            if (settings.wakeScreen && screenWaker?.wakeAndShow() == true) delay(700) // let the screen and HUD come up first
            val inline = detection.trailingText
            if (inline != null) {
                interaction(inline.take(MAX_UTTERANCE))
            } else {
                val addr = if (settings.userTitle.equals("Sir", ignoreCase = true)) "sir" else settings.userTitle
                val (speech, subtitle) = acknowledgements(addr).random()
                say(speech, subtitle, null, persistMessage = false)
                delay(150)
                interaction(null)
            }
        }
    }

    private fun standbyFailed(message: String) {
        standby = false
        _hud.update { it.copy(standby = false) }
        showError(message)
    }

    // ------------------------------------------------------------------ one conversation turn

    private suspend fun interaction(initial: String?) {
        var pending = initial
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            val utterance = pending ?: listen(silentOnEmpty = rounds > 0) ?: return
            pending = null
            respondTo(utterance)
            rounds++
            if (!settingsRepo.current().autoListen) return
        }
    }

    private suspend fun listen(silentOnEmpty: Boolean): String? {
        if (!Perms.hasMic(context)) {
            showError("마이크 권한이 필요합니다. 권한을 허용해 주세요.")
            return null
        }
        val settings = settingsRepo.current()
        _hud.update { it.copy(phase = AssistantPhase.LISTENING, partial = "", notice = null) }
        val result = recognizer.listen(
            ListenRequest(languageTag = settings.inputLanguage, maxDurationMs = 20_000, silenceMs = 1_500),
            ListenCallbacks(
                onPartial = { p -> _hud.update { it.copy(partial = p) } },
                onLevel = { levels.set(it) },
            ),
        )
        levels.set(0f)
        return when (result) {
            is SttResult.Text -> {
                val text = result.text.trim()
                if (text.length > MAX_UTTERANCE) {
                    showNotice("입력이 길어 앞부분만 처리합니다.")
                    text.take(MAX_UTTERANCE)
                } else {
                    text
                }
            }
            SttResult.NoSpeech -> {
                _hud.update { it.copy(phase = AssistantPhase.IDLE, partial = "") }
                if (!silentOnEmpty) showNotice("음성이 들리지 않았습니다. 다시 말씀해 주세요.")
                null
            }
            is SttResult.Failure -> {
                showError(
                    when (result.error) {
                        SttError.PERMISSION -> "마이크 권한이 필요합니다. 권한을 허용해 주세요."
                        SttError.NETWORK -> "음성 인식 서비스에 연결할 수 없습니다. 인터넷 연결을 확인해 주세요."
                        SttError.BUSY -> "음성 인식 서비스가 사용 중입니다. 잠시 후 다시 시도해 주세요."
                        SttError.UNAVAILABLE -> "이 기기에서 음성 인식을 사용할 수 없습니다. Google 앱을 확인해 주세요."
                        SttError.TIMEOUT -> "음성 인식 시간이 초과되었습니다."
                        SttError.OTHER -> "음성을 인식하지 못했습니다. 다시 시도해 주세요."
                    },
                )
                null
            }
        }
    }

    private suspend fun respondTo(userText: String) {
        _hud.update { it.copy(phase = AssistantPhase.THINKING, partial = userText, notice = null) }
        persist { conversations.addMessage(Role.USER, userText) }
        val settings = settingsRepo.current()
        val reply = obtainReply(userText, settings)
        _hud.update { it.copy(partial = "") }
        deliver(reply, userText, settings)
    }

    // ------------------------------------------------------------------ AI

    private suspend fun obtainReply(userText: String, settings: AppSettings): AiReply {
        val local = LocalIntentParser.parse(userText)
        if (settings.localShortcuts) {
            routines?.matchSpoken(userText)?.let { name ->
                return AiReply("Running your routine.", "루틴을 실행합니다.", AiAction("RUN_ROUTINE", mapOf("name" to name)))
            }
        }
        if (settings.localShortcuts && local != null) {
            val action = local.action
            // Only unambiguous device commands that are actually installed skip the AI round trip.
            if (action != null && action.type.uppercase() in LOCAL_FIRST && executor.resolve(action) != null) return local
            if (action != null && action.type.uppercase() in LOCAL_FIRST_SPECIAL) return local
        }
        return try {
            val turns = buildTurns(userText, settings, toolFollowUp = null)
            val raw = completeWithFailover(settings, turns)
            val parsed = AiReplyParser.parse(raw)
            if (!parsed.wellFormed && local != null) local else parsed
        } catch (e: TimeoutCancellationException) {
            local ?: AiReply("The AI core is taking too long to respond.", "AI 응답 시간이 초과되었습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            JLog.w("Controller", "AI failure: ${e.kind}")
            local ?: when (e.kind) {
                AiError.NOT_CONFIGURED -> AiReply(
                    "My AI core isn't configured yet, sir. Please add an API key in settings.",
                    "AI 코어가 아직 설정되지 않았습니다. 설정에서 API 키를 입력해 주세요.",
                )
                AiError.NETWORK -> AiReply("Connection unavailable.", "인터넷 연결을 사용할 수 없습니다.")
                AiError.TIMEOUT -> AiReply("The AI core is taking too long to respond.", "AI 응답 시간이 초과되었습니다.")
                AiError.AUTH -> AiReply(
                    "My AI credentials were rejected. Please check the API key.",
                    "API 키가 거부되었습니다. 키를 확인해 주세요.",
                )
                AiError.RATE_LIMIT -> AiReply(
                    "I'm being rate limited. Please try again shortly.",
                    "요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.",
                )
                AiError.SERVER, AiError.BAD_RESPONSE -> AiReply(
                    "My AI core returned an error.",
                    "AI 서버에서 오류가 발생했습니다.",
                )
            }
        }
    }

    /** Providers that were rate limited recently, so they are skipped for a short while. */
    private val cooldownUntil = mutableMapOf<AiProviderType, Long>()

    /**
     * Tries the selected provider first, then every other provider that has a key (free tiers run
     * out quickly, so several free keys together last much longer). Only limit / outage style
     * failures move on to the next provider; being offline or a bad request does not.
     */
    private suspend fun completeWithFailover(settings: AppSettings, turns: List<ChatTurn>): String {
        val primary = settingsRepo.aiConfig(settings.aiProvider)
        val chain = mutableListOf(primary)
        if (settings.aiFailover) {
            for (type in FAILOVER_ORDER) {
                if (type == primary.provider) continue
                val cfg = settingsRepo.aiConfig(type)
                if (!cfg.apiKey.isNullOrBlank()) chain += cfg
            }
        }
        val now = System.currentTimeMillis()
        var last: AiException? = null
        for ((index, cfg) in chain.withIndex()) {
            val resting = (cooldownUntil[cfg.provider] ?: 0L) > now
            // The chosen provider is always tried first; resting backups are skipped.
            if (resting && index > 0 && last != null) continue
            try {
                val provider = aiProviders[cfg.provider] ?: continue
                val raw = try {
                    withTimeout(AI_ATTEMPT_MS) { provider.complete(cfg, turns) }
                } catch (e: TimeoutCancellationException) {
                    throw AiException(AiError.TIMEOUT, "The AI service timed out")
                }
                if (index > 0) showNotice("${cfg.provider.label} 로 자동 전환했습니다.")
                return raw
            } catch (e: AiException) {
                last = e
                JLog.w("Controller", "AI ${cfg.provider} failed: ${e.kind}")
                if (e.kind == AiError.RATE_LIMIT) cooldownUntil[cfg.provider] = System.currentTimeMillis() + RATE_LIMIT_REST_MS
                if (e.kind !in FAILOVER_KINDS) throw e
            }
        }
        throw last ?: AiException(AiError.NOT_CONFIGURED, "No AI provider available")
    }

    /** Asks the AI to phrase a command result; null when it is unreachable (caller uses the local text). */
    private suspend fun followUp(action: AiAction, data: String, userText: String, settings: AppSettings): AiReply? {
        return try {
            val turns = buildTurns(userText, settings, toolFollowUp = PromptBuilder.toolResultTurn(action.type, data))
            val raw = completeWithFailover(settings, turns)
            AiReplyParser.parse(raw).takeIf { it.wellFormed }?.copy(action = null)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) null else throw e
        } catch (e: Exception) {
            JLog.w("Controller", "Follow-up failed", e)
            null
        }
    }

    private suspend fun buildTurns(userText: String, settings: AppSettings, toolFollowUp: String?): List<ChatTurn> {
        val turns = mutableListOf(
            ChatTurn(ChatTurn.SYSTEM, PromptBuilder.system(ZonedDateTime.now(), settings.weatherCity, settings.userTitle, userText)),
        )
        val history = try {
            conversations.recentContext(HISTORY_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        history.forEach { turns += it.toTurn() }
        // Conversation must start with the user and end with the current request.
        while (turns.size > 1 && turns[1].role == ChatTurn.ASSISTANT) turns.removeAt(1)
        val last = turns.last()
        if (last.role != ChatTurn.USER || last.content != userText) turns += ChatTurn(ChatTurn.USER, userText)
        if (toolFollowUp == null) {
            // "이거 / 여기" refer to the window under the cursor; tell the AI which one (not stored in history).
            contextProvider?.invoke()?.let { ctx ->
                val i = turns.lastIndex
                turns[i] = ChatTurn(ChatTurn.USER, turns[i].content + "\n\n[SCREEN CONTEXT: $ctx]")
            }
        }
        if (toolFollowUp != null) turns += ChatTurn(ChatTurn.USER, toolFollowUp)
        return turns
    }

    private fun MessageEntity.toTurn(): ChatTurn =
        if (role == Role.USER) {
            ChatTurn(ChatTurn.USER, text.take(300))
        } else {
            val json = JSONObject().put("speech", text.take(200)).put("subtitle", subtitle.orEmpty().take(120))
            json.put(
                "action",
                actionJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL,
            )
            ChatTurn(ChatTurn.ASSISTANT, json.toString())
        }

    // ------------------------------------------------------------------ delivery

    private suspend fun deliver(reply: AiReply, userText: String, settings: AppSettings) {
        val action = reply.action
        if (action == null) {
            say(reply.speech, reply.subtitle, null)
            return
        }
        when (action.type.trim().uppercase()) {
            "RUN_ROUTINE", "ROUTINE" -> {
                runRoutine(action.param("name") ?: action.param("routine") ?: "")
                return
            }
            "BREATHE", "BREATHING", "MEDITATE" -> {
                say(reply.speech, reply.subtitle, null)
                breathe()
                return
            }
            "REPEAT", "REPEAT_LAST", "SAY_AGAIN" -> {
                val last = lastSpoken
                if (last != null) say(last.first, last.second, null)
                else say("I haven't said anything yet.", "아직 말씀드린 내용이 없습니다.", null)
                return
            }
        }
        val command = executor.resolve(action)
        if (command == null) {
            JLog.w("Controller", "Unsupported action type requested: ${action.type.take(40)}")
            val unsupported = ActionResult.unsupported()
            say(unsupported.speech.orEmpty(), unsupported.subtitle, null)
            return
        }
        if (command.executesBeforeSpeech) {
            _hud.update { it.copy(phase = AssistantPhase.EXECUTING) }
            val result = executor.execute(action)
            say(result.speech ?: reply.speech, result.subtitle ?: reply.subtitle, action)
            return
        }
        coroutineScope {
            _hud.update { it.copy(phase = AssistantPhase.EXECUTING) }
            val execution = async { executor.execute(action) }
            say(reply.speech, reply.subtitle, action)
            if (!execution.isCompleted) _hud.update { it.copy(phase = AssistantPhase.EXECUTING) }
            val result = execution.await()
            finish(result, action, userText, settings)
        }
    }

    private suspend fun finish(result: ActionResult, action: AiAction, userText: String, settings: AppSettings) {
        if (result.narrate && result.data != null) {
            _hud.update { it.copy(phase = AssistantPhase.THINKING) }
            val narrated = followUp(action, result.data, userText, settings)
            if (narrated != null) {
                say(narrated.speech, narrated.subtitle, null)
            } else if (result.speech != null) {
                say(result.speech, result.subtitle, null)
            }
        } else if (result.speech != null) {
            say(result.speech, result.subtitle, null)
        }
    }

    /** Shows the Korean subtitle and speaks the English line; returns when speech has finished. */
    private suspend fun say(speech: String, subtitle: String?, action: AiAction?, persistMessage: Boolean = true) {
        if (speech.isBlank() && subtitle.isNullOrBlank()) return
        val settings = settingsRepo.current()
        val sub = subtitle?.takeIf { it.isNotBlank() }
        if (persistMessage) lastSpoken = speech to sub
        if (persistMessage) {
            persist { conversations.addMessage(Role.JARVIS, speech, sub, action?.toJson()?.toString()) }
        }
        subtitleJob?.cancel()
        _hud.update {
            it.copy(phase = AssistantPhase.SPEAKING, subtitle = if (settings.subtitlesEnabled) (sub ?: speech) else null)
        }
        if (settings.voiceFeedback) {
            try {
                speaker.speak(speech)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtsException) {
                JLog.w("Controller", "Speech output failed: ${e.kind}")
                showNotice("음성 출력에 실패했습니다. 자막으로 표시합니다.")
                delay(readingTimeMs(sub ?: speech))
            }
        } else {
            delay(readingTimeMs(sub ?: speech))
        }
        subtitleJob = scope.launch {
            delay(SUBTITLE_LINGER_MS)
            _hud.update { it.copy(subtitle = null) }
        }
    }

    private suspend fun runRoutine(name: String) {
        val book = routines
        val routine = book?.find(name)
        if (routine == null) {
            say("I don't have a routine by that name.", "해당 루틴을 찾을 수 없습니다.", null)
            return
        }
        routine.intro?.let { say(it.first, it.second, null) }
        for (step in routine.steps) {
            val action = LocalIntentParser.parse(step)?.action ?: continue
            _hud.update { it.copy(phase = AssistantPhase.EXECUTING) }
            val result = executor.execute(action)
            result.speech?.let { say(it, result.subtitle, null) }
        }
        routine.outro?.let { say(it.first, it.second, null) }
    }

    /** Guided 4-4-6 breathing: spoken cues with real pauses between them. */
    private suspend fun breathe() {
        repeat(3) { round ->
            say(if (round == 0) "Breathe in." else "In.", "숨을 들이쉬세요.", null)
            delay(3_500)
            say("Hold.", "멈추세요.", null)
            delay(3_000)
            say("And out, slowly.", "천천히 내쉬세요.", null)
            delay(5_000)
        }
        say("Well done. Your heart rate should be settling.", "수고하셨습니다. 마음이 한결 편안해지셨을 겁니다.", null)
    }

    private fun readingTimeMs(text: String) = (1_200L + text.length * 70L).coerceAtMost(7_000L)

    // ------------------------------------------------------------------ notices

    private suspend fun persist(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JLog.e("Controller", "Could not save conversation", e)
        }
    }

    private fun showNotice(message: String) {
        noticeJob?.cancel()
        _hud.update { it.copy(notice = message) }
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _hud.update { if (it.notice == message) it.copy(notice = null) else it }
        }
    }

    private fun showError(message: String) {
        sounds?.play(UiSounds.Kind.ERROR)
        noticeJob?.cancel()
        levels.set(0f)
        _hud.update { it.copy(phase = AssistantPhase.ERROR, notice = message, partial = "") }
        noticeJob = scope.launch {
            delay(ERROR_MS)
            _hud.update {
                if (it.phase == AssistantPhase.ERROR && it.notice == message) {
                    it.copy(phase = AssistantPhase.IDLE, notice = null)
                } else {
                    it
                }
            }
        }
    }

    private companion object {
        const val AI_ATTEMPT_MS = 25_000L
        const val RATE_LIMIT_REST_MS = 90_000L

        /** Order in which backup providers are tried (largest free allowance first). */
        val FAILOVER_ORDER = listOf(
            AiProviderType.GEMINI, AiProviderType.GROQ, AiProviderType.CEREBRAS,
            AiProviderType.OPENROUTER, AiProviderType.OPENAI_COMPATIBLE,
        )
        val FAILOVER_KINDS = setOf(
            AiError.RATE_LIMIT, AiError.SERVER, AiError.TIMEOUT, AiError.AUTH, AiError.BAD_RESPONSE, AiError.NOT_CONFIGURED,
        )

        /** Deterministic commands answered instantly without the AI (setting: Instant shortcuts). */
        val LOCAL_FIRST = setOf(
            "GET_TIME", "GET_DATE", "GET_BATTERY", "BATTERY_DETAIL", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "FLASHLIGHT_SOS",
            "VOLUME_UP", "VOLUME_DOWN", "SET_VOLUME", "MUTE", "UNMUTE", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN", "BRIGHTNESS_SET",
            "RINGER_NORMAL", "RINGER_VIBRATE", "RINGER_SILENT", "MUSIC_PLAY", "MUSIC_PAUSE", "MUSIC_NEXT", "MUSIC_PREVIOUS",
            "MUSIC_STOP", "MUSIC_FORWARD", "MUSIC_REWIND", "SET_TIMER", "SET_ALARM", "STOPWATCH_START", "STOPWATCH_STOP",
            "STOPWATCH_LAP", "STOPWATCH_RESET", "STOPWATCH_STATUS", "ROLL_DICE", "FLIP_COIN", "TELL_JOKE", "QUOTE", "FUN_FACT",
            "CALCULATE", "STORAGE_INFO", "MEMORY_INFO", "DEVICE_INFO", "NETWORK_INFO", "UPTIME", "STATUS_REPORT", "HELP",
            "AMBIENT_LIGHT", "COMPASS", "STEP_COUNT", "ALTITUDE", "FIND_PHONE", "STOP_FIND_PHONE", "REMINDER_LIST",
            "REMINDER_CANCEL", "SHOW_ALARMS", "ROUTINE_LIST", "DAILY_BRIEFING", "COUNTER_ADD", "COUNTER_GET",
            "HOLOGRAM_OPEN", "HOLOGRAM_CLOSE", "WINDOW_OPEN", "WINDOW_CLOSE", "WINDOW_CLOSE_ALL", "WINDOW_ARRANGE",
            "WINDOW_MAXIMIZE", "WINDOW_RESTORE", "WINDOW_MOVE_HERE", "WINDOW_REFRESH",
        )

        /** Handled inside the controller itself, so no installed Command is required. */
        val LOCAL_FIRST_SPECIAL = setOf("RUN_ROUTINE", "REPEAT", "BREATHE")
        const val MAX_UTTERANCE = 500
        const val MAX_ROUNDS = 6
        const val HISTORY_LIMIT = 6
        const val SUBTITLE_LINGER_MS = 3_000L
        const val NOTICE_MS = 4_500L
        const val ERROR_MS = 6_000L

        fun acknowledgements(addr: String) = listOf(
            "Yes, $addr?" to "네, 말씀하세요.",
            "At your service." to "말씀하세요.",
            "I'm listening." to "듣고 있습니다.",
            "How may I help?" to "무엇을 도와드릴까요?",
        )
    }
}
