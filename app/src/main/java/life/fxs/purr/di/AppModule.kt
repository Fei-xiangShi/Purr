package life.fxs.purr.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import life.fxs.purr.BuildConfig
import life.fxs.purr.config.AppConfig
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.api.PurrCallHistoryApi
import life.fxs.purr.core.network.api.PurrCallTelemetryApi
import life.fxs.purr.core.network.api.PurrRecordingApi
import life.fxs.purr.data.account.network.BearerTokenInterceptor
import life.fxs.purr.data.account.network.RefreshTokenAuthenticator
import life.fxs.purr.data.account.realtime.RealtimeEndpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        bearerTokenInterceptor: BearerTokenInterceptor,
        refreshTokenAuthenticator: RefreshTokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(bearerTokenInterceptor)
        .authenticator(refreshTokenAuthenticator)
        .addInterceptor(
            loggingInterceptor(),
        )
        .build()

    @Provides
    @Singleton
    @RealtimeEndpoint
    fun provideRealtimeEndpoint(): String = realtimeEndpoint(AppConfig.requireBaseUrl())

    @Provides
    @Singleton
    fun provideAuthenticatedRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.requireBaseUrl())
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePurrAccountApi(retrofit: Retrofit): PurrAccountApi = retrofit.create(PurrAccountApi::class.java)

    @Provides
    @Singleton
    fun providePurrCallApi(retrofit: Retrofit): PurrCallApi = retrofit.create(PurrCallApi::class.java)

    @Provides
    @Singleton
    fun providePurrCallHistoryApi(retrofit: Retrofit): PurrCallHistoryApi =
        retrofit.create(PurrCallHistoryApi::class.java)

    @Provides
    @Singleton
    fun providePurrCallTelemetryApi(retrofit: Retrofit): PurrCallTelemetryApi =
        retrofit.create(PurrCallTelemetryApi::class.java)

    @Provides
    @Singleton
    fun providePurrRecordingApi(retrofit: Retrofit): PurrRecordingApi = retrofit.create(PurrRecordingApi::class.java)

    @Provides
    @Singleton
    fun providePurrAuthApi(
        json: Json,
    ): PurrAuthApi {
        return Retrofit.Builder()
            .baseUrl(AppConfig.requireBaseUrl())
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(loggingInterceptor())
                    .build(),
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PurrAuthApi::class.java)
    }
}

private fun loggingInterceptor() = HttpLoggingInterceptor().apply {
    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
}

internal fun realtimeEndpoint(baseUrl: String): String {
    val endpoint = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegment("realtime")
        .build()
        .toString()
    return when {
        endpoint.startsWith("https://") -> "wss://${endpoint.removePrefix("https://")}"
        endpoint.startsWith("http://") -> "ws://${endpoint.removePrefix("http://")}"
        else -> error("Unsupported realtime endpoint: $endpoint")
    }
}
