package life.fxs.purr.feature.incomingcall

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class ProcessApplicationVisibility @Inject constructor() :
    ApplicationVisibility,
    DefaultLifecycleObserver {
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val foregroundState = MutableStateFlow(
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
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
