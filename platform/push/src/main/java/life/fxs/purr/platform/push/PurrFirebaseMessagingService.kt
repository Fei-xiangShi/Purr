package life.fxs.purr.platform.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PurrFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var incomingCallPushHandler: IncomingCallPushHandler

    @Inject
    lateinit var tokenCoordinator: FirebaseTokenCoordinator

    override fun onMessageReceived(message: RemoteMessage) {
        incomingCallPushHandler.handle(message.data)
    }

    override fun onRegistered(installationId: String) {
        tokenCoordinator.onRegistered(installationId)
    }
}
