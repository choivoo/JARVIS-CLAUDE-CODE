package com.friday.assistant.core

import com.friday.assistant.command.Answer
import com.friday.assistant.command.Command
import com.friday.assistant.command.YesNoParser

/** Holds at most one command that is waiting for the user's "yes". Expires on its own and never confirms by itself. */
class ConfirmationManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 30_000,
) {
    sealed class Outcome {
        data class Confirmed(val command: Command) : Outcome()
        data object Declined : Outcome()
        /** The user said something else; the pending command is dropped and the text is a new request. */
        data object NewRequest : Outcome()
        data object Nothing : Outcome()
    }

    private var pending: Command? = null
    private var since = 0L

    val hasPending: Boolean get() = pending != null && clock() - since <= ttlMs

    fun hold(command: Command) { pending = command; since = clock() }
    fun clear() { pending = null }

    fun resolve(userText: String): Outcome {
        val cmd = pending ?: return Outcome.Nothing
        val fresh = clock() - since <= ttlMs
        pending = null
        if (!fresh) return Outcome.NewRequest
        return when (YesNoParser.parse(userText)) {
            Answer.YES -> Outcome.Confirmed(cmd)
            Answer.NO -> Outcome.Declined
            Answer.UNKNOWN -> Outcome.NewRequest
        }
    }
}
