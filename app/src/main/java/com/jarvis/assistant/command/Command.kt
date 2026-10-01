package com.jarvis.assistant.command

import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.util.JLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** One allow-listed capability. The AI can only trigger what is registered here. */
interface Command {
    /** Canonical type followed by any aliases the AI might use. */
    val types: List<String>

    /** Fast local commands run first and speak their own result instead of an acknowledgement. */
    val executesBeforeSpeech: Boolean get() = false

    suspend fun execute(action: AiAction): ActionResult
}

class CommandRouter(commands: List<Command>) {
    private val table: Map<String, Command> = buildMap {
        commands.forEach { command -> command.types.forEach { put(normalize(it), command) } }
    }

    val supportedTypes: Set<String> get() = table.keys

    fun resolve(action: AiAction): Command? = table[normalize(action.type)]

    private fun normalize(type: String) =
        type.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
}

/** Runs commands with a timeout and converts every failure into an [ActionResult]. */
class CommandExecutor(private val router: CommandRouter) {
    fun resolve(action: AiAction): Command? = router.resolve(action)

    suspend fun execute(action: AiAction): ActionResult {
        val command = router.resolve(action) ?: return ActionResult.unsupported()
        return try {
            withTimeout(TIMEOUT_MS) { command.execute(action) }
        } catch (e: TimeoutCancellationException) {
            ActionResult.fail("That took too long, sir.", "처리 시간이 너무 오래 걸렸습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            JLog.w("Command", "Permission denied for ${action.type}", e)
            ActionResult.fail("I don't have permission to do that.", "해당 작업을 수행할 권한이 없습니다.")
        } catch (e: Exception) {
            JLog.e("Command", "Command ${action.type} failed", e)
            ActionResult.fail("Something went wrong while executing that.", "명령을 실행하는 중 문제가 발생했습니다.")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
    }
}
