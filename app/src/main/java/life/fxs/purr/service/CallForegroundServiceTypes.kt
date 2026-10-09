package life.fxs.purr.service

import android.content.pm.ServiceInfo
import android.os.Build

/** Chooses the runtime foreground-service type; a null type means the untyped overload. */
internal object CallForegroundServiceTypes {
    fun start(sdkInt: Int, starter: (Int?) -> Unit) {
        when {
            sdkInt >= Build.VERSION_CODES.R -> try {
                starter(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (_: SecurityException) {
                // phoneCall requires Telecom registration on newer releases; microphone alone still works.
                starter(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
            sdkInt >= Build.VERSION_CODES.Q -> starter(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            else -> starter(null)
        }
    }
}
