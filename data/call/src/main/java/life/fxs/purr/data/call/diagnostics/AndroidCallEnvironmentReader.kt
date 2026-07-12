package life.fxs.purr.data.call.diagnostics

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import life.fxs.purr.domain.call.model.DeviceCallMetrics
import life.fxs.purr.domain.call.model.NetworkTransport

@Singleton
class AndroidCallEnvironmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): DeviceCallMetrics {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return DeviceCallMetrics(callVolumePercent = readCallVolumePercent())
        return DeviceCallMetrics(
            networkTransport = capabilities.toNetworkTransport(),
            networkValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            networkMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            estimatedUpstreamKbps = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.toDouble(),
            estimatedDownstreamKbps = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.toDouble(),
            callVolumePercent = readCallVolumePercent(),
        )
    }

    private fun readCallVolumePercent(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (max <= 0) return 0
        return (audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) * 100 / max).coerceIn(0, 100)
    }
}

private fun NetworkCapabilities.toNetworkTransport(): NetworkTransport = when {
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.Vpn
    hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.Bluetooth
    else -> NetworkTransport.Other
}
