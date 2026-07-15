package life.fxs.purr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.data.account.repository.ApiAccountSecurityRepository
import life.fxs.purr.data.account.repository.ApiAuthRepository
import life.fxs.purr.data.account.repository.ApiProfileRepository
import life.fxs.purr.data.account.repository.ApiPairRepository
import life.fxs.purr.data.account.repository.ApiPushRegistrationRepository
import life.fxs.purr.data.account.realtime.ApiRealtimeRepository
import life.fxs.purr.data.call.livekit.LiveKitCallDataSource
import life.fxs.purr.data.call.livekit.RealLiveKitCallDataSource
import life.fxs.purr.data.call.repository.CallRepositoryImpl
import life.fxs.purr.data.call.runtime.CallRuntimeControllerImpl
import life.fxs.purr.data.call.runtime.CallRuntimeController
import life.fxs.purr.data.call.runtime.MediaCallPort
import life.fxs.purr.data.call.repository.ApiCallHistoryRepository
import life.fxs.purr.data.call.repository.ApiCallCalendarRepository
import life.fxs.purr.data.call.repository.ApiCallDetailRepository
import life.fxs.purr.data.call.repository.UnavailableTranscriptionRepository
import life.fxs.purr.data.call.repository.ApiCallTelemetryRepository
import life.fxs.purr.data.call.repository.CallDiagnosticsRepositoryImpl
import life.fxs.purr.data.call.repository.RecordingRepositoryImpl
import life.fxs.purr.data.call.remote.ApiCallStatusRemoteDataSource
import life.fxs.purr.data.call.remote.CallStatusRemoteDataSource
import life.fxs.purr.data.call.preferences.SharedPreferencesCallOverlayStyleRepository
import life.fxs.purr.domain.account.repository.AuthRepository
import life.fxs.purr.domain.account.repository.AccountSecurityRepository
import life.fxs.purr.domain.account.repository.ProfileRepository
import life.fxs.purr.domain.account.repository.PairRepository
import life.fxs.purr.domain.account.repository.RealtimeRepository
import life.fxs.purr.domain.account.repository.PushRegistrationRepository
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.domain.call.repository.CallHistoryRepository
import life.fxs.purr.domain.call.repository.CallCalendarRepository
import life.fxs.purr.domain.call.repository.CallDetailRepository
import life.fxs.purr.domain.call.repository.TranscriptionRepository
import life.fxs.purr.domain.call.repository.CallTelemetryRepository
import life.fxs.purr.domain.call.repository.CallDiagnosticsRepository
import life.fxs.purr.domain.call.repository.RecordingRepository
import life.fxs.purr.domain.call.repository.CallOverlayStyleRepository
import life.fxs.purr.service.AndroidCallServiceController

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindAccountSecurityRepository(impl: ApiAccountSecurityRepository): AccountSecurityRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ApiProfileRepository): ProfileRepository

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
    abstract fun bindPushRegistrationRepository(
        impl: ApiPushRegistrationRepository,
    ): PushRegistrationRepository

    @Binds
    @Singleton
    abstract fun bindLiveKitCallDataSource(impl: RealLiveKitCallDataSource): LiveKitCallDataSource

    @Binds
    @Singleton
    abstract fun bindMediaCallPort(impl: RealLiveKitCallDataSource): MediaCallPort

    @Binds
    @Singleton
    abstract fun bindCallRuntimeController(impl: CallRuntimeControllerImpl): CallRuntimeController

    @Binds
    @Singleton
    abstract fun bindCallStatusRemoteDataSource(
        impl: ApiCallStatusRemoteDataSource,
    ): CallStatusRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindCallServiceController(impl: AndroidCallServiceController): CallServiceController

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds
    @Singleton
    abstract fun bindCallHistoryRepository(impl: ApiCallHistoryRepository): CallHistoryRepository

    @Binds
    @Singleton
    abstract fun bindCallCalendarRepository(impl: ApiCallCalendarRepository): CallCalendarRepository

    @Binds
    @Singleton
    abstract fun bindCallDetailRepository(impl: ApiCallDetailRepository): CallDetailRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptionRepository(
        impl: UnavailableTranscriptionRepository,
    ): TranscriptionRepository

    @Binds
    @Singleton
    abstract fun bindCallTelemetryRepository(
        impl: ApiCallTelemetryRepository,
    ): CallTelemetryRepository

    @Binds
    @Singleton
    abstract fun bindCallDiagnosticsRepository(impl: CallDiagnosticsRepositoryImpl): CallDiagnosticsRepository

    @Binds
    @Singleton
    abstract fun bindRecordingRepository(impl: RecordingRepositoryImpl): RecordingRepository

    @Binds
    @Singleton
    abstract fun bindCallOverlayStyleRepository(
        impl: SharedPreferencesCallOverlayStyleRepository,
    ): CallOverlayStyleRepository
}
