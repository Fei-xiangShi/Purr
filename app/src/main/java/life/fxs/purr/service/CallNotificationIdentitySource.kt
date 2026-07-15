package life.fxs.purr.service

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase

internal data class CallNotificationIdentity(
    val displayName: String,
    val avatarUrl: String?,
)

internal interface CallNotificationIdentitySource {
    fun observe(pairId: String): Flow<CallNotificationIdentity?>
}

@Singleton
internal class PairBondCallNotificationIdentitySource @Inject constructor(
    private val observePairBond: ObservePairBondUseCase,
) : CallNotificationIdentitySource {
    override fun observe(pairId: String): Flow<CallNotificationIdentity?> =
        observePairBond()
            .map { bond ->
                bond
                    ?.takeIf { it.pairId == pairId }
                    ?.partner
                    ?.let { partner ->
                        CallNotificationIdentity(
                            displayName = partner.displayName,
                            avatarUrl = partner.avatarUrl,
                        )
                    }
            }
            .distinctUntilChanged()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CallNotificationIdentityModule {
    @Binds
    @Singleton
    abstract fun bindCallNotificationIdentitySource(
        implementation: PairBondCallNotificationIdentitySource,
    ): CallNotificationIdentitySource
}
