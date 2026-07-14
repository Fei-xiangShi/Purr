package life.fxs.purr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import life.fxs.purr.feature.incomingcall.IncomingCallReminderCoordinator
import life.fxs.purr.realtime.RealtimeSessionCoordinator
import life.fxs.purr.data.call.telemetry.CallTelemetryCoordinator

@HiltAndroidApp
class PurrApplication : Application() {
    @Inject
    lateinit var incomingCallReminderCoordinator: IncomingCallReminderCoordinator

    @Inject
    lateinit var realtimeSessionCoordinator: RealtimeSessionCoordinator

    @Inject
    lateinit var callTelemetryCoordinator: CallTelemetryCoordinator

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
    }
}
