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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.NoOpPurrLogger
import life.fxs.purr.core.media.telecom.SystemCallDescriptor
import life.fxs.purr.core.media.telecom.SystemCallEvent
import life.fxs.purr.core.media.telecom.SystemCallInterruptionDispatcher
import life.fxs.purr.core.media.telecom.SystemCallInterruptionHandler
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.core.model.CallDirection
import life.fxs.purr.core.model.SystemCallInterruptionRequest
import life.fxs.purr.core.model.SystemCallInterruptionResult
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
    fun `inactive and active callbacks dispatch one paired interruption operation`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))

        harness.gateway.callbacks!!.onSetInactive()
        harness.gateway.callbacks!!.onSetActive()

        assertThat(harness.interruptionHandler.inactiveRequests).hasSize(1)
        assertThat(harness.interruptionHandler.activeRequests).hasSize(1)
        assertThat(harness.interruptionHandler.activeRequests.single().operationId)
            .isEqualTo(harness.interruptionHandler.inactiveRequests.single().operationId)
        assertThat(harness.interruptionHandler.activeRequests.single().telecomSequence)
            .isGreaterThan(harness.interruptionHandler.inactiveRequests.single().telecomSequence)
        harness.controller.disconnectCall("call-1")
        assertThat(harness.controlScope.disconnectCauses).hasSize(1)
        assertThat(harness.controlScope.disconnectCauses.single().code).isEqualTo(DisconnectCause.LOCAL)
    }

    @Test
    fun `callback timeout stops waiting but application interruption operation continues`() = runTest {
        val cutoffStarted = CompletableDeferred<Unit>()
        val releaseCutoff = CompletableDeferred<Unit>()
        val harness = harness(
            interruptionHandler = RecordingInterruptionHandler(
                onInactive = {
                    cutoffStarted.complete(Unit)
                    releaseCutoff.await()
                    SystemCallInterruptionResult.Applied
                },
            ),
        )
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        val callback = async { harness.gateway.callbacks!!.onSetInactive() }
        runCurrent()
        cutoffStarted.await()

        advanceTimeBy(4_000L)
        runCurrent()

        assertThat(callback.isCompleted).isTrue()
        assertThat(harness.interruptionHandler.inactiveCompletions).isEqualTo(0)

        releaseCutoff.complete(Unit)
        runCurrent()

        assertThat(harness.interruptionHandler.inactiveCompletions).isEqualTo(1)
        harness.controller.disconnectCall("call-1")
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
    fun `successful route request updates active route before Telecom callback`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()

        harness.controller.selectRoute(AudioRoute.Speaker)

        assertThat(harness.controlScope.requestedEndpoints)
            .containsExactly(harness.controlScope.speakerEndpoint)
        assertThat(harness.controller.activeRoute.value).isEqualTo(AudioRoute.Speaker)
        harness.controller.disconnectCall("call-1")
    }

    @Test
    fun `stale endpoint callback does not overwrite a newer route request`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()

        harness.controller.selectRoute(AudioRoute.Speaker)
        harness.controller.selectRoute(AudioRoute.Earpiece)
        harness.controlScope.emitCurrentEndpoint(AudioRoute.Speaker)
        runCurrent()

        assertThat(harness.controlScope.requestedEndpoints).containsExactly(
            harness.controlScope.speakerEndpoint,
            harness.controlScope.earpieceEndpoint,
        ).inOrder()
        assertThat(harness.controller.activeRoute.value).isEqualTo(AudioRoute.Earpiece)

        harness.controlScope.emitCurrentEndpoint(AudioRoute.Earpiece)
        runCurrent()

        assertThat(harness.controller.activeRoute.value).isEqualTo(AudioRoute.Earpiece)
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

    @Test
    fun `different call cannot reuse an active Telecom session`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        val attempt = runCatching {
            harness.controller.startCall(descriptor(CallDirection.Outgoing).copy(callId = "call-2"))
        }
        assertThat(attempt.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(harness.gateway.registerCount).isEqualTo(1)
        harness.controller.disconnectCall("call-1")
    }

    @Test
    fun `different call cannot reuse a pending Telecom session`() = runTest {
        val harness = harness(behavior = FakeTelecomCallGateway.Behavior.NeverReady)
        val pending = async { harness.controller.startCall(descriptor(CallDirection.Outgoing)) }
        runCurrent()
        val attempt = runCatching {
            harness.controller.startCall(descriptor(CallDirection.Outgoing).copy(callId = "call-2"))
        }
        assertThat(attempt.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(harness.gateway.registerCount).isEqualTo(1)
        pending.cancelAndJoin()
    }

    @Test
    fun `detached Telecom endpoint events do not restore the previous call route`() = runTest {
        val harness = harness()
        harness.controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()
        harness.controlScope.onDisconnect = {}
        harness.controller.disconnectCall("call-1")
        assertThat(harness.controller.activeRoute.value).isEqualTo(AudioRoute.Earpiece)
        harness.controlScope.emitCurrentEndpoint(AudioRoute.Speaker)
        runCurrent()
        assertThat(harness.controller.activeRoute.value).isEqualTo(AudioRoute.Earpiece)
        harness.gateway.finishCall()
    }

    @Test
    fun `explicit disconnect cancels the matching pending Telecom call`() = runTest {
        val harness = harness(behavior = FakeTelecomCallGateway.Behavior.NeverReady)
        val pending = async { harness.controller.startCall(descriptor(CallDirection.Incoming)) }
        runCurrent()
        harness.controller.disconnectCall("call-1")
        runCurrent()
        assertThat(pending.isCancelled).isTrue()
        assertThat(harness.gateway.cancelledBeforeReady.isCompleted).isTrue()
    }

    @Test
    fun `late old Telecom closure preserves the replacement route`() = runTest {
        val firstScope = FakeCallControlScope(backgroundScope.coroutineContext, AudioRoute.Earpiece)
        val secondScope = FakeCallControlScope(backgroundScope.coroutineContext, AudioRoute.Speaker)
        val scopes = listOf(firstScope, secondScope)
        val closures = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var next = 0
        val gateway = object : TelecomCallGateway {
            override fun registerApp() = Unit
            override suspend fun addCall(
                attributes: CallAttributesCompat,
                callbacks: TelecomCallCallbacks,
                onReady: (CallControlScope) -> Unit,
            ) {
                val index = next++
                onReady(scopes[index])
                closures[index].await()
            }
        }
        val controller = CoreTelecomSystemCallController(
            callGateway = gateway,
            attributesFactory = TelecomCallAttributesFactory(),
            interruptionDispatcher = SystemCallInterruptionDispatcher().apply {
                register(RecordingInterruptionHandler())
            },
            logger = NoOpPurrLogger,
            applicationScope = backgroundScope,
        )
        controller.startCall(descriptor(CallDirection.Outgoing))
        runCurrent()
        controller.disconnectCall("call-1")
        controller.startCall(descriptor(CallDirection.Outgoing).copy(callId = "call-2"))
        runCurrent()
        controller.selectRoute(AudioRoute.Speaker)
        assertThat(controller.activeRoute.value).isEqualTo(AudioRoute.Speaker)
        closures[0].complete(Unit)
        runCurrent()
        assertThat(controller.activeRoute.value).isEqualTo(AudioRoute.Speaker)
        controller.disconnectCall("call-2")
        closures[1].complete(Unit)
    }

    private fun TestScope.harness(
        initialRoute: AudioRoute = AudioRoute.Earpiece,
        behavior: FakeTelecomCallGateway.Behavior = FakeTelecomCallGateway.Behavior.HoldOpen,
        interruptionHandler: RecordingInterruptionHandler = RecordingInterruptionHandler(),
    ): Harness {
        val controlScope = FakeCallControlScope(backgroundScope.coroutineContext, initialRoute)
        val gateway = FakeTelecomCallGateway(controlScope, behavior)
        val interruptionDispatcher = SystemCallInterruptionDispatcher().apply {
            register(interruptionHandler)
        }
        controlScope.onDisconnect = gateway::finishCall
        return Harness(
            controller = CoreTelecomSystemCallController(
                callGateway = gateway,
                attributesFactory = TelecomCallAttributesFactory(),
                interruptionDispatcher = interruptionDispatcher,
                logger = NoOpPurrLogger,
                applicationScope = backgroundScope,
            ),
            gateway = gateway,
            controlScope = controlScope,
            interruptionHandler = interruptionHandler,
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
    val interruptionHandler: RecordingInterruptionHandler,
)

private class RecordingInterruptionHandler(
    private val onInactive: suspend (SystemCallInterruptionRequest) -> SystemCallInterruptionResult = {
        SystemCallInterruptionResult.Applied
    },
    private val onActive: suspend (SystemCallInterruptionRequest) -> SystemCallInterruptionResult = {
        SystemCallInterruptionResult.Applied
    },
) : SystemCallInterruptionHandler {
    val inactiveRequests = mutableListOf<SystemCallInterruptionRequest>()
    val activeRequests = mutableListOf<SystemCallInterruptionRequest>()
    var inactiveCompletions: Int = 0
        private set

    override suspend fun onSetInactive(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult {
        inactiveRequests += request
        return onInactive(request).also { inactiveCompletions += 1 }
    }

    override suspend fun onSetActive(
        request: SystemCallInterruptionRequest,
    ): SystemCallInterruptionResult {
        activeRequests += request
        return onActive(request)
    }
}

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

    fun emitCurrentEndpoint(route: AudioRoute) {
        currentEndpoint.tryEmit(
            when (route) {
                AudioRoute.Earpiece -> earpieceEndpoint
                AudioRoute.Speaker -> speakerEndpoint
                else -> error("Unsupported fake route: $route")
            },
        )
    }

    private fun endpoint(name: String, type: Int) = CallEndpointCompat(name, type, mockk())
}
