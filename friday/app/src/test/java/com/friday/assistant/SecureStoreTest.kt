package com.friday.assistant

import com.friday.assistant.security.AesGcmCrypto
import com.friday.assistant.security.InMemorySecureStore
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureStoreTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTrip() {
        val k = key()
        val c = AesGcmCrypto { k }
        val blob = c.encrypt("sk-secret-123")
        assertFalse(blob.contains("sk-secret"))
        assertEquals("sk-secret-123", c.decrypt(blob))
    }

    @Test fun eachEncryptionUsesFreshIv() {
        val k = key()
        val c = AesGcmCrypto { k }
        assertNotEquals(c.encrypt("same"), c.encrypt("same"))
    }

    @Test fun tamperedOrWrongKeyYieldsNull() {
        val c = AesGcmCrypto { key() }
        val blob = c.encrypt("secret")
        assertNull(AesGcmCrypto { key() }.decrypt(blob))
        assertNull(c.decrypt(blob.dropLast(3) + "AAA"))
        assertNull(c.decrypt("garbage"))
    }

    @Test fun inMemoryStoreContract() {
        val s = InMemorySecureStore()
        assertNull(s.get("a"))
        s.put("a", "1"); assertEquals("1", s.get("a"))
        s.remove("a"); assertNull(s.get("a"))
    }
}
