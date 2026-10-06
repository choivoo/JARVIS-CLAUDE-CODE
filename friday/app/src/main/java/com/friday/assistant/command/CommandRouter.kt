package com.friday.assistant.command

import com.friday.assistant.ai.AiAction
import kotlinx.coroutines.CancellationException

fun interface CommandExecutor {
    /** [confirmed] is true only after the user said yes to a NEEDS_CONFIRMATION prompt. */
    suspend fun execute(command: Command, confirmed: Boolean): CommandResult
}

/** Single entry point for every action. Unknown types are rejected, failures never escape. */
class CommandRouter(private val executors: Map<CommandType, CommandExecutor>) {

    fun toCommand(action: AiAction): Command? =
        CommandType.parse(action.type)?.let { Command(it, action.params) }

    suspend fun execute(action: AiAction): CommandResult =
        toCommand(action)?.let { execute(it) } ?: CommandResult.unsupported(action.type)

    suspend fun execute(command: Command, confirmed: Boolean = false): CommandResult {
        val executor = executors[command.type] ?: return CommandResult.unsupported(command.type.name)
        return try {
            executor.execute(command, confirmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            CommandResult.permission("I need a permission for that.", "이 작업에 필요한 권한이 없습니다.")
        } catch (e: Exception) {
            CommandResult.failed("Something went wrong while doing that.", "작업 중 오류가 발생했습니다.")
        }
    }
}
