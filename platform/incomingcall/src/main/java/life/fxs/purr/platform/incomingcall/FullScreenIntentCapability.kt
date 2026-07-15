package life.fxs.purr.platform.incomingcall

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal fun interface FullScreenIntentCapability {
    fun canUse(): Boolean
}

internal class AndroidFullScreenIntentCapability @Inject constructor(
    @ApplicationContext context: Context,
) : FullScreenIntentCapability {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun canUse(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()
}
