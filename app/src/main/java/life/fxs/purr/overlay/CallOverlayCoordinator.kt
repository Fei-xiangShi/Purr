package life.fxs.purr.overlay

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.domain.call.usecase.ObserveCallOverlayStyleUseCase
import life.fxs.purr.domain.call.usecase.ObserveCallStateUseCase

@Singleton
@OptIn(FlowPreview::class)
class CallOverlayCoordinator @Inject constructor(
    private val observeCallState: ObserveCallStateUseCase,
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val observePairBond: ObservePairBondUseCase,
    private val observeOverlayStyle: ObserveCallOverlayStyleUseCase,
    private val audioLevels: CallAudioLevelProvider,
    private val visibilityStore: CallOverlayVisibilityStore,
    private val presenter: CallOverlayPresenter,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var renderingJob: Job? = null

    @Synchronized
    fun start() {
        renderingJob?.cancel()
        val base = combine(
            observeCallState(),
            observeAuthSession(),
            observePairBond(),
            observeOverlayStyle(),
            visibilityStore.state,
        ) { session, auth, bond, style, visibility ->
            OverlayBase(
                session = session,
                pairId = session?.pairId,
                localName = auth?.self?.displayName ?: "我",
                localAvatarUrl = auth?.self?.avatarUrl,
                remoteName = bond?.partner?.displayName ?: "对方",
                remoteAvatarUrl = bond?.partner?.avatarUrl,
                style = style,
                shouldShow = visibility.shouldShowOverlay && session.isOverlayActive(),
            )
        }
        renderingJob = applicationScope.launch {
            combine(
                base,
                audioLevels.localAudioLevel,
                audioLevels.remoteAudioLevel,
                durationTicker(),
            ) { overlay, localLevel, remoteLevel, nowMillis ->
                if (!overlay.shouldShow || overlay.session == null) return@combine null
                val pairId = overlay.pairId?.takeIf(String::isNotBlank) ?: return@combine null
                CallOverlayRenderModel(
                    callId = overlay.session.callId,
                    direction = overlay.session.direction,
                    pairId = pairId,
                    style = overlay.style,
                    localName = overlay.localName,
                    localAvatarUrl = overlay.localAvatarUrl,
                    remoteName = overlay.remoteName,
                    remoteAvatarUrl = overlay.remoteAvatarUrl,
                    durationSeconds = overlay.session.timing
                        .durationAtMonotonicMillis(nowMillis)
                        .div(1_000L),
                    localAudioLevel = localLevel,
                    remoteAudioLevel = remoteLevel,
                )
            }
                .sample(RENDER_INTERVAL_MILLIS)
                .collect { model ->
                    withContext(Dispatchers.Main.immediate) {
                        if (model == null) presenter.hide() else presenter.render(model)
                    }
                }
        }
    }

    @Synchronized
    fun stop() {
        renderingJob?.cancel()
        renderingJob = null
        applicationScope.launch(Dispatchers.Main.immediate) { presenter.hide() }
    }

    private fun durationTicker() = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.nanoTime() / 1_000_000L)
            delay(DURATION_TICK_MILLIS)
        }
    }

    private companion object {
        const val RENDER_INTERVAL_MILLIS = 50L
        const val DURATION_TICK_MILLIS = 250L
    }
}

private data class OverlayBase(
    val session: CallSession?,
    val pairId: String?,
    val localName: String,
    val localAvatarUrl: String?,
    val remoteName: String,
    val remoteAvatarUrl: String?,
    val style: life.fxs.purr.domain.call.model.CallOverlayStyle,
    val shouldShow: Boolean,
)

private fun CallSession?.isOverlayActive(): Boolean =
    this?.connectionState?.isResumable == true
