package local.codex.mobile.core

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultEncryptionTest {
    private val payload = """[{"name":"github","secret":"test-only-token"}]""".toByteArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(12) { it.toByte() }

    @Test fun encryptsAndRestoresAllFields() {
        val password = "test phrase".toCharArray()
        val key = VaultEncryption.key(password, salt)
        val ciphertext = VaultEncryption.encrypt(key, nonce, payload)
        assertTrue(password.all { it == '\u0000' })
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("test-only-token"))
        assertArrayEquals(payload, VaultEncryption.decrypt(key, nonce, ciphertext))
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsWrongPassword() {
        val ciphertext = VaultEncryption.encrypt(VaultEncryption.key("first password".toCharArray(), salt), nonce, payload)
        VaultEncryption.decrypt(VaultEncryption.key("wrong password".toCharArray(), salt), nonce, ciphertext)
    }

    @Test(expected = AEADBadTagException::class)
    fun detectsCiphertextTampering() {
        val key = VaultEncryption.key("test password".toCharArray(), salt)
        val ciphertext = VaultEncryption.encrypt(key, nonce, payload)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        VaultEncryption.decrypt(key, nonce, ciphertext)
    }
}
