package life.fxs.purr.platform.telecom

import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CoreTelecomSystemCallControllerTest {
    @Test
    fun `incoming activation answers while outgoing activation sets active`() = runTest {
        val incoming = harness()
        incoming.controller.startCall(descriptor(CallDirection.Incoming))

        incoming.controller.activateCall("call-1")

        assertThat(incoming.controlScope.answerTypes)
            .containsExactly(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        assertThat(incoming.controlScope.setActiveCount).isEqualTo(0)
        incoming.controller.disconnectCall("call-1")

        val outgoing = harness()
        outgoing.controller.startCall(descriptor(CallDirection.Outgoing))

        outgoing.controller.activateCall("call-1")

        assertThat(outgoing.controlScope.answerTypes).isEmpty()
        assertThat(outgoing.controlScope.setActiveCount).isEqualTo(1)
        outgoing.controller.disconnectCall("call-1")
    }

    @Test
    fun `system answer emits once and later activation does not answer twice`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Incoming))
        val event = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.events.first() }

        harness.gateway.callbacks!!.onAnswer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        harness.gateway.callbacks!!.onAnswer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        harness.controller.activateCall("call-1")

        assertThat(event.await()).isEqualTo(SystemCallEvent.AnswerRequested("call-1"))
        assertThat(harness.controlScope.answerTypes).isEmpty()
        harness.controller.disconnectCall("call-1")
    }

    @Test
    fun `set inactive requests domain teardown but still allows final Telecom disconnect`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        val event = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.events.first() }

        harness.gateway.callbacks!!.onSetInactive()

        assertThat(event.await()).isEqualTo(SystemCallEvent.DisconnectRequested("call-1"))
        harness.controller.disconnectCall("call-1")
        assertThat(harness.controlScope.disconnectCauses).hasSize(1)
        assertThat(harness.controlScope.disconnectCauses.single().code).isEqualTo(DisconnectCause.LOCAL)
    }

    @Test
    fun `Telecom disconnect request emits once and does not issue a second platform disconnect`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        val event = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.events.first() }

        harness.gateway.callbacks!!.onDisconnect(DisconnectCause(DisconnectCause.REMOTE))
        harness.gateway.finishCall()
        runCurrent()
        harness.controller.disconnectCall("call-1")

        assertThat(event.await()).isEqualTo(SystemCallEvent.DisconnectRequested("call-1"))
        assertThat(harness.controlScope.disconnectCauses).isEmpty()
    }

    @Test
    fun `new call selects earpiece only once when Telecom starts on speaker`() = runTest {
        val harness = harness(initialRoute = AudioRoute.Speaker)
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()

        harness.controlScope.emitAvailableEndpoints()
        runCurrent()

        assertThat(harness.controlScope.requestedEndpoints)
            .containsExactly(harness.controlScope.earpieceEndpoint)
        harness.controller.disconnectCall("call-1")
    }

    @Test
    fun `selecting the current route avoids a redundant endpoint request`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()

        harness.controller.selectRoute(AudioRoute.Earpiece)

        assertThat(harness.controlScope.requestedEndpoints).isEmpty()
        harness.controller.disconnectCall("call-1")
    }

    @Test
    fun `unexpected Telecom session closure requests domain teardown`() = runTest {
        val harness = harness(behavior = FakeTelecomCallGateway.Behavior.ReturnAfterReady)
        val event = async(start = CoroutineStart.UNDISPATCHED) { harness.controller.events.first() }

        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()

        assertThat(event.await()).isEqualTo(SystemCallEvent.DisconnectRequested("call-1"))
    }

    @Test
    fun `cancelled start cancels a Telecom operation that never became ready`() = runTest {
        val harness = harness(behavior = FakeTelecomCallGateway.Behavior.NeverReady)
        val start = async { harness.controller.startCall(descriptor(CallDirection.Outgoing)) }
        runCurrent()

        start.cancelAndJoin()
        runCurrent()

        assertThat(harness.gateway.cancelledBeforeReady.isCompleted).isTrue()
    }

    private fun TestScope.harness(
        initialRoute: AudioRoute = AudioRoute.Earpiece,
        behavior: FakeTelecomCallGateway.Behavior = FakeTelecomCallGateway.Behavior.HoldOpen,
    ): Harness {
        val controlScope = FakeCallControlScope(backgroundScope.coroutineContext, initialRoute)
        val gateway = FakeTelecomCallGateway(controlScope, behavior)
        controlScope.onDisconnect = gateway::finishCall
        return Harness(
            controller = CoreTelecomSystemCallController(
                callGateway = gateway,
                attributesFactory = TelecomCallAttributesFactory(),
                applicationScope = backgroundScope,
            ),
            gateway = gateway,
            controlScope = controlScope,
        )
    }

    private fun descriptor(direction: CallDirection) = SystemCallDescriptor(
        callId = "call-1",
        pairId = "pair-1",
        remoteDisplayName = "Partner",
        direction = direction,
    )
}

