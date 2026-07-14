package life.fxs.purr.overlay

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CallOverlayVisibility(
    val isApplicationForeground: Boolean = false,
    val isCallSurfaceVisible: Boolean = false,
) {
    val shouldShowOverlay: Boolean
        get() = !isApplicationForeground || !isCallSurfaceVisible
}

@Singleton
class CallOverlayVisibilityStore @Inject constructor() {
    private val mutableState = MutableStateFlow(CallOverlayVisibility())
    val state = mutableState.asStateFlow()

    fun setApplicationForeground(isForeground: Boolean) {
        mutableState.update { it.copy(isApplicationForeground = isForeground) }
    }

    fun setCallSurfaceVisible(isVisible: Boolean) {
        mutableState.update { it.copy(isCallSurfaceVisible = isVisible) }
    }
}
