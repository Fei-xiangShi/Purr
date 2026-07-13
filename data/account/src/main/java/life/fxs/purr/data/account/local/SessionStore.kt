package life.fxs.purr.data.account.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession

@Singleton
class SessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher,
) {
    private val migrationMutex = Mutex()

    val session: Flow<AuthSession?> = dataStore.data
        .catch { exception ->
            // A storage read failure is not evidence that the user signed out.
            // Complete without a replacement value so state holders retain the
            // last confirmed session; a cold start still remains signed out.
            if (exception !is IOException) throw exception
        }
        .transform { preferences ->
            when (val result = decodeSession(preferences)) {
                is SessionRead.Value -> emit(result.session)
                SessionRead.Stale -> Unit
            }
        }

    suspend fun save(session: AuthSession) {
        val encryptedAccessToken = tokenCipher.encrypt(session.accessToken)
        val encryptedRefreshToken = tokenCipher.encrypt(session.refreshToken)
        dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = encryptedAccessToken
            preferences[REFRESH_TOKEN] = encryptedRefreshToken
            preferences[TOKEN_STORAGE_VERSION] = CURRENT_TOKEN_STORAGE_VERSION
            preferences[USER_ID] = session.self.userId
            preferences[DISPLAY_NAME] = session.self.displayName
            session.self.avatarUrl?.let { preferences[AVATAR_URL] = it } ?: preferences.set(AVATAR_URL, "")
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.clear()
            preferences[TOKEN_STORAGE_VERSION] = CURRENT_TOKEN_STORAGE_VERSION
        }
    }

    /**
     * Reads the current persisted value without the flow-level IOException handler.
     * A storage failure must reach callers so they can retain the in-memory session
     * instead of interpreting an unavailable store as a real signed-out state.
     */
    suspend fun readCurrentSession(): AuthSession? {
        repeat(MAX_READ_ATTEMPTS) {
            when (val result = decodeSession(dataStore.data.first())) {
                is SessionRead.Value -> return result.session
                SessionRead.Stale -> Unit
            }
        }
        throw IOException("Session store changed during each read attempt")
    }

    private suspend fun decodeSession(preferences: Preferences): SessionRead {
        val rawAccessToken = preferences[ACCESS_TOKEN]
        val rawRefreshToken = preferences[REFRESH_TOKEN]
        val storageVersion = preferences[TOKEN_STORAGE_VERSION]
        if (storageVersion != null && storageVersion != CURRENT_TOKEN_STORAGE_VERSION) {
            return if (clearIfCredentialsUnchanged(rawAccessToken, rawRefreshToken)) {
                SessionRead.Value(null)
            } else {
                SessionRead.Stale
            }
        }
        val legacyMigrationAllowed = storageVersion == null
        val accessToken = readToken(rawAccessToken, legacyMigrationAllowed)
        val refreshToken = readToken(rawRefreshToken, legacyMigrationAllowed)
        if (accessToken is TokenRead.Invalid || refreshToken is TokenRead.Invalid) {
            return if (clearIfCredentialsUnchanged(rawAccessToken, rawRefreshToken)) {
                SessionRead.Value(null)
            } else {
                SessionRead.Stale
            }
        }
        if (
            legacyMigrationAllowed &&
            (accessToken is TokenRead.Legacy || refreshToken is TokenRead.Legacy)
        ) {
            when (
                migrateLegacyTokens(
                    rawAccessToken = rawAccessToken,
                    rawRefreshToken = rawRefreshToken,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                )
            ) {
                MigrationOutcome.APPLIED -> Unit
                MigrationOutcome.CLEARED -> return SessionRead.Value(null)
                MigrationOutcome.STALE -> return SessionRead.Stale
            }
        } else if (legacyMigrationAllowed) {
            if (!markMigrationCompleteIfCredentialsUnchanged(rawAccessToken, rawRefreshToken)) {
                return SessionRead.Stale
            }
        }
        val accessValue = accessToken.valueOrNull()
        val refreshValue = refreshToken.valueOrNull()
        val userId = preferences[USER_ID]
        val displayName = preferences[DISPLAY_NAME]
        val avatarUrl = preferences[AVATAR_URL]?.takeIf { it.isNotBlank() }
        return if (
            accessValue.isNullOrBlank() ||
            refreshValue.isNullOrBlank() ||
            userId.isNullOrBlank() ||
            displayName.isNullOrBlank()
        ) {
            SessionRead.Value(null)
        } else {
            SessionRead.Value(
                AuthSession(
                    accessToken = accessValue,
                    refreshToken = refreshValue,
                    self = SelfProfile(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                    ),
                ),
            )
        }
    }

    private fun readToken(raw: String?, legacyMigrationAllowed: Boolean): TokenRead = when {
        raw == null -> TokenRead.Missing
        raw.startsWith(TokenCipher.CURRENT_VERSION_PREFIX) ->
            tokenCipher.decrypt(raw)?.let(TokenRead::Encrypted) ?: TokenRead.Invalid
        VERSIONED_ENVELOPE_PATTERN.containsMatchIn(raw) -> TokenRead.Invalid
        legacyMigrationAllowed -> TokenRead.Legacy(raw)
        else -> TokenRead.Invalid
    }

    private suspend fun migrateLegacyTokens(
        rawAccessToken: String?,
        rawRefreshToken: String?,
        accessToken: TokenRead,
        refreshToken: TokenRead,
    ): MigrationOutcome = migrationMutex.withLock {
        try {
            val legacyAccess = (accessToken as? TokenRead.Legacy)?.value
            val legacyRefresh = (refreshToken as? TokenRead.Legacy)?.value
            val encryptedAccess = legacyAccess?.let(tokenCipher::encrypt)
            val encryptedRefresh = legacyRefresh?.let(tokenCipher::encrypt)
            var applied = false
            dataStore.edit { current ->
                if (current[ACCESS_TOKEN] != rawAccessToken || current[REFRESH_TOKEN] != rawRefreshToken) {
                    return@edit
                }
                if (encryptedAccess != null) {
                    current[ACCESS_TOKEN] = encryptedAccess
                }
                if (encryptedRefresh != null) current[REFRESH_TOKEN] = encryptedRefresh
                current[TOKEN_STORAGE_VERSION] = CURRENT_TOKEN_STORAGE_VERSION
                applied = true
            }
            if (applied) MigrationOutcome.APPLIED else MigrationOutcome.STALE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (clearIfCredentialsUnchanged(rawAccessToken, rawRefreshToken)) {
                MigrationOutcome.CLEARED
            } else {
                MigrationOutcome.STALE
            }
        }
    }

    private suspend fun clearIfCredentialsUnchanged(
        expectedAccessToken: String?,
        expectedRefreshToken: String?,
    ): Boolean {
        var cleared = false
        dataStore.edit { preferences ->
            if (
                preferences[ACCESS_TOKEN] == expectedAccessToken &&
                preferences[REFRESH_TOKEN] == expectedRefreshToken
            ) {
                preferences.clear()
                preferences[TOKEN_STORAGE_VERSION] = CURRENT_TOKEN_STORAGE_VERSION
                cleared = true
            }
        }
        return cleared
    }

    private suspend fun markMigrationCompleteIfCredentialsUnchanged(
        expectedAccessToken: String?,
        expectedRefreshToken: String?,
    ): Boolean {
        var applied = false
        dataStore.edit { preferences ->
            if (
                preferences[ACCESS_TOKEN] == expectedAccessToken &&
                preferences[REFRESH_TOKEN] == expectedRefreshToken
            ) {
                preferences[TOKEN_STORAGE_VERSION] = CURRENT_TOKEN_STORAGE_VERSION
                applied = true
            }
        }
        return applied
    }

    private sealed interface TokenRead {
        data object Missing : TokenRead

        data class Encrypted(val value: String) : TokenRead

        data class Legacy(val value: String) : TokenRead

        data object Invalid : TokenRead
    }

    private enum class MigrationOutcome {
        APPLIED,
        CLEARED,
        STALE,
    }

    private sealed interface SessionRead {
        data class Value(val session: AuthSession?) : SessionRead

        data object Stale : SessionRead
    }

    private fun TokenRead.valueOrNull(): String? = when (this) {
        TokenRead.Missing, TokenRead.Invalid -> null
        is TokenRead.Encrypted -> value
        is TokenRead.Legacy -> value
    }

    companion object {
        const val FILE_NAME = "purr_session.preferences_pb"

        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val TOKEN_STORAGE_VERSION = intPreferencesKey("token_storage_version")
        private val USER_ID = stringPreferencesKey("user_id")
        private val DISPLAY_NAME = stringPreferencesKey("display_name")
        private val AVATAR_URL = stringPreferencesKey("avatar_url")

        private const val CURRENT_TOKEN_STORAGE_VERSION = 1
        private const val MAX_READ_ATTEMPTS = 2
        private val VERSIONED_ENVELOPE_PATTERN = Regex("^v\\d+:")
    }
}
