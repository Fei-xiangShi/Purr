package life.fxs.purr.platform.push

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushPlatformCoordinator @Inject internal constructor(
    private val runtime: PushRuntime,
    private val tokenCoordinator: FirebaseTokenCoordinator,
    private val registrationCoordinator: PushDeviceRegistrationCoordinator,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!runtime.initialize()) return
        registrationCoordinator.start()
        tokenCoordinator.start()
    }
}
