package life.fxs.purr.data.call.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.domain.call.model.CallOverlayStyle
import life.fxs.purr.domain.call.repository.CallOverlayStyleRepository

@Singleton
class SharedPreferencesCallOverlayStyleRepository @Inject constructor(
    @ApplicationContext context: Context,
) : CallOverlayStyleRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableStyle = MutableStateFlow(readStyle())

    override val style = mutableStyle.asStateFlow()

    override fun setStyle(style: CallOverlayStyle) {
        if (mutableStyle.value == style) return
        preferences.edit().putString(STYLE_KEY, style.name).apply()
        mutableStyle.value = style
    }

    private fun readStyle(): CallOverlayStyle = preferences.getString(STYLE_KEY, null)
        ?.let { saved -> CallOverlayStyle.entries.firstOrNull { it.name == saved } }
        ?: CallOverlayStyle.CompactSquare

    private companion object {
        const val PREFERENCES_NAME = "call_overlay_preferences"
        const val STYLE_KEY = "overlay_style"
    }
}
