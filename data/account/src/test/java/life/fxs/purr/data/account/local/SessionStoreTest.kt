package life.fxs.purr.data.account.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession
import org.junit.Test

class SessionStoreTest {
    @Test
    fun `save persists only versioned encrypted token values`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val cipher = TestTokenCipher()
        val store = SessionStore(dataStore, cipher)

        store.save(TEST_SESSION)

        val preferences = dataStore.data.first()
        val storedAccess = preferences[ACCESS_TOKEN]
        val storedRefresh = preferences[REFRESH_TOKEN]
        assertThat(storedAccess).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(storedRefresh).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(storedAccess).doesNotContain(TEST_SESSION.accessToken)
        assertThat(storedRefresh).doesNotContain(TEST_SESSION.refreshToken)
        assertThat(preferences[TOKEN_STORAGE_VERSION]).isEqualTo(1)
        assertThat(cipher.encryptedValues).containsExactly(
            TEST_SESSION.accessToken,
            TEST_SESSION.refreshToken,
        ).inOrder()
        assertThat(store.session.first()).isEqualTo(TEST_SESSION)
    }

    @Test
    fun `legacy plaintext tokens migrate atomically on first read`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(dataStore, accessToken = "legacy-access", refreshToken = "legacy-refresh")
        val cipher = TestTokenCipher()
        val store = SessionStore(dataStore, cipher)

        val restored = store.session.first()

        assertThat(restored).isEqualTo(
            TEST_SESSION.copy(accessToken = "legacy-access", refreshToken = "legacy-refresh"),
        )
        val migrated = dataStore.data.first()
        assertThat(migrated[ACCESS_TOKEN]).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(migrated[REFRESH_TOKEN]).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(migrated[ACCESS_TOKEN]).isNotEqualTo("legacy-access")
        assertThat(migrated[REFRESH_TOKEN]).isNotEqualTo("legacy-refresh")
        assertThat(migrated[TOKEN_STORAGE_VERSION]).isEqualTo(1)
    }

    @Test
    fun `legacy token containing colon is migrated instead of mistaken for ciphertext`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(dataStore, accessToken = "legacy:access", refreshToken = "legacy:refresh")
        val store = SessionStore(dataStore, TestTokenCipher())

        assertThat(store.session.first()).isEqualTo(
            TEST_SESSION.copy(accessToken = "legacy:access", refreshToken = "legacy:refresh"),
        )

        val migrated = dataStore.data.first()
        assertThat(migrated[ACCESS_TOKEN]).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
        assertThat(migrated[REFRESH_TOKEN]).startsWith(TokenCipher.CURRENT_VERSION_PREFIX)
    }

    @Test
    fun `decrypt failure clears the complete session before emitting signed out state`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(
            dataStore,
            accessToken = "${TokenCipher.CURRENT_VERSION}:invalid",
            refreshToken = TestTokenCipher().encrypt("refresh-token"),
        )
        val store = SessionStore(dataStore, TestTokenCipher(invalidCiphertexts = setOf("v1:invalid")))

        assertThat(store.session.first()).isNull()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
    }

    @Test
    fun `unsupported ciphertext version is rejected and cleared instead of migrated`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(
            dataStore,
            accessToken = "v2:iv:ciphertext",
            refreshToken = TestTokenCipher().encrypt("refresh-token"),
        )
        val cipher = TestTokenCipher()
        val store = SessionStore(dataStore, cipher)

        assertThat(store.session.first()).isNull()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
        assertThat(cipher.encryptedValues).isEmpty()
    }

    @Test
    fun `legacy migration encryption failure clears plaintext and signs out`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(dataStore, accessToken = "legacy-access", refreshToken = "legacy-refresh")
        val store = SessionStore(dataStore, FailingEncryptTokenCipher())

        assertThat(store.session.first()).isNull()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
    }

    @Test
    fun `plaintext credentials are rejected after legacy migration is complete`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(dataStore, accessToken = "injected-access", refreshToken = "injected-refresh")
        dataStore.edit { it[TOKEN_STORAGE_VERSION] = 1 }
        val cipher = TestTokenCipher()
        val store = SessionStore(dataStore, cipher)

        assertThat(store.session.first()).isNull()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
        assertThat(cipher.encryptedValues).isEmpty()
    }

    @Test
    fun `credentials from a newer storage version are rejected on downgrade`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        seedSession(dataStore, accessToken = "future-access", refreshToken = "future-refresh")
        dataStore.edit { it[TOKEN_STORAGE_VERSION] = 2 }
        val store = SessionStore(dataStore, TestTokenCipher())

        assertThat(store.session.first()).isNull()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
    }

    @Test
    fun `sign out keeps the migration marker while deleting credentials`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SessionStore(dataStore, TestTokenCipher())
        store.save(TEST_SESSION)

        store.clear()

        val cleared = dataStore.data.first()
        assertThat(cleared[ACCESS_TOKEN]).isNull()
        assertThat(cleared[REFRESH_TOKEN]).isNull()
        assertThat(cleared[TOKEN_STORAGE_VERSION]).isEqualTo(1)
    }

    @Test
    fun `storage read failure does not emit a false signed out state`() = runTest {
        val source = InMemoryPreferencesDataStore()
        val cipher = TestTokenCipher()
        SessionStore(source, cipher).save(TEST_SESSION)
        val persisted = source.data.first()
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                emit(persisted)
                throw IOException("storage temporarily unavailable")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("Not used")
        }

        val emissions = SessionStore(failingStore, cipher).session.toList()

        assertThat(emissions).containsExactly(TEST_SESSION)
    }

    @Test
    fun `direct current-session read propagates storage failure`() = runTest {
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("storage temporarily unavailable")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("Not used")
        }

        var failure: IOException? = null
        try {
            SessionStore(failingStore, TestTokenCipher()).readCurrentSession()
        } catch (error: IOException) {
            failure = error
        }

        assertThat(failure).isNotNull()
    }

    private suspend fun seedSession(
        dataStore: DataStore<Preferences>,
        accessToken: String,
        refreshToken: String,
    ) {
        dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[REFRESH_TOKEN] = refreshToken
            preferences[USER_ID] = TEST_SESSION.self.userId
            preferences[DISPLAY_NAME] = TEST_SESSION.self.displayName
            preferences[AVATAR_URL] = TEST_SESSION.self.avatarUrl.orEmpty()
        }
    }

    private class TestTokenCipher(
        private val invalidCiphertexts: Set<String> = emptySet(),
    ) : TokenCipher {
        val encryptedValues = mutableListOf<String>()

        override fun encrypt(value: String): String {
            encryptedValues += value
            return "${TokenCipher.CURRENT_VERSION}:${value.reversed()}"
        }

        override fun decrypt(value: String): String? {
            if (value in invalidCiphertexts) return null
            val parts = value.split(':')
            if (parts.size != 2 || parts[0] != TokenCipher.CURRENT_VERSION) return null
            return parts[1].reversed()
        }
    }

    private class FailingEncryptTokenCipher : TokenCipher {
        override fun encrypt(value: String): String = throw IllegalStateException("cipher unavailable")

        override fun decrypt(value: String): String? = null
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }

    private companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val TOKEN_STORAGE_VERSION = intPreferencesKey("token_storage_version")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val AVATAR_URL = stringPreferencesKey("avatar_url")

        val TEST_SESSION = AuthSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            self = SelfProfile(
                userId = "user-1",
                displayName = "Purr",
                avatarUrl = "https://example.invalid/avatar.png",
            ),
        )
    }
}
