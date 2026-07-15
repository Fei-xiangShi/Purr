package life.fxs.purr.platform.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal interface InstallationIdStore {
    fun getOrCreate(): String
}

@Singleton
internal class SharedPreferencesInstallationIdStore @Inject constructor(
    @ApplicationContext context: Context,
) : InstallationIdStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    @Synchronized
    override fun getOrCreate(): String {
        cached?.let { return it }
        preferences.getString(KEY_INSTALLATION_ID, null)
            ?.takeIf(::isValidInstallationId)
            ?.let {
                cached = it
                return it
            }

        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_INSTALLATION_ID, generated).commit()) {
            "Unable to persist push installation ID"
        }
        cached = generated
        return generated
    }

    private companion object {
        const val PREFERENCES_NAME = "purr_push_installation"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}

internal fun isValidInstallationId(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9._-]{16,128}"))
