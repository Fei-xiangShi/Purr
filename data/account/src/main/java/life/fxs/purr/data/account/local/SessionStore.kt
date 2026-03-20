package life.fxs.purr.data.account.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.AuthSession

@Singleton
class SessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val session: Flow<AuthSession?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            val accessToken = preferences[ACCESS_TOKEN]
            val refreshToken = preferences[REFRESH_TOKEN]
            val userId = preferences[USER_ID]
            val displayName = preferences[DISPLAY_NAME]
            val avatarUrl = preferences[AVATAR_URL]?.takeIf { it.isNotBlank() }
            if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || userId.isNullOrBlank() || displayName.isNullOrBlank()) {
                null
            } else {
                AuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    self = SelfProfile(
                        userId = userId,
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                    ),
                )
            }
        }

    suspend fun save(session: AuthSession) {
        dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = session.accessToken
            preferences[REFRESH_TOKEN] = session.refreshToken
            preferences[USER_ID] = session.self.userId
            preferences[DISPLAY_NAME] = session.self.displayName
            session.self.avatarUrl?.let { preferences[AVATAR_URL] = it } ?: preferences.set(AVATAR_URL, "")
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    companion object {
        const val FILE_NAME = "purr_session.preferences_pb"

        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val USER_ID = stringPreferencesKey("user_id")
        private val DISPLAY_NAME = stringPreferencesKey("display_name")
        private val AVATAR_URL = stringPreferencesKey("avatar_url")
    }
}
