package life.fxs.purr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.data.account.repository.ApiAuthRepository
import life.fxs.purr.data.account.repository.ApiPairRepository
import life.fxs.purr.data.account.realtime.ApiRealtimeRepository
import life.fxs.purr.data.call.livekit.LiveKitCallDataSource
import life.fxs.purr.data.call.livekit.RealLiveKitCallDataSource
import life.fxs.purr.data.call.repository.CallRepositoryImpl
import life.fxs.purr.data.call.repository.RecordingRepositoryImpl
import life.fxs.purr.domain.account.repository.AuthRepository
import life.fxs.purr.domain.account.repository.PairRepository
import life.fxs.purr.domain.account.repository.RealtimeRepository
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.repository.RecordingRepository
import life.fxs.purr.service.AndroidCallServiceController

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: ApiAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindPairRepository(impl: ApiPairRepository): PairRepository

    @Binds
    @Singleton
    abstract fun bindRealtimeRepository(impl: ApiRealtimeRepository): RealtimeRepository

    @Binds
    @Singleton
    abstract fun bindLiveKitCallDataSource(impl: RealLiveKitCallDataSource): LiveKitCallDataSource

    @Binds
    @Singleton
    abstract fun bindCallServiceController(impl: AndroidCallServiceController): CallServiceController

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds
    @Singleton
    abstract fun bindRecordingRepository(impl: RecordingRepositoryImpl): RecordingRepository
}
