package life.fxs.purr.platform.incomingcall

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.feature.incomingcall.ApplicationVisibility

@Singleton
internal class ProcessApplicationVisibility @Inject constructor() :
    ApplicationVisibility,
    DefaultLifecycleObserver {
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val foregroundState = MutableStateFlow(
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )

    override val isForeground = foregroundState.asStateFlow()

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        foregroundState.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundState.value = false
    }
}
