package life.fxs.purr.core.media.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.core.model.AudioRoute

interface AudioRouteController {
    val availableRoutes: StateFlow<List<AudioRoute>>
    val activeRoute: StateFlow<AudioRoute>

    suspend fun selectRoute(route: AudioRoute)
}

class AndroidAudioRouteController(
    private val context: Context,
    private val audioManager: AudioManager,
) : AudioRouteController {
    private val _availableRoutes = MutableStateFlow(currentAvailableRoutes())
    private val _activeRoute = MutableStateFlow(currentActiveRoute(_availableRoutes.value))

    override val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()
    override val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    override suspend fun selectRoute(route: AudioRoute) {
        val routes = currentAvailableRoutes()
        require(route in routes) { "Audio route $route is not available" }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        when (route) {
            AudioRoute.Speaker -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
            AudioRoute.Bluetooth -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            AudioRoute.WiredHeadset,
            AudioRoute.Earpiece,
            -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }

        refreshState()
    }

    private fun refreshState() {
        val routes = currentAvailableRoutes()
        _availableRoutes.value = routes
        _activeRoute.value = currentActiveRoute(routes)
    }

    private fun currentAvailableRoutes(): List<AudioRoute> {
        val routes = linkedSetOf<AudioRoute>()
        if (hasBuiltInEarpiece()) {
            routes += AudioRoute.Earpiece
        }
        routes += AudioRoute.Speaker

        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                -> routes += AudioRoute.Bluetooth

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                -> routes += AudioRoute.WiredHeadset
            }
        }

        return routes.toList()
    }

    private fun currentActiveRoute(routes: List<AudioRoute>): AudioRoute = when {
        audioManager.isBluetoothScoOn && AudioRoute.Bluetooth in routes -> AudioRoute.Bluetooth
        audioManager.isSpeakerphoneOn -> AudioRoute.Speaker
        AudioRoute.WiredHeadset in routes -> AudioRoute.WiredHeadset
        AudioRoute.Earpiece in routes -> AudioRoute.Earpiece
        else -> AudioRoute.Speaker
    }

    private fun hasBuiltInEarpiece(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
}
