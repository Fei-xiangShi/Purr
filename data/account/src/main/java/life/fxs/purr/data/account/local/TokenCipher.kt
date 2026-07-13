package life.fxs.purr.data.account.local

/**
 * Encrypts values that are persisted as session credentials.
 *
 * The serialized value includes its format version so a future key or wire
 * format can be introduced without treating it as legacy plaintext.
 */
interface TokenCipher {
    fun encrypt(value: String): String

    /** Returns null for an unsupported, malformed, or undecryptable value. */
    fun decrypt(value: String): String?

    companion object {
        const val CURRENT_VERSION = "v1"
        const val CURRENT_VERSION_PREFIX = "$CURRENT_VERSION:"
    }
}
