package com.jarvis.assistant

import android.speech.tts.Voice
import com.jarvis.assistant.tts.AndroidTTSProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class VoiceGenderTest {
    private fun v(name: String, locale: Locale = Locale.UK) = Voice(name, locale, 400, 200, false, emptySet())

    @Test
    fun knownVoicesAreClassified() {
        assertEquals(AndroidTTSProvider.Gender.MALE, AndroidTTSProvider.gender(v("en-gb-x-gbd-local")))
        assertEquals(AndroidTTSProvider.Gender.FEMALE, AndroidTTSProvider.gender(v("en-gb-x-gba-local")))
        assertEquals(AndroidTTSProvider.Gender.FEMALE, AndroidTTSProvider.gender(v("en-US-SMTf00", Locale.US)))
        assertEquals(AndroidTTSProvider.Gender.MALE, AndroidTTSProvider.gender(v("en-US-SMTm01", Locale.US)))
        assertEquals(AndroidTTSProvider.Gender.UNKNOWN, AndroidTTSProvider.gender(v("en-us-default", Locale.US)))
    }

    @Test
    fun maleOutranksFemaleEvenWhenFemaleIsBritish() {
        val male = v("en-us-x-iol-local", Locale.US)
        val female = v("en-gb-x-gba-local", Locale.UK)
        assertTrue(AndroidTTSProvider.score(male) > AndroidTTSProvider.score(female))
    }
}
