package life.fxs.purr.feature.incomingcall

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class IncomingCallReminderModule {
    @Binds
    @Singleton
    abstract fun bindIncomingCallSource(implementation: DomainIncomingCallSource): IncomingCallSource

    @Binds
    @Singleton
    abstract fun bindIncomingCallCallerSource(
        implementation: DomainIncomingCallCallerSource,
    ): IncomingCallCallerSource
}