private data class Harness(
    val controller: CoreTelecomSystemCallController,
    val gateway: FakeTelecomCallGateway,
    val controlScope: FakeCallControlScope,
)

private class FakeTelecomCallGateway(
    private val controlScope: CallControlScope,
    private val behavior: Behavior,
) : TelecomCallGateway {
    enum class Behavior {
        HoldOpen,
        ReturnAfterReady,
        NeverReady,
    }

    private val callFinished = CompletableDeferred<Unit>()
    val cancelledBeforeReady = CompletableDeferred<Unit>()
    var callbacks: TelecomCallCallbacks? = null
        private set
    var registerCount: Int = 0
        private set

    override fun registerApp() {
        registerCount += 1
    }

    override suspend fun addCall(
        attributes: CallAttributesCompat,
        callbacks: TelecomCallCallbacks,
        onReady: (CallControlScope) -> Unit,
    ) {
        this.callbacks = callbacks
        when (behavior) {
            Behavior.HoldOpen -> {
                onReady(controlScope)
                callFinished.await()
            }
            Behavior.ReturnAfterReady -> onReady(controlScope)
            Behavior.NeverReady -> try {
                awaitCancellation()
            } finally {
                cancelledBeforeReady.complete(Unit)
            }
        }
    }

    fun finishCall() {
        callFinished.complete(Unit)
    }
}

private class FakeCallControlScope(
    override val coroutineContext: CoroutineContext,
    initialRoute: AudioRoute,
) : CallControlScope {
    val earpieceEndpoint = endpoint("Earpiece", CallEndpointCompat.TYPE_EARPIECE)
    val speakerEndpoint = endpoint("Speaker", CallEndpointCompat.TYPE_SPEAKER)
    private val currentEndpoint = MutableSharedFlow<CallEndpointCompat>(replay = 1).apply {
        tryEmit(if (initialRoute == AudioRoute.Speaker) speakerEndpoint else earpieceEndpoint)
    }
    private val endpoints = MutableSharedFlow<List<CallEndpointCompat>>(replay = 1).apply {
        tryEmit(listOf(earpieceEndpoint, speakerEndpoint))
    }

    override fun getCallId(): ParcelUuid = mockk()
    override val currentCallEndpoint: Flow<CallEndpointCompat> = currentEndpoint
    override val availableEndpoints: Flow<List<CallEndpointCompat>> = endpoints
    override val isMuted: Flow<Boolean> = MutableStateFlow(false)
    val answerTypes = mutableListOf<Int>()
    val disconnectCauses = mutableListOf<DisconnectCause>()
    val requestedEndpoints = mutableListOf<CallEndpointCompat>()
    var setActiveCount: Int = 0
        private set
    var onDisconnect: () -> Unit = {}

    override suspend fun setActive(): CallControlResult {
        setActiveCount += 1
        return CallControlResult.Success()
    }

    override suspend fun setInactive(): CallControlResult = CallControlResult.Success()

    override suspend fun answer(callType: Int): CallControlResult {
        answerTypes += callType
        return CallControlResult.Success()
    }

    override suspend fun disconnect(disconnectCause: DisconnectCause): CallControlResult {
        disconnectCauses += disconnectCause
        onDisconnect()
        return CallControlResult.Success()
    }

    override suspend fun requestEndpointChange(callEndpoint: CallEndpointCompat): CallControlResult {
        requestedEndpoints += callEndpoint
        return CallControlResult.Success()
    }

    fun emitAvailableEndpoints() {
        endpoints.tryEmit(listOf(earpieceEndpoint, speakerEndpoint))
    }

    private fun endpoint(name: String, type: Int) = CallEndpointCompat(name, type, mockk())
}
