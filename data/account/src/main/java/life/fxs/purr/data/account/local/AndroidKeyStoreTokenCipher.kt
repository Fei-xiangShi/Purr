package life.fxs.purr.data.account.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore backed AES/GCM implementation for session credentials.
 *
 * The key is non-exportable and the IV is stored with each value. Decryption
 * deliberately converts all format/provider failures into null so callers can
 * invalidate a session without logging or exposing credential material.
 */
class AndroidKeyStoreTokenCipher internal constructor(
    private val keyProvider: SessionTokenKeyProvider,
) : TokenCipher {
    constructor() : this(AndroidKeyStoreSessionTokenKeyProvider())

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        val iv = cipher.iv.also { require(it.size == IV_LENGTH_BYTES) }
        cipher.updateAAD(TokenCipher.CURRENT_VERSION.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append(TokenCipher.CURRENT_VERSION)
            append(':')
            append(encode(iv))
            append(':')
            append(encode(encrypted))
        }
    }

    override fun decrypt(value: String): String? {
        return try {
            val parts = value.split(':')
            require(parts.size == 3 && parts[0] == TokenCipher.CURRENT_VERSION)
            val iv = decode(parts[1]).also { require(it.size == IV_LENGTH_BYTES) }
            val encrypted = decode(parts[2]).also { require(it.size >= TAG_LENGTH_BYTES) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.updateAAD(TokenCipher.CURRENT_VERSION.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / Byte.SIZE_BITS
    }
}

internal fun interface SessionTokenKeyProvider {
    fun getOrCreate(): SecretKey
}

internal class AndroidKeyStoreSessionTokenKeyProvider : SessionTokenKeyProvider {
    @Synchronized
    override fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "life.fxs.purr.session.tokens.v1"
        const val KEY_SIZE_BITS = 256
    }
}
