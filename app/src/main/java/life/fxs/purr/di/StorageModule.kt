package life.fxs.purr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import life.fxs.purr.data.account.local.SessionStore
import life.fxs.purr.data.account.local.AndroidKeyStoreTokenCipher
import life.fxs.purr.data.account.local.TokenCipher
import life.fxs.purr.core.common.ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideTokenCipher(): TokenCipher = AndroidKeyStoreTokenCipher()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile(SessionStore.FILE_NAME) },
    )
}
