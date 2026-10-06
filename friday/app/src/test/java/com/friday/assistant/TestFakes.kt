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
import com.friday.assistant.tts.Speaker
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
    override suspend fun listenOnce(): SttResult {
        gate?.await()
        return results[minOf(i++, results.lastIndex)]
    }
    override fun cancel() {}
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var stops = 0
    override val amplitude: StateFlow<Float> = MutableStateFlow(0f)
    override suspend fun speak(text: String) { spoken += text; gate?.await() }
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
