package com.friday.assistant

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.settings.AudioFocusMode
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.settings.TtsProviderType
import com.friday.assistant.tts.TTSProvider
import com.friday.assistant.tts.TtsException
import com.friday.assistant.voice.AudioFocusManager
import com.friday.assistant.voice.Emphasis
import com.friday.assistant.voice.FocusController
import com.friday.assistant.voice.SpeechChunk
import com.friday.assistant.voice.SpeechInterruptController
import com.friday.assistant.voice.SpeechRequest
import com.friday.assistant.voice.SubtitleSynchronizer
import com.friday.assistant.voice.VoiceEngine
import com.friday.assistant.voice.VoiceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class FakeTts(override val name: String, private val failWith: Exception? = null) : TTSProvider {
    val said = mutableListOf<String>()
    val emphases = mutableListOf<Emphasis>()
    var gate: CompletableDeferred<Unit>? = null
    var stopped = 0
    private val _amp = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amp
    fun setAmp(v: Float) { _amp.value = v }
    override suspend fun speak(text: String, emphasis: Emphasis, onFirstAudio: () -> Unit) {
        failWith?.let { throw it }
        said += text; emphases += emphasis
        onFirstAudio()
        gate?.await()
    }
    override fun stop() { stopped++ }
}

class FakeFocus : FocusController {
    val events = mutableListOf<String>()
    var onLost: () -> Unit = {}
    override fun acquire(mode: AudioFocusMode, onLost: () -> Unit): Boolean { events += "acquire:$mode"; this.onLost = onLost; return true }
    override fun release() { events += "release" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceEngineTest {
    private fun engine(
        settings: FridaySettings = FridaySettings(),
        a: TTSProvider? = null, b: TTSProvider? = null, android: TTSProvider = FakeTts("android"), focus: FocusController = FakeFocus(),
    ) = VoiceEngine({ settings }, { when (it) { TtsProviderType.OPENAI_COMPATIBLE -> a; TtsProviderType.ELEVENLABS -> b; else -> null } }, android, focus)

    private fun req(vararg s: String, onChunk: (Int) -> Unit = {}, onFirst: () -> Unit = {}) =
        SpeechRequest(s.map { SpeechChunk(it, "ko $it") }, onChunkStart = onChunk, onFirstAudio = onFirst)

    @Test fun tierAThenBThenAndroidOrderFollowsSettings() {
        val a = FakeTts("A"); val b = FakeTts("B"); val c = FakeTts("C")
        assertEquals(listOf("A", "B", "C"), engine(a = a, b = b, android = c).plan().map { it.name })
        val swapped = FridaySettings(ttsPriority = listOf(TtsProviderType.ELEVENLABS, TtsProviderType.OPENAI_COMPATIBLE, TtsProviderType.ANDROID))
        assertEquals(listOf("B", "A", "C"), engine(swapped, a, b, c).plan().map { it.name })
    }

    @Test fun tiersWithoutCredentialsAreSkippedAndAndroidIsAlwaysThere() {
        val c = FakeTts("C")
        assertEquals(listOf("C"), engine(a = null, b = null, android = c).plan().map { it.name })
        val onlyAndroidListed = FridaySettings(ttsPriority = listOf(TtsProviderType.ANDROID))
        assertEquals(listOf("C"), engine(onlyAndroidListed, FakeTts("A"), FakeTts("B"), c).plan().map { it.name })
    }

    @Test fun failingPremiumFallsBackThenToAndroid() = runTest {
        val a = FakeTts("A", TtsException("server")); val b = FakeTts("B", TtsException("network")); val c = FakeTts("C")
        val e = engine(a = a, b = b, android = c)
        e.speak(req("Hello there."))
        assertEquals(listOf("Hello there."), c.said)
        assertEquals("C", e.lastProvider)

        val ok = FakeTts("B2")
        val e2 = engine(a = FakeTts("A", TtsException("x")), b = ok, android = c)
        e2.speak(req("Second."))
        assertEquals(listOf("Second."), ok.said)
    }

    @Test fun chunksAreSpokenInOrderWithCallbacks() = runTest {
        val a = FakeTts("A")
        val seen = mutableListOf<String>()
        var first = 0
        engine(a = a).speak(req("One.", "Two.", "Three.", onChunk = { seen += "chunk$it" }, onFirst = { first++ }))
        assertEquals(listOf("One.", "Two.", "Three."), a.said)
        assertEquals(listOf("chunk0", "chunk1", "chunk2"), seen)
        assertEquals(3, first) // provider fires per chunk; the controller's latency tracker keeps only the first
    }

    @Test fun focusIsTakenBeforeAndReleasedAfterEvenOnFailure() = runTest {
        val f = FakeFocus()
        engine(a = FakeTts("A"), focus = f).speak(req("Hi."))
        assertEquals(listOf("acquire:DUCK", "release"), f.events)
        val f2 = FakeFocus()
        val boom = engine(android = FakeTts("C", TtsException("dead")), focus = f2)
        try { boom.speak(req("Hi.")); fail() } catch (e: TtsException) { }
        assertEquals(listOf("acquire:DUCK", "release"), f2.events)
    }

    @Test fun focusModeComesFromSettings() = runTest {
        val f = FakeFocus()
        engine(FridaySettings(audioFocusMode = AudioFocusMode.PAUSE), a = FakeTts("A"), focus = f).speak(req("Hi."))
        assertEquals("acquire:PAUSE", f.events.first())
    }

    @Test fun interruptStopsSpeechAndNoLaterChunkIsSpoken() = runTest {
        val a = FakeTts("A").also { it.gate = CompletableDeferred() }
        val e = engine(a = a)
        val job = launch { try { e.speak(req("First.", "Second.")) } catch (c: kotlinx.coroutines.CancellationException) { } }
        runCurrent()
        assertTrue(e.interrupts.speaking.value)
        e.interrupts.interrupt()
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals("the second sentence must never start", listOf("First."), a.said)
        assertTrue(a.stopped > 0)
        assertFalse(e.interrupts.speaking.value)
        assertEquals(1, e.interrupts.interruptions)
    }

    @Test fun lossOfAudioFocusInterrupts() = runTest {
        val a = FakeTts("A").also { it.gate = CompletableDeferred() }
        val f = FakeFocus()
        val e = engine(a = a, focus = f)
        val job = launch { try { e.speak(req("A call is coming.")) } catch (c: kotlinx.coroutines.CancellationException) { } }
        runCurrent()
        f.onLost() // e.g. an incoming call took audio focus
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertFalse(e.interrupts.speaking.value)
    }

    @Test fun amplitudeFollowsTheProviderThatIsSpeaking() = runTest {
        val a = FakeTts("A").also { it.gate = CompletableDeferred() }
        val e = engine(a = a)
        val job = launch { e.speak(req("Level check.")) }
        runCurrent()
        a.setAmp(0.7f)
        runCurrent()
        assertEquals(0.7f, e.amplitude.value, 0.001f)
        a.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0f, e.amplitude.value, 0.001f)
        assertTrue(job.isCompleted)
    }

    @Test fun emphasisIsPassedToTheProvider() = runTest {
        val a = FakeTts("A")
        engine(a = a).speak(SpeechRequest(listOf(SpeechChunk("Battery low.", "")), Emphasis.WARNING))
        assertEquals(listOf(Emphasis.WARNING), a.emphases)
    }

    @Test fun voiceProfileIsOriginalAndWarningIsSlower() {
        val p = VoiceProfile.FRIDAY
        assertTrue(p.instructions(Emphasis.NORMAL).contains("female"))
        assertTrue(p.instructions(Emphasis.WARNING).length > p.instructions(Emphasis.NORMAL).length)
        assertTrue(p.rateFactor(Emphasis.WARNING) < 1f && p.rateFactor(Emphasis.NORMAL) == 1f)
        assertTrue(p.pitchFactor(Emphasis.WARNING) < 1f)
    }

    // ---- subtitle synchronisation ---------------------------------------------------------------------------

    @Test fun subtitlesSplitSentenceBySentence() {
        val c = SubtitleSynchronizer.split("You have two events. The first is at four PM.", "일정이 2개 있습니다. 첫 일정은 오후 4시입니다.")
        assertEquals(listOf(SpeechChunk("You have two events.", "일정이 2개 있습니다."), SpeechChunk("The first is at four PM.", "첫 일정은 오후 4시입니다.")), c)
    }

    @Test fun mismatchedSentenceCountsKeepOneChunk() {
        val c = SubtitleSynchronizer.split("One. Two. Three.", "하나 둘 셋입니다.")
        assertEquals(1, c.size)
        assertEquals("하나 둘 셋입니다.", c[0].subtitle)
        assertEquals("Yes?", SubtitleSynchronizer.split("Yes?", "네?").single().speech)
        assertTrue(SubtitleSynchronizer.split("  ", "x").isEmpty())
    }

    @Test fun decimalNumbersDoNotSplitSentences() {
        assertEquals(1, SubtitleSynchronizer.split("It is 3.5 degrees.", "3.5도입니다.").size)
    }

    // ---- barge-in -------------------------------------------------------------------------------------------

    @Test fun bargeInOnlyWhileSpeakingAndNotForOwnFridayWord() {
        val c = SpeechInterruptController()
        assertFalse("not speaking: a plain wake, not a barge-in", c.isBargeIn("FRIDAY"))
        c.onSpeechStart("The weather this afternoon is expected to be sunny.")
        assertTrue(c.isBargeIn("프라이데이"))
        assertTrue(c.isBargeIn("Friday stop"))
        assertFalse(c.isBargeIn("sunny weather"))
        c.onSpeechEnd()
        c.onSpeechStart("Your meeting is on Friday at four PM.")
        assertTrue(c.echoesWakeWord())
        assertFalse("own voice saying Friday must not interrupt itself", c.isBargeIn("friday"))
    }

    // ---- real Android audio focus (Robolectric shadow) ------------------------------------------------------

    @Test fun audioFocusManagerRequestsAndAbandons() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val shadow = shadowOf(ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val m = AudioFocusManager(ctx)
        assertTrue(m.acquire(AudioFocusMode.DUCK))
        val duck = shadow.lastAudioFocusRequest
        assertNotNull(duck)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, duck!!.audioFocusRequest.focusGain)
        m.release()
        assertTrue(m.acquire(AudioFocusMode.PAUSE))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest!!.audioFocusRequest.focusGain)
        m.release()
        assertTrue("OFF never touches audio focus", AudioFocusManager(ctx).acquire(AudioFocusMode.OFF))
    }
}
