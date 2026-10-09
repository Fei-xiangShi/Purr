package life.fxs.purr.feature.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists whether the one-time battery optimization prompt has been shown. */
@Singleton
class BatteryPromptStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPrompted(): Boolean = prefs.getBoolean(KEY_PROMPTED, false)

    fun markPrompted() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "purr_prompts"
        const val KEY_PROMPTED = "battery_optimization_prompted"
    }
}
