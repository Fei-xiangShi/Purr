package life.fxs.purr.service

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.service.ForegroundCallServiceState
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.model.CallSession
import life.fxs.purr.domain.call.model.PrepareCallParams
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.usecase.DisconnectCallUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the real Android Service command boundary on API levels supported by the app.
 * Hilt normally supplies these fields; the test injects the narrow production dependencies
 * after Robolectric attaches the Service so that no network graph is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = Application::class)
class CallForegroundServiceLifecycleRobolectricTest {
    private lateinit var repository: RecordingCallRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        repository = RecordingCallRepository()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `real start command publishes running state`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)

        val result = service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-1", pairId = "pair-1"),
            0,
            1,
        )

        assertThat(result).isEqualTo(android.app.Service.START_NOT_STICKY)
        assertThat(stateStore.state.value).isEqualTo(ForegroundCallServiceState("call-1"))
    }

    @Test
    fun `duplicate start for the same call is idempotent`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)

        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-1", pairId = "pair-1"),
            0,
            1,
        )
        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-1", pairId = "pair-1"),
            0,
            2,
        )

        assertThat(stateStore.state.value.activeCallId).isEqualTo("call-1")
        assertThat(repository.disconnectedCallIds).isEmpty()
    }

    @Test
    fun `stale start cannot replace the current notification owner`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)
        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-new", pairId = "pair-1"),
            0,
            1,
        )

        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-old", pairId = "pair-1"),
            0,
            2,
        )

        assertThat(stateStore.state.value.activeCallId).isEqualTo("call-new")
        assertThat(repository.disconnectedCallIds).isEmpty()
    }

    @Test
    fun `task removal leaves the foreground call owned by the service`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)
        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-1", pairId = "pair-1"),
            0,
            1,
        )

        service.onTaskRemoved(CallForegroundService.intent(service, callId = "call-1"))

        assertThat(stateStore.state.value.activeCallId).isEqualTo("call-1")
        assertThat(repository.disconnectedCallIds).isEmpty()
    }

    @Test
    fun `manifest keeps the microphone service alive when the app task is removed`() {
        val application = RuntimeEnvironment.getApplication()
        val serviceInfo = application.packageManager.getServiceInfo(
            ComponentName(application, CallForegroundService::class.java),
            0,
        )

        assertThat(serviceInfo.flags and ServiceInfo.FLAG_STOP_WITH_TASK).isEqualTo(0)
        assertThat(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            .isNotEqualTo(0)
    }

    @Test
    fun `stale hang up cannot disconnect a newer call`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)
        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-new", pairId = "pair-1"),
            0,
            1,
        )

        val staleHangUp = CallForegroundService.intent(service, callId = "call-old", pairId = "pair-1")
            .setAction(CallForegroundService.ACTION_HANG_UP)
        service.onStartCommand(staleHangUp, 0, 2)

        assertThat(repository.disconnectedCallIds).isEmpty()
        assertThat(stateStore.state.value.activeCallId).isEqualTo("call-new")

        service.onDestroy()

        assertThat(repository.disconnectedCallIds).containsExactly("call-new")
        assertThat(stateStore.state.value.isActive).isFalse()
    }

    @Test
    fun `destroy clears state and performs call scoped cleanup`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<CallForegroundService>(stateStore)
        service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-1", pairId = "pair-1"),
            0,
            1,
        )

        service.onDestroy()

        assertThat(stateStore.state.value.isActive).isFalse()
        assertThat(repository.disconnectedCallIds).containsExactly("call-1")
    }

    @Test
    fun `destroy uses the shared state when the Service instance lost its local call id`() {
        val stateStore = CallForegroundServiceStateStore()
        stateStore.markStarted("call-rehydrated")
        val service = attachService<CallForegroundService>(stateStore)

        service.onDestroy()

        assertThat(stateStore.state.value.isActive).isFalse()
        assertThat(repository.disconnectedCallIds).containsExactly("call-rehydrated")
    }

    @Test
    fun `foreground permission failure is contained and leaves no running state`() {
        val stateStore = CallForegroundServiceStateStore()
        val service = attachService<SecurityExceptionService>(stateStore)

        val result = service.onStartCommand(
            CallForegroundService.intent(service, callId = "call-denied", pairId = "pair-1"),
            0,
            1,
        )

        assertThat(result).isEqualTo(android.app.Service.START_NOT_STICKY)
        assertThat(stateStore.state.value.isActive).isFalse()
        assertThat(repository.disconnectedCallIds).isEmpty()
    }

    @Test
    fun `controller waits for the attached service lifecycle after dispatching start`() = runBlocking {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val stateStore = CallForegroundServiceStateStore()
        val controller = AndroidCallServiceController(context, stateStore)

        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.startForegroundCall(callId = "call-1", pairId = "pair-1")
        }

        val startedIntent = context.startedForegroundService
        assertThat(startedIntent).isNotNull()
        assertThat(startedIntent?.getStringExtra(CallForegroundService.EXTRA_CALL_ID))
            .isEqualTo("call-1")
        assertThat(startedIntent?.getStringExtra(CallForegroundService.EXTRA_PAIR_ID))
            .isEqualTo("pair-1")
        assertThat(start.isCompleted).isFalse()

        stateStore.markStarted("call-1")
        start.join()
    }

    @Test
    fun `controller does not stop a service owned by another call`() = runBlocking {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val stateStore = CallForegroundServiceStateStore()
        stateStore.markStarted("call-new")
        val controller = AndroidCallServiceController(context, stateStore)

        controller.stopForegroundCall(expectedCallId = "call-old")

        assertThat(context.stoppedService).isNull()
        assertThat(stateStore.state.value.activeCallId).isEqualTo("call-new")
    }

    private inline fun <reified T : CallForegroundService> attachService(
        stateStore: CallForegroundServiceStateStore,
    ): T {
        val service = Robolectric.buildService(T::class.java).get()
        inject(service, "disconnectCallUseCase", DisconnectCallUseCase(repository))
        inject(service, "applicationScope", scope)
        inject(service, "stateStore", stateStore)
        return service
    }

    private fun inject(target: Any, fieldName: String, value: Any) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(fieldName).apply {
                    isAccessible = true
                    set(target, value)
                }
                return
            } catch (_: NoSuchFieldException) {
                // Hilt puts the injected fields on the generated superclass in some builds.
            }
            type = type.superclass
        }
        error("Unable to inject $fieldName into ${target.javaClass.name}")
    }

    class SecurityExceptionService : CallForegroundService() {
        override fun enterForeground(notification: Notification): Unit =
            throw SecurityException("microphone foreground service permission denied")
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedForegroundService: Intent? = null
        var stoppedService: Intent? = null

        override fun startForegroundService(service: Intent): ComponentName? {
            startedForegroundService = service
            return ComponentName(this, CallForegroundService::class.java)
        }

        override fun stopService(name: Intent): Boolean {
            stoppedService = name
            return true
        }
    }

    private class RecordingCallRepository : CallRepository {
        private val session = MutableStateFlow<CallSession?>(null)
        val disconnectedCallIds = mutableListOf<String?>()

        override fun observeCallSession(): Flow<CallSession?> = session.asStateFlow()

        override suspend fun prepareCall(params: PrepareCallParams): AppResult<CallSession> =
            error("Not used by this lifecycle test")

        override suspend fun connectCall(): AppResult<Unit> =
            error("Not used by this lifecycle test")

        override suspend fun disconnectCall(expectedCallId: String?): AppResult<Unit> {
            disconnectedCallIds += expectedCallId
            return AppResult.Success(Unit)
        }

        override suspend fun setMuted(muted: Boolean): AppResult<Unit> =
            error("Not used by this lifecycle test")

        override suspend fun selectAudioRoute(route: AudioRoute): AppResult<Unit> =
            error("Not used by this lifecycle test")
    }
}
