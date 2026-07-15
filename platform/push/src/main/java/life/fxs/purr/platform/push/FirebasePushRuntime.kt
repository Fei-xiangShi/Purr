package life.fxs.purr.platform.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal interface PushRuntime {
    fun initialize(): Boolean
}

@Singleton
internal class FirebasePushRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configuration: FirebasePushConfiguration,
) : PushRuntime {
    @Volatile
    private var initialized = false

    override fun initialize(): Boolean {
        if (initialized) return true

        synchronized(this) {
            if (initialized) return true
            val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (existing == null) {
                val resourceConfiguredApp = FirebaseApp.initializeApp(context)
                if (resourceConfiguredApp == null) {
                    if (!configuration.isComplete) return false
                    FirebaseApp.initializeApp(context, configuration.toFirebaseOptions())
                        ?: return false
                }
            }
            initialized = true
        }
        return true
    }
}

private fun FirebasePushConfiguration.toFirebaseOptions(): FirebaseOptions =
    FirebaseOptions.Builder()
        .setApplicationId(applicationId)
        .setApiKey(apiKey)
        .setProjectId(projectId)
        .setGcmSenderId(senderId)
        .build()
