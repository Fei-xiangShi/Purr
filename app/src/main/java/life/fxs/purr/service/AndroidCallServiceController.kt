package life.fxs.purr.service

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import life.fxs.purr.core.media.service.CallServiceController

@Singleton
class AndroidCallServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallServiceController {
    private val foreground = MutableStateFlow(false)

    override val isCallForeground: StateFlow<Boolean> = foreground

    override suspend fun startForegroundCall(callId: String) {
        ContextCompat.startForegroundService(context, CallForegroundService.intent(context))
        foreground.emit(true)
    }

    override suspend fun stopForegroundCall() {
        context.stopService(CallForegroundService.intent(context))
        foreground.emit(false)
    }
}
