package life.fxs.purr.data.account.local

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Test

class AndroidKeyStoreTokenCipherTest {
    @Test
    fun `AES GCM envelope round trips without exposing plaintext`() {
        val cipher = cipherWithNewKey()

        val encrypted = cipher.encrypt("access-token.with:delimiters")

        assertThat(encrypted).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(encrypted.split(':')).hasSize(3)
        assertThat(encrypted).doesNotContain("access-token")
        assertThat(cipher.decrypt(encrypted)).isEqualTo("access-token.with:delimiters")
    }

    @Test
    fun `each encryption uses a different GCM nonce`() {
        val cipher = cipherWithNewKey()

        val first = cipher.encrypt("same-token")
        val second = cipher.encrypt("same-token")

        assertThat(first).isNotEqualTo(second)
        assertThat(cipher.decrypt(first)).isEqualTo("same-token")
        assertThat(cipher.decrypt(second)).isEqualTo("same-token")
    }

    @Test
    fun `tampered ciphertext wrong key and unknown version fail closed`() {
        val cipher = cipherWithNewKey()
        val encrypted = cipher.encrypt("refresh-token")
        val parts = encrypted.split(':')
        val ciphertext = Base64.getUrlDecoder().decode(parts[2])
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        val tampered = "${parts[0]}:${parts[1]}:${Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)}"

        assertThat(cipher.decrypt(tampered)).isNull()
        assertThat(cipherWithNewKey().decrypt(encrypted)).isNull()
        assertThat(cipher.decrypt(encrypted.replaceFirst("v1:", "v2:"))).isNull()
        assertThat(cipher.decrypt("not-an-envelope")).isNull()
    }

    private fun cipherWithNewKey(): AndroidKeyStoreTokenCipher {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val key = generator.generateKey()
        return AndroidKeyStoreTokenCipher { key }
    }
}
