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
        val ex = RecordingConfirmable({ c -> CommandResult.confirm("Would you like me to call mom?", "엄마에게 전화를 걸까요?", c) }, { CommandResult.ok("Calling.", "전화합니다.") })
        val router = routerWith(CommandType.CALL_CONTACT_REQUEST to ex)
        val first = router.execute(AiAction("CALL_CONTACT_REQUEST", mapOf("name" to "엄마")))
        assertEquals(ResultStatus.NEEDS_CONFIRMATION, first.status)
        val second = router.execute(first.pending!!, confirmed = true)
        assertTrue(second.ok)
        assertEquals(listOf(false, true), ex.calls.map { it.second })
    }

    @Test fun riskyCommandCannotBeRegisteredWithAPlainExecutor() = runTest {
        val sneaky = RecordingExecutor { _, _ -> CommandResult.ok("Calling right away.", "바로 전화") }
        val r = routerWith(CommandType.CALL_CONTACT_REQUEST to sneaky).execute(Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to "x")))
        assertEquals(ResultStatus.UNSUPPORTED, r.status)
        assertTrue("nothing may run", sneaky.calls.isEmpty())
    }

    @Test fun permissionIsCheckedBeforeExecuting() = runTest {
        val ex = RecordingExecutor { _, _ -> CommandResult.ok("ok", "ok") }
        val access = FakeAccess()
        val router = CommandRouter(mapOf(CommandType.GET_TODAY_EVENTS to ex), access)
        assertEquals(ResultStatus.NEEDS_PERMISSION, router.execute(Command(CommandType.GET_TODAY_EVENTS)).status)
        assertTrue(ex.calls.isEmpty())
        access.permissions = setOf(android.Manifest.permission.READ_CALENDAR)
        assertTrue(router.execute(Command(CommandType.GET_TODAY_EVENTS)).ok)
    }

    @Test fun notificationAccessIsCheckedBeforeExecuting() = runTest {
        val ex = RecordingExecutor { _, _ -> CommandResult.ok("ok", "ok") }
        val access = FakeAccess()
        val router = CommandRouter(mapOf(CommandType.GET_NOTIFICATIONS to ex), access)
        val denied = router.execute(Command(CommandType.GET_NOTIFICATIONS))
        assertEquals(ResultStatus.NEEDS_PERMISSION, denied.status)
        assertTrue(denied.subtitle.contains("알림 접근"))
        access.notificationAccess = true
        assertTrue(router.execute(Command(CommandType.GET_NOTIFICATIONS)).ok)
    }

    @Test fun slowExecutorTimesOut() = runTest {
        val slow = CommandExecutor { _, _ -> kotlinx.coroutines.delay(60_000); CommandResult.ok("late", "늦음") }
        val r = routerWith(CommandType.GET_TIME to slow).execute(Command(CommandType.GET_TIME))
        assertEquals(ResultStatus.FAILED, r.status)
        assertTrue(r.speech.contains("too long"))
    }

    @Test fun everyCommandHasCategoryRiskAndTimeout() {
        CommandType.entries.forEach {
            assertTrue(it.name, it.timeoutMs in 1_000..60_000)
            assertEquals(it.name, it.risk >= 2, it.needsConfirmation)
        }
        assertEquals(com.friday.assistant.command.CommandCategory.NOTIFICATION, CommandType.READ_LATEST_NOTIFICATION.category)
        assertEquals(com.friday.assistant.command.Risk.EXTERNAL, CommandType.MESSAGE_CONTACT_REQUEST.risk)
        assertEquals(com.friday.assistant.command.Risk.WRITE, CommandType.CREATE_EVENT_REQUEST.risk)
        assertEquals(com.friday.assistant.command.Risk.READ, CommandType.WEATHER.risk)
    }
}
