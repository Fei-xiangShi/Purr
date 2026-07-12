package life.fxs.purr.di

import android.content.Context
import android.media.AudioManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.core.media.audio.AndroidAudioRouteController
import life.fxs.purr.core.media.audio.AndroidCallAudioFocusManager
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.core.media.livekit.MutableCallRoomStateProvider

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideAudioManager(
        @ApplicationContext context: Context,
    ): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Provides
    @Singleton
    fun provideAudioRouteController(
        @ApplicationContext context: Context,
        audioManager: AudioManager,
    ): AudioRouteController = AndroidAudioRouteController(context, audioManager)

    @Provides
    @Singleton
    fun provideCallAudioFocusManager(audioManager: AudioManager): CallAudioFocusManager =
        AndroidCallAudioFocusManager(audioManager)

    @Provides
    @Singleton
    fun provideCallRoomStateProvider(impl: MutableCallRoomStateProvider): CallRoomStateProvider = impl
}
