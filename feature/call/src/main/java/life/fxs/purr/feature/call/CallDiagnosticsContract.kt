package life.fxs.purr.feature.call

import life.fxs.purr.core.model.AudioRoute

/** The smallest call projection required by the diagnostics presentation. */
internal data class CallDiagnosticsContext(
    val screenState: CallScreenState,
    val activeRoute: AudioRoute,
    val networkQualityScore: Int?,
    val callId: String? = null,
    val shareId: String? = null,
    val shareSource: String? = null,
    val shareStatus: String? = null,
)

internal fun CallState.toDiagnosticsContext(): CallDiagnosticsContext = CallDiagnosticsContext(
    screenState = screenState,
    activeRoute = activeRoute,
    networkQualityScore = session?.uiSnapshot?.networkQuality?.uplinkScore,
    callId = session?.callId,
    shareId = screenShare.session?.shareId,
    shareSource = screenShare.session?.source?.wireValue,
    shareStatus = screenShare.session?.status?.wireValue,
)
