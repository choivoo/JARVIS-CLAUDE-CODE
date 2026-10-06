package com.friday.assistant

import com.friday.assistant.ai.AiAction
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandRouter
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ResultStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    @Test fun routesAllowListedCommand() = runTest {
        val ex = RecordingExecutor { _, _ -> CommandResult.ok("Opening YouTube.", "유튜브를 실행합니다.") }
        val r = routerWith(CommandType.OPEN_YOUTUBE to ex).execute(AiAction("open_youtube"))
        assertTrue(r.ok)
        assertEquals(1, ex.calls.size)
    }

    @Test fun rejectsUnknownType() = runTest {
        val r = routerWith().execute(AiAction("DELETE_EVERYTHING", mapOf("path" to "/")))
        assertEquals(ResultStatus.UNSUPPORTED, r.status)
    }

    @Test fun rejectsKnownTypeWithoutExecutor() = runTest {
        assertEquals(ResultStatus.UNSUPPORTED, routerWith().execute(Command(CommandType.GET_TIME)).status)
    }

    @Test fun executorCrashBecomesFailure() = runTest {
        val r = routerWith(CommandType.GET_TIME to CommandExecutor { _, _ -> error("boom") }).execute(Command(CommandType.GET_TIME))
        assertEquals(ResultStatus.FAILED, r.status)
    }

    @Test fun securityExceptionBecomesPermissionRequest() = runTest {
        val r = routerWith(CommandType.FLASHLIGHT_ON to CommandExecutor { _, _ -> throw SecurityException("no") }).execute(Command(CommandType.FLASHLIGHT_ON))
        assertEquals(ResultStatus.NEEDS_PERMISSION, r.status)
    }

    @Test fun confirmationFlagIsPassedThrough() = runTest {
        val ex = RecordingExecutor { c, ok ->
            if (!ok) CommandResult.confirm("Would you like me to call mom?", "엄마에게 전화를 걸까요?", c) else CommandResult.ok("Calling.", "전화합니다.")
        }
        val router = routerWith(CommandType.CALL_CONTACT_REQUEST to ex)
        val first = router.execute(AiAction("CALL_CONTACT_REQUEST", mapOf("name" to "엄마")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, first.status)
        val second = router.execute(first.pending!!, confirmed = true)
        assertTrue(second.ok)
        assertEquals(listOf(false, true), ex.calls.map { it.second })
    }
}
