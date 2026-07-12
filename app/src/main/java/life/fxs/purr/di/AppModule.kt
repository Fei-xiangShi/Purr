package life.fxs.purr.di

import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import life.fxs.purr.config.AppConfig
import life.fxs.purr.core.media.audio.AndroidAudioRouteController
import life.fxs.purr.core.media.audio.AndroidCallAudioFocusManager
import life.fxs.purr.core.media.audio.AudioRouteController
import life.fxs.purr.core.media.audio.CallAudioFocusManager
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.core.media.livekit.MutableCallRoomStateProvider
import life.fxs.purr.core.media.service.CallServiceController
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.data.account.network.BearerTokenInterceptor
import life.fxs.purr.data.account.network.RefreshTokenAuthenticator
import life.fxs.purr.data.account.repository.ApiAuthRepository
import life.fxs.purr.data.account.repository.ApiPairRepository
import life.fxs.purr.data.account.realtime.ApiRealtimeRepository
import life.fxs.purr.data.account.realtime.RealtimeEndpoint
import life.fxs.purr.data.call.livekit.LiveKitCallDataSource
import life.fxs.purr.data.call.livekit.RealLiveKitCallDataSource
import life.fxs.purr.data.call.repository.CallRepositoryImpl
import life.fxs.purr.domain.account.repository.AuthRepository
import life.fxs.purr.domain.account.repository.PairRepository
import life.fxs.purr.domain.account.repository.RealtimeRepository
import life.fxs.purr.domain.call.repository.CallRepository
import life.fxs.purr.service.CallForegroundService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(SessionStore.FILE_NAME) },
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(
        bearerTokenInterceptor: BearerTokenInterceptor,
        refreshTokenAuthenticator: RefreshTokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(bearerTokenInterceptor)
        .authenticator(refreshTokenAuthenticator)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    @Provides
    @Singleton
    @RealtimeEndpoint
    fun provideRealtimeEndpoint(): String {
        val baseUrl = AppConfig.requireBaseUrl().toHttpUrl()
        val socketScheme = if (baseUrl.isHttps) "wss" else "ws"
        return baseUrl.newBuilder()
            .scheme(socketScheme)
            .addPathSegment("realtime")
            .build()
            .toString()
    }

    @Provides
    @Singleton
    fun providePurrCallApi(
        okHttpClient: OkHttpClient,
        json: Json,
    ): PurrCallApi {
        val baseUrl = AppConfig.requireBaseUrl()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PurrCallApi::class.java)
    }

    @Provides
    @Singleton
    fun providePurrAuthApi(
        json: Json,
    ): PurrAuthApi {
        val baseUrl = AppConfig.requireBaseUrl()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                    .build(),
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PurrAuthApi::class.java)
    }

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
    ): AudioRouteController = AndroidAudioRouteController(
        context = context,
        audioManager = audioManager,
    )

    @Provides
    @Singleton
    fun provideCallAudioFocusManager(
        audioManager: AudioManager,
    ): CallAudioFocusManager = AndroidCallAudioFocusManager(audioManager)

    @Provides
    @Singleton
    fun provideCallRoomStateProvider(
        impl: MutableCallRoomStateProvider,
    ): CallRoomStateProvider = impl

    @Provides
    @Singleton
    fun provideCallServiceController(
        @ApplicationContext context: Context,
    ): CallServiceController = object : CallServiceController {
        private val foreground = MutableStateFlow(false)

        override val isCallForeground: StateFlow<Boolean> = foreground

        override suspend fun startForegroundCall(callId: String) {
            foreground.emit(true)
            ContextCompat.startForegroundService(context, CallForegroundService.intent(context))
        }

        override suspend fun stopForegroundCall() {
            foreground.emit(false)
            context.stopService(CallForegroundService.intent(context))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        impl: ApiAuthRepository,
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindPairRepository(
        impl: ApiPairRepository,
    ): PairRepository

    @Binds
    @Singleton
    abstract fun bindRealtimeRepository(
        impl: ApiRealtimeRepository,
    ): RealtimeRepository

    @Binds
    @Singleton
    abstract fun bindLiveKitCallDataSource(
        impl: RealLiveKitCallDataSource,
    ): LiveKitCallDataSource

    @Binds
    @Singleton
    abstract fun bindCallRepository(
        impl: CallRepositoryImpl,
    ): CallRepository
}
