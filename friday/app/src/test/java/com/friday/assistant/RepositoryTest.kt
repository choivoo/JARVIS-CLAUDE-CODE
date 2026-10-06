package com.friday.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.friday.assistant.ai.Role
import com.friday.assistant.data.ConversationRepository
import com.friday.assistant.data.FridayDatabase
import com.friday.assistant.security.InMemorySecureStore
import com.friday.assistant.settings.AiProviderType
import com.friday.assistant.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var db: FridayDatabase
    private lateinit var repo: ConversationRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FridayDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ConversationRepository(db.dao())
    }

    @After fun tearDown() = db.close()

    @Test fun storesAndOrdersMessages() = runTest {
        repo.addUser("안녕")
        repo.addAssistant("Hello.", "안녕하세요.")
        val all = repo.messages.first()
        assertEquals(listOf("user", "assistant"), all.map { it.role })
        assertEquals("안녕하세요.", all[1].subtitle)
        assertEquals(2, repo.count())
    }

    @Test fun contextIsBoundedAndKeepsNewest() = runTest {
        repeat(30) { repo.addUser("질문 $it"); repo.addAssistant("Answer $it.", "답변 $it") }
        val ctx = repo.context(maxTurns = 6)
        val turns = ctx.filter { it.role != Role.SYSTEM }
        assertEquals(6, turns.size)
        assertEquals("Answer 29.", turns.last().content)
        val digest = ctx.firstOrNull { it.role == Role.SYSTEM }
        assertTrue(digest != null && digest.content.length <= 340)
    }

    @Test fun contextRespectsCharBudget() = runTest {
        repeat(10) { repo.addUser("x".repeat(400)) }
        val total = repo.context(maxTurns = 10, maxChars = 1000).filter { it.role != Role.SYSTEM }.sumOf { it.content.length }
        assertTrue(total <= 1000)
    }

    @Test fun clearDeletesEverything() = runTest {
        repo.addUser("a"); repo.addAssistant("b", "c")
        repo.clear()
        assertEquals(0, repo.count())
        assertTrue(repo.messages.first().isEmpty())
        assertTrue(repo.context().isEmpty())
    }

    @Test fun preferences() = runTest {
        repo.putPreference("k", "v1"); repo.putPreference("k", "v2")
        assertEquals("v2", repo.getPreference("k"))
    }

    @Test fun settingsKeepSecretsOutOfPreferences() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val secrets = InMemorySecureStore()
        val s = SettingsRepository.create(ctx, secrets)
        s.setAiApiKey("  AIza-super-secret ")
        s.update { it.copy(aiProvider = AiProviderType.OLLAMA, defaultCity = "Busan", ttsSpeed = 1.3f) }
        val plain = ctx.getSharedPreferences("friday_settings", Context.MODE_PRIVATE).all.toString()
        assertFalse(plain.contains("AIza"))
        assertEquals("AIza-super-secret", s.aiApiKey())
        val reloaded = SettingsRepository.create(ctx, secrets).current
        assertEquals(AiProviderType.OLLAMA, reloaded.aiProvider)
        assertEquals("Busan", reloaded.defaultCity)
        assertEquals(1.3f, reloaded.ttsSpeed, 0.001f)
        s.setAiApiKey("")
        assertEquals("", s.aiApiKey())
    }
}
