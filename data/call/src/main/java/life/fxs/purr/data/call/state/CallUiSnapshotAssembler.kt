package life.fxs.purr.data.call.state

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession

@Singleton
class CallUiSnapshotAssembler @Inject constructor(
    private val audioRouteController: AudioRouteController,
    private val callServiceController: CallServiceController,
) {
    val runtimeState: Flow<CallUiRuntimeState> = combine(
        audioRouteController.availableRoutes,
        audioRouteController.activeRoute,
        callServiceController.foregroundState,
    ) { availableRoutes, activeRoute, foregroundState ->
        CallUiRuntimeState(
            availableAudioRoutes = availableRoutes,
            activeAudioRoute = activeRoute,
            isForegroundServiceActive = foregroundState.isActive,
        )
    }.distinctUntilChanged()

    fun assemble(session: CallSession): CallSession = assemble(
        session = session,
        runtimeState = CallUiRuntimeState(
            availableAudioRoutes = audioRouteController.availableRoutes.value,
            activeAudioRoute = audioRouteController.activeRoute.value,
            isForegroundServiceActive = callServiceController.foregroundState.value.isActive,
        ),
    )

    fun assemble(session: CallSession, runtimeState: CallUiRuntimeState): CallSession = session.copy(
        uiSnapshot = session.uiSnapshot.copy(
            activeAudioRoute = runtimeState.activeAudioRoute,
            availableAudioRoutes = runtimeState.availableAudioRoutes,
            isForegroundServiceActive = runtimeState.isForegroundServiceActive,
        ),
    )
}

data class CallUiRuntimeState(
    val availableAudioRoutes: List<AudioRoute>,
    val activeAudioRoute: AudioRoute,
    val isForegroundServiceActive: Boolean,
)
