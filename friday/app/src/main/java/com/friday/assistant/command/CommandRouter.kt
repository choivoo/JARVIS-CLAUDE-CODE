package com.friday.assistant.command

import com.friday.assistant.ai.AiAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

fun interface CommandExecutor {
    /** [confirmed] is true only after the user said yes to a NEEDS_CONFIRMATION prompt. */
    suspend fun execute(command: Command, confirmed: Boolean): CommandResult
}

/**
 * Executor for commands that change data or talk to other people (risk >= WRITE).
 * It is split in two so that nothing can happen before the user confirmed: [prepare] only builds the question.
 */
abstract class ConfirmableExecutor : CommandExecutor {
    abstract suspend fun prepare(command: Command): CommandResult
    abstract suspend fun perform(command: Command): CommandResult
    final override suspend fun execute(command: Command, confirmed: Boolean) =
        if (confirmed) perform(command) else prepare(command)
}

/** What the router needs to know about permissions; implemented over the real Android state. */
interface AccessChecker {
    fun hasPermission(permission: String): Boolean
    fun hasNotificationAccess(): Boolean

    companion object { val AllowAll = object : AccessChecker {
        override fun hasPermission(permission: String) = true
        override fun hasNotificationAccess() = true
    } }
}

/** Single entry point for every action: allow-list, permission pre-check, confirmation rule, timeout, crash guard. */
class CommandRouter(
    private val executors: Map<CommandType, CommandExecutor>,
    private val access: AccessChecker = AccessChecker.AllowAll,
) {
    fun toCommand(action: AiAction): Command? =
        CommandType.parse(action.type)?.let { Command(it, action.params) }

    suspend fun execute(action: AiAction): CommandResult =
        toCommand(action)?.let { execute(it) } ?: CommandResult.unsupported(action.type)

    suspend fun execute(command: Command, confirmed: Boolean = false): CommandResult {
        val type = command.type
        val executor = executors[type] ?: return CommandResult.unsupported(type.name)
        // A risky command may only be registered with an executor that cannot act before confirmation.
        if (type.needsConfirmation && executor !is ConfirmableExecutor) return CommandResult.unsupported(type.name)
        type.permission?.let {
            if (!access.hasPermission(it)) return CommandResult.permission(
                "I need ${permissionWord(it)} access for that. You can allow it in the Permission Center.",
                "${permissionWordKo(it)} 권한이 필요합니다. 권한 센터에서 허용해 주세요.",
            )
        }
        if (type.needsNotificationAccess && !access.hasNotificationAccess()) return CommandResult.permission(
            "I need notification access for that. You can turn it on in the Permission Center.",
            "알림 접근 권한이 필요합니다. 권한 센터에서 허용해 주세요.",
        )
        return try {
            withTimeout(type.timeoutMs) { executor.execute(command, confirmed) }
        } catch (e: TimeoutCancellationException) {
            CommandResult.failed("That took too long, so I stopped.", "시간이 너무 오래 걸려 중단했습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            CommandResult.permission("I need a permission for that.", "이 작업에 필요한 권한이 없습니다.")
        } catch (e: Exception) {
            CommandResult.failed("Something went wrong while doing that.", "작업 중 오류가 발생했습니다.")
        }
    }

    private fun permissionWord(p: String) = if (p.endsWith("CALENDAR")) "calendar" else p.substringAfterLast('.').lowercase()
    private fun permissionWordKo(p: String) = if (p.endsWith("CALENDAR")) "캘린더" else p.substringAfterLast('.')
}
