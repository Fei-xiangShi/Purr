package life.fxs.purr

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import life.fxs.purr.domain.incomingcall.IncomingCallReminderCoordinator
import life.fxs.purr.realtime.RealtimeSessionCoordinator
import life.fxs.purr.data.call.telemetry.CallTelemetryCoordinator
import life.fxs.purr.platform.push.PushPlatformCoordinator
import life.fxs.purr.telecom.IncomingSystemCallLifecycleCoordinator
import life.fxs.purr.telecom.SystemCallEventCoordinator

import life.fxs.purr.screenshare.ScreenShareCallLifecycleCoordinator
@HiltAndroidApp
class PurrApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var incomingCallReminderCoordinator: IncomingCallReminderCoordinator

    @Inject
    lateinit var realtimeSessionCoordinator: RealtimeSessionCoordinator

    @Inject
    lateinit var callTelemetryCoordinator: CallTelemetryCoordinator

    @Inject
    lateinit var pushPlatformCoordinator: PushPlatformCoordinator

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var systemCallEventCoordinator: SystemCallEventCoordinator

    @Inject
    lateinit var incomingSystemCallLifecycleCoordinator: IncomingSystemCallLifecycleCoordinator

    @Inject
    lateinit var screenShareCallLifecycleCoordinator: ScreenShareCallLifecycleCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().apply {
            if (::workerFactory.isInitialized) setWorkerFactory(workerFactory)
        }.build()

    override fun onCreate() {
        super.onCreate()
        // Plain Robolectric service tests create the application without a Hilt test component.
        // Android production startup always injects this field before Application.onCreate.
        if (::realtimeSessionCoordinator.isInitialized) {
            realtimeSessionCoordinator.start()
        }
        if (::incomingCallReminderCoordinator.isInitialized) {
            incomingCallReminderCoordinator.start()
        }
        if (::callTelemetryCoordinator.isInitialized) {
            callTelemetryCoordinator.start()
        }
        if (::pushPlatformCoordinator.isInitialized) {
            pushPlatformCoordinator.start()
        }
        if (::systemCallEventCoordinator.isInitialized) {
            systemCallEventCoordinator.start()
        }
        if (::incomingSystemCallLifecycleCoordinator.isInitialized) {
            incomingSystemCallLifecycleCoordinator.start()
        }
        if (::screenShareCallLifecycleCoordinator.isInitialized) {
            screenShareCallLifecycleCoordinator.start()
        }
    }
}
