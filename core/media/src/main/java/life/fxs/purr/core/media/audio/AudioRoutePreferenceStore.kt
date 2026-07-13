package life.fxs.purr.core.media.audio

import android.content.Context
import life.fxs.purr.core.model.AudioRoute

interface AudioRoutePreferenceStore {
    fun load(): AudioRoute?

    fun save(route: AudioRoute)
}

class SharedPreferencesAudioRoutePreferenceStore(
    context: Context,
) : AudioRoutePreferenceStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): AudioRoute? = preferences.getString(PREFERRED_ROUTE_KEY, null)
        ?.let { stored -> AudioRoute.entries.firstOrNull { it.name == stored } }

    override fun save(route: AudioRoute) {
        preferences.edit().putString(PREFERRED_ROUTE_KEY, route.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "audio_route_preferences"
        const val PREFERRED_ROUTE_KEY = "preferred_audio_route"
    }
}
