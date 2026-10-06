package com.friday.assistant

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.ai.AiException
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.GeminiProvider
import com.friday.assistant.ai.Role
import com.friday.assistant.command.LanguageIntent
import com.friday.assistant.core.Errors
import com.friday.assistant.overlay.SubtitleOverlay
import com.friday.assistant.settings.FridaySettings
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayAndLanguageTest {
    // ---- language commands ----------------------------------------------------------------------------------

    @Test fun languageIntents() {
        fun p(s: String) = LanguageIntent.parse(s)
        listOf("한국어로 말해줘", "한국어로 대답해", "앞으로 한국어로 말해줘", "이제부터 한국어로 해줘", "한국어로 말해 주세요").forEach {
            assertEquals(it, LanguageIntent.SwitchToKorean, p(it))
        }
        listOf("영어로 말해줘", "다시 영어로 대답해", "영어로 해줘").forEach { assertEquals(it, LanguageIntent.SwitchToEnglish, p(it)) }
        assertEquals(LanguageIntent.KoreanOnce("오늘 날씨 알려줘"), p("오늘 날씨 한국어로 알려줘"))
        assertEquals(LanguageIntent.KoreanOnce("오늘 날씨 알려줘"), p("한국어로 오늘 날씨 알려줘"))
        assertNull(p("오늘 날씨 알려줘"))
        assertNull(p("영어로 번역해줘 안녕하세요").takeIf { false })
        assertNull(p(""))
    }

    // ---- error messages -------------------------------------------------------------------------------------

    @Test fun serverErrorsShowTheCode() {
        assertTrue(Errors.ai(AiException.Server(503)).subtitle.contains("HTTP 503"))
        assertTrue(Errors.ai(AiException.Server(404)).subtitle.contains("AI Model"))
        assertTrue(Errors.ai(AiException.Server(400, "API key not valid")).subtitle.contains("API key not valid"))
        assertTrue(AiException.Server(500, "boom").message!!.contains("boom"))
    }

    // ---- Gemini robustness ----------------------------------------------------------------------------------

    private val msgs = listOf(ChatMessage(Role.SYSTEM, "s"), ChatMessage(Role.USER, "hi"))
    private val ok = """{"candidates":[{"content":{"parts":[{"text":"{\"speech\":\"ok\"}"}]}}]}"""

    @Test fun flashModelsDoNotWasteTokensOnThinking() = runTest {
        var body = ""
        GeminiProvider("https://g.test", "gemini-2.5-flash", "k") { _, b, _ -> body = b; ok }.complete(msgs)
        val cfg = JSONObject(body).getJSONObject("generationConfig")
        assertEquals(0, cfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertEquals(1024, cfg.getInt("maxOutputTokens"))
        GeminiProvider("https://g.test", "gemini-2.5-pro", "k") { _, b, _ -> body = b; ok }.complete(msgs)
        assertFalse("pro cannot disable thinking", JSONObject(body).getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun overloadedModelFallsBackToTheLighterOneOnce() = runTest {
        val urls = mutableListOf<String>()
        val p = GeminiProvider("https://g.test", "gemini-2.5-flash", "k") { u, _, _ -> urls += u; if (urls.size == 1) throw AiException.Server(503, "overloaded") else ok }
        assertEquals("{\"speech\":\"ok\"}", p.complete(msgs))
        assertTrue(urls[1].endsWith("models/gemini-2.5-flash-lite:generateContent"))
        // a second failure is reported, not looped on
        val failing = GeminiProvider("https://g.test", "gemini-2.5-flash", "k") { _, _, _ -> throw AiException.Server(503) }
        try { failing.complete(msgs); throw AssertionError("expected failure") } catch (e: AiException.Server) { assertEquals(503, e.code) }
        // key problems never trigger the fallback
        var calls = 0
        try { GeminiProvider("e", "gemini-2.5-flash", "k") { _, _, _ -> calls++; throw AiException.InvalidKey() }.complete(msgs) } catch (e: AiException.InvalidKey) { }
        assertEquals(1, calls)
    }

    // ---- subtitle overlay -----------------------------------------------------------------------------------

    @Test fun overlayDecisionTable() {
        assertTrue(SubtitleOverlay.shouldShow(true, true, false, "안녕"))
        assertFalse("FRIDAY is on screen: the HUD shows it", SubtitleOverlay.shouldShow(true, true, true, "안녕"))
        assertFalse("no permission", SubtitleOverlay.shouldShow(true, false, false, "안녕"))
        assertFalse("switched off", SubtitleOverlay.shouldShow(false, true, false, "안녕"))
        assertFalse("nothing to show", SubtitleOverlay.shouldShow(true, true, false, " "))
    }

    /** Records the windows the overlay adds and removes. */
    class WindowLog { val views = mutableListOf<android.view.View>(); val params = mutableMapOf<android.view.View, android.view.ViewGroup.LayoutParams>() }

    private fun fakeWm(log: WindowLog): WindowManager = java.lang.reflect.Proxy.newProxyInstance(
        WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java),
    ) { _, m, a ->
        when (m.name) {
            "addView" -> { log.views += a[0] as android.view.View; log.params[a[0] as android.view.View] = a[1] as android.view.ViewGroup.LayoutParams }
            "removeView" -> log.views -= a[0] as android.view.View
        }
        null
    } as WindowManager

    private fun overlay(enabled: Boolean = true, canDraw: Boolean = true, fg: () -> Boolean = { false }, log: WindowLog = WindowLog()): Pair<SubtitleOverlay, WindowLog> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return SubtitleOverlay(ctx, { FridaySettings(overlaySubtitles = enabled) }, fg, { canDraw }, fakeWm(log)) to log
    }

    @Test fun overlayAddsAnApplicationOverlayWindowAndRemovesIt() {
        val (o, wm) = overlay()
        o.update("현재 배터리는 72%입니다.")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(o.isShowing)
        assertEquals(1, wm.views.size)
        val v = wm.views.single() as android.widget.TextView
        assertEquals("현재 배터리는 72%입니다.", v.text.toString())
        val lp = wm.params.getValue(v) as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, lp.type)
        assertTrue("must let touches through", lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)

        o.update("다음 문장입니다.")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("reuses the same window", 1, wm.views.size)
        assertEquals("다음 문장입니다.", (wm.views.single() as android.widget.TextView).text.toString())

        o.update("")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertFalse(o.isShowing)
        assertTrue(wm.views.isEmpty())
    }

    @Test fun overlayStaysAwayWithoutPermissionOrWhenAppIsInFront() {
        val (noPerm, wm) = overlay(canDraw = false)
        noPerm.update("자막"); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(wm.views.isEmpty())

        var foreground = false
        val (o, _) = overlay(fg = { foreground })
        o.update("자막"); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(o.isShowing)
        foreground = true // user opened FRIDAY: the overlay must go
        o.refresh(); shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertFalse(o.isShowing)
    }
}
