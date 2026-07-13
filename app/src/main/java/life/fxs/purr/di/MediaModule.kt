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
import life.fxs.purr.core.media.audio.AudioRoutePreferenceStore
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.audio.SharedPreferencesAudioRoutePreferenceStore
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.data.call.livekit.MutableCallRoomStateProvider
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider

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
        preferenceStore: AudioRoutePreferenceStore,
    ): AudioRouteController = AndroidAudioRouteController(context, audioManager, preferenceStore)

    @Provides
    @Singleton
    fun provideAudioRoutePreferenceStore(
        @ApplicationContext context: Context,
    ): AudioRoutePreferenceStore = SharedPreferencesAudioRoutePreferenceStore(context)

    @Provides
    @Singleton
    fun provideCallAudioFocusManager(audioManager: AudioManager): CallAudioFocusManager =
        AndroidCallAudioFocusManager(audioManager)

    @Provides
    @Singleton
    fun provideCallRoomStateProvider(impl: MutableCallRoomStateProvider): CallRoomStateProvider = impl

    @Provides
    @Singleton
    fun provideCallAudioLevelProvider(impl: MutableCallAudioLevelProvider): CallAudioLevelProvider = impl
}
