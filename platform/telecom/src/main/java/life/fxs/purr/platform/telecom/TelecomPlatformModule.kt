package life.fxs.purr.platform.telecom

import android.content.Context
import androidx.core.telecom.CallsManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.telecom.SystemCallController

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TelecomPlatformBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTelecomCallGateway(
        implementation: AndroidXTelecomCallGateway,
    ): TelecomCallGateway

    @Binds
    @Singleton
    abstract fun bindSystemCallController(
        implementation: CoreTelecomSystemCallController,
    ): SystemCallController

    @Binds
    @Singleton
    abstract fun bindAudioRouteController(
        implementation: CoreTelecomSystemCallController,
    ): AudioRouteController
}

@Module
@InstallIn(SingletonComponent::class)
internal object TelecomPlatformProvidersModule {
    @Provides
    @Singleton
    fun provideCallsManager(@ApplicationContext context: Context): CallsManager = CallsManager(context)
}
