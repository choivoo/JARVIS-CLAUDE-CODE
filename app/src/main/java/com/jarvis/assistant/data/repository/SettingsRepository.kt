package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.database.SettingsDao
import com.jarvis.assistant.data.database.SettingsEntity
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.security.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsRepository(
    private val dao: SettingsDao,
    private val secure: SecureStorage,
    scope: CoroutineScope,
) {
    val settings: StateFlow<AppSettings> = dao.observeAll()
        .map { rows -> AppSettings.from(rows.associate { it.key to it.value }) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings =
        AppSettings.from(dao.getAll().associate { it.key to it.value })

    suspend fun getRaw(key: String): String? = dao.getValue(key)

    suspend fun put(key: String, value: String) = dao.put(SettingsEntity(key, value))

    suspend fun put(key: String, value: Boolean) = put(key, value.toString())

    suspend fun putAll(vararg pairs: Pair<String, String>) =
        dao.putAll(pairs.map { SettingsEntity(it.first, it.second) })

    // --- API keys: only ever stored in the Keystore-backed SecureStorage -------------------

    fun aiApiKey(provider: AiProviderType): String? = secure.get("key.ai.${provider.name}")

    fun setAiApiKey(provider: AiProviderType, key: String?) = secure.put("key.ai.${provider.name}", key)

    fun hasAiApiKey(provider: AiProviderType) = secure.has("key.ai.${provider.name}")

    fun ttsApiKey(provider: TtsProviderType): String? = secure.get("key.tts.${provider.name}")

    fun setTtsApiKey(provider: TtsProviderType, key: String?) = secure.put("key.tts.${provider.name}", key)

    fun hasTtsApiKey(provider: TtsProviderType) = secure.has("key.tts.${provider.name}")
}
