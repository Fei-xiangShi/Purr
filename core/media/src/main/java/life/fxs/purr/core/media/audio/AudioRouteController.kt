package life.fxs.purr.core.media.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.core.model.AudioRoute

interface AudioRouteController {
    val availableRoutes: StateFlow<List<AudioRoute>>
    val activeRoute: StateFlow<AudioRoute>

    suspend fun selectRoute(route: AudioRoute)

    suspend fun selectDefaultRoute()

    /** Releases the communication device selected for the current call. */
    suspend fun releaseCallRoute()
}

class AndroidAudioRouteController(
    private val context: Context,
    private val audioManager: AudioManager,
) : AudioRouteController {
    private val _availableRoutes = MutableStateFlow(currentAvailableRoutes())
    private val _activeRoute = MutableStateFlow(currentActiveRoute(_availableRoutes.value))
    @Volatile
    private var communicationRouteActive = currentCommunicationRouteIsSelected()

    override val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()
    override val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshState()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshState()
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(
                ContextCompat.getMainExecutor(context),
            ) { refreshState() }
        }
    }

    override suspend fun selectRoute(route: AudioRoute) {
        applyRoute(route)
    }

    override suspend fun selectDefaultRoute() {
        val routes = currentAvailableRoutes()
        val route = resolveCallStartRoute(
            availableRoutes = routes,
            currentRoute = currentActiveRoute(routes),
        )
        applyRoute(route)
    }

    override suspend fun releaseCallRoute() {
        communicationRouteActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }
        refreshState()
    }

    private fun applyRoute(route: AudioRoute) {
        val routes = currentAvailableRoutes()
        require(route in routes) { "Audio route $route is not available" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModernRoute(route)
        } else {
            applyLegacyRoute(route)
        }

        communicationRouteActive = true
        _availableRoutes.value = routes
        _activeRoute.value = route
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyModernRoute(route: AudioRoute) {
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.toAudioRoute() == route }
            ?: error("No communication device is available for $route")
        check(audioManager.setCommunicationDevice(device)) {
            "Android rejected audio route $route"
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyRoute(route: AudioRoute) {
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
    }

    private fun refreshState() {
        val routes = currentAvailableRoutes()
        _availableRoutes.value = routes
        _activeRoute.value = if (communicationRouteActive || _activeRoute.value !in routes) {
            currentActiveRoute(routes)
        } else {
            _activeRoute.value
        }
    }

    private fun currentAvailableRoutes(): List<AudioRoute> {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val routes = linkedSetOf<AudioRoute>()
        if (hasBuiltInEarpiece(devices)) routes += AudioRoute.Earpiece
        routes += AudioRoute.Speaker
        devices.mapNotNullTo(routes) { it.toAudioRoute() }
        return routes.toList()
    }

    private fun currentActiveRoute(routes: List<AudioRoute>): AudioRoute {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.toAudioRoute()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return when {
            audioManager.isBluetoothScoOn && AudioRoute.Bluetooth in routes -> AudioRoute.Bluetooth
            audioManager.isSpeakerphoneOn -> AudioRoute.Speaker
            AudioRoute.WiredHeadset in routes -> AudioRoute.WiredHeadset
            AudioRoute.Earpiece in routes -> AudioRoute.Earpiece
            else -> AudioRoute.Speaker
        }
    }

    private fun hasBuiltInEarpiece(devices: List<AudioDeviceInfo>): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    private fun currentCommunicationRouteIsSelected(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice != null
    } else {
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn || audioManager.isSpeakerphoneOn
    }
}

internal fun resolveCallStartRoute(
    availableRoutes: List<AudioRoute>,
    currentRoute: AudioRoute,
): AudioRoute {
    require(availableRoutes.isNotEmpty()) { "At least one audio route must be available" }
    return when {
        AudioRoute.Earpiece in availableRoutes -> AudioRoute.Earpiece
        currentRoute in availableRoutes -> currentRoute
        else -> availableRoutes.first()
    }
}

private fun AudioDeviceInfo.toAudioRoute(): AudioRoute? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Earpiece
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.Speaker
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    -> AudioRoute.WiredHeadset
    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type in bleAudioDeviceTypes) {
        AudioRoute.Bluetooth
    } else {
        null
    }
}

private val bleAudioDeviceTypes: Set<Int>
    @RequiresApi(Build.VERSION_CODES.S)
    get() = setOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
