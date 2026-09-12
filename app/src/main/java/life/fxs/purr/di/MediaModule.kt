package life.fxs.purr.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import life.fxs.purr.core.common.ApplicationScope
import life.fxs.purr.core.media.audio.CallAudioSessionController
import life.fxs.purr.core.media.audio.TelecomManagedCallAudioSessionController
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.data.call.livekit.MutableCallRoomStateProvider
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider
import life.fxs.purr.core.media.screenshare.NativeWhepPlaybackController
import life.fxs.purr.core.media.screenshare.WhepPlaybackController
import life.fxs.purr.core.media.screenshare.ScreenShareDiagnosticsStore
import life.fxs.purr.core.media.screenshare.ScreenShareFailureReporter
import life.fxs.purr.diagnostics.PurrSentry
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides @Singleton
    fun provideScreenShareFailureReporter(): ScreenShareFailureReporter = PurrSentry
    @Provides
    @Singleton
    fun provideCallAudioSessionController(): CallAudioSessionController =
        TelecomManagedCallAudioSessionController()

    @Provides
    @Singleton
    fun provideCallRoomStateProvider(impl: MutableCallRoomStateProvider): CallRoomStateProvider = impl

    @Provides
    @Singleton
    fun provideCallAudioLevelProvider(impl: MutableCallAudioLevelProvider): CallAudioLevelProvider = impl

    @Provides
    @Singleton
    @MediaTransportClient
    fun provideMediaTransportClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWhepPlaybackController(
        @ApplicationContext context: Context,
        @MediaTransportClient okHttpClient: OkHttpClient,
        @ApplicationScope applicationScope: CoroutineScope,
        diagnostics: ScreenShareDiagnosticsStore,
        failures: ScreenShareFailureReporter,
    ): WhepPlaybackController = NativeWhepPlaybackController(
        context = context,
        okHttpClient = okHttpClient,
        applicationScope = applicationScope,
        diagnostics = diagnostics,
        failures = failures,
    )
}
