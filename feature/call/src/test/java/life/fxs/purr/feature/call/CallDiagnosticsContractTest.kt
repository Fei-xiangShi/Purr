package life.fxs.purr.feature.call

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.CallUiSnapshot
import life.fxs.purr.domain.call.model.NetworkQuality
import life.fxs.purr.domain.call.model.ParticipantIdentity
import org.junit.Test

class CallDiagnosticsContractTest {
    @Test
    fun `diagnostics receives only its call state projection`() {
        val state = CallState(
            screenState = CallScreenState.Active,
            session = CallSession(
                callId = "call-1",
                pairId = "pair-1",
                participantIdentity = ParticipantIdentity(local = "local", remote = "remote"),
                roomName = "room-1",
                uiSnapshot = CallUiSnapshot(
                    networkQuality = NetworkQuality(uplinkScore = 4),
                ),
            ),
            activeRoute = AudioRoute.Bluetooth,
            isLoading = true,
        )

        assertThat(state.toDiagnosticsContext()).isEqualTo(
            CallDiagnosticsContext(
                screenState = CallScreenState.Active,
                activeRoute = AudioRoute.Bluetooth,
                networkQualityScore = 4,
                callId = "call-1",
            ),
        )
    }
}
