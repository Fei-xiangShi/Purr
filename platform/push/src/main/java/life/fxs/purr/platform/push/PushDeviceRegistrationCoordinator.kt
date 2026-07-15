package life.fxs.purr.platform.push

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.usecase.ObserveAuthSessionUseCase
import life.fxs.purr.domain.account.usecase.RegisterPushInstallationUseCase

@Singleton
internal class PushDeviceRegistrationCoordinator @Inject constructor(
    private val observeAuthSession: ObserveAuthSessionUseCase,
    private val tokenStore: PushTokenStore,
    private val installationIdStore: InstallationIdStore,
    private val registerInstallation: RegisterPushInstallationUseCase,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            combine(observeAuthSession(), tokenStore.observe(), ::registrationTarget)
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target != null) synchronizeUntilSuccessful(target)
                }
        }
    }

    private fun registrationTarget(session: AuthSession?, token: String?): RegistrationTarget? {
        if (session == null || token == null) return null
        return RegistrationTarget(
            sessionFingerprint = session.refreshToken,
            installationId = installationIdStore.getOrCreate(),
            token = token,
        )
    }

    private suspend fun synchronizeUntilSuccessful(target: RegistrationTarget) {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            when (registerInstallation(target.installationId, target.token)) {
                is AppResult.Success -> return
                is AppResult.Failure -> delay(retryDelayMillis)
            }
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
    }

    private data class RegistrationTarget(
        val sessionFingerprint: String,
        val installationId: String,
        val token: String,
    )

    private companion object {
        const val INITIAL_RETRY_DELAY_MILLIS = 2_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L
    }
}
