package com.friday.assistant.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Storage for secrets such as API keys. Values never appear in logs. */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemorySecureStore : SecureStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** AES-256-GCM over a key supplied by [keyProvider] (Android Keystore in production). */
class AesGcmCrypto(private val keyProvider: () -> SecretKey) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = try {
        val (iv, ct) = blob.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object { const val TRANSFORM = "AES/GCM/NoPadding" }
}

/** Ciphertext lives in private SharedPreferences, the AES key never leaves the Android Keystore. */
class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs = context.applicationContext.getSharedPreferences("friday_secrets", Context.MODE_PRIVATE)
    private val crypto = AesGcmCrypto { keystoreKey() }

    override fun get(key: String): String? = prefs.getString(key, null)?.let { crypto.decrypt(it) }
    override fun put(key: String, value: String) { prefs.edit().putString(key, crypto.encrypt(value)).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object { const val ALIAS = "friday_secret_key" }
}
