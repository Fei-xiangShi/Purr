package life.fxs.purr.platform.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal interface PushTokenStore {
    fun observe(): StateFlow<String?>

    fun update(token: String)
}

@Singleton
internal class SharedPreferencesPushTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : PushTokenStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getString(KEY_TOKEN, null)?.takeIf(::isValidPushToken))

    override fun observe(): StateFlow<String?> = state

    @Synchronized
    override fun update(token: String) {
        if (!isValidPushToken(token) || state.value == token) return
        if (preferences.edit().putString(KEY_TOKEN, token).commit()) {
            state.value = token
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "purr_push_token"
        const val KEY_TOKEN = "fcm_token"
    }
}

internal fun isValidPushToken(value: String): Boolean =
    value.length in 16..4_096 && value.none(Char::isWhitespace)
