package life.fxs.purr.feature.call

import life.fxs.purr.core.model.AudioRoute

/** The smallest call projection required by the diagnostics presentation. */
internal data class CallDiagnosticsContext(
    val screenState: CallScreenState,
    val activeRoute: AudioRoute,
    val networkQualityScore: Int?,
)

internal fun CallState.toDiagnosticsContext(): CallDiagnosticsContext = CallDiagnosticsContext(
    screenState = screenState,
    activeRoute = activeRoute,
    networkQualityScore = session?.uiSnapshot?.networkQuality?.uplinkScore,
)
