package life.fxs.purr.platform.push

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PushPlatformBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRuntime(implementation: FirebasePushRuntime): PushRuntime

    @Binds
    @Singleton
    abstract fun bindInstallationIdStore(
        implementation: SharedPreferencesInstallationIdStore,
    ): InstallationIdStore

    @Binds
    @Singleton
    abstract fun bindTokenStore(implementation: SharedPreferencesPushTokenStore): PushTokenStore

    @Binds
    @Singleton
    abstract fun bindTokenProvider(implementation: FirebasePushTokenProvider): PushTokenProvider

    @Binds
    @Singleton
    abstract fun bindWakeScheduler(
        implementation: WorkManagerIncomingCallWakeScheduler,
    ): IncomingCallWakeScheduler
}

@Module
@InstallIn(SingletonComponent::class)
internal object PushPlatformProvidersModule {
    @Provides
    @Singleton
    fun provideFirebaseConfiguration(): FirebasePushConfiguration =
        FirebasePushConfiguration.fromBuildConfig()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
