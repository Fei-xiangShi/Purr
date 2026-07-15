package life.fxs.purr.platform.incomingcall

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.feature.incomingcall.ApplicationVisibility
import life.fxs.purr.feature.incomingcall.IncomingCallReminder

@Module
@InstallIn(SingletonComponent::class)
internal abstract class IncomingCallPlatformModule {
    @Binds
    @Singleton
    abstract fun bindFullScreenIntentCapability(
        implementation: AndroidFullScreenIntentCapability,
    ): FullScreenIntentCapability

    @Binds
    @Singleton
    abstract fun bindApplicationVisibility(
        implementation: ProcessApplicationVisibility,
    ): ApplicationVisibility

    @Binds
    @Singleton
    abstract fun bindIncomingCallReminder(
        implementation: AndroidIncomingCallReminder,
    ): IncomingCallReminder
}
