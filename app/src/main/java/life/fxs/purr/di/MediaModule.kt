package life.fxs.purr.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.core.media.audio.CallAudioSessionController
import life.fxs.purr.core.media.audio.TelecomManagedCallAudioSessionController
import life.fxs.purr.data.call.audio.MutableCallAudioLevelProvider
import life.fxs.purr.data.call.livekit.CallRoomStateProvider
import life.fxs.purr.data.call.livekit.MutableCallRoomStateProvider
import life.fxs.purr.domain.call.repository.CallAudioLevelProvider

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
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
}
