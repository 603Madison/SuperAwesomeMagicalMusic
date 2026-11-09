package com.music.connect.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ext.SdkExtensions
import android.util.Log
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(
    val networkClass: NetworkClass,
    /** The network lacks NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED (e.g. T-Satellite data). */
    val bandwidthConstrained: Boolean = false,
    val metered: Boolean = false,
) {
    val isOnline: Boolean get() = networkClass != NetworkClass.NONE

    companion object {
        val OFFLINE = NetworkState(NetworkClass.NONE)
    }
}

/**
 * Tracks which kind of network playback is using, following Android's guidance for constrained
 * satellite networks (https://developer.android.com/develop/connectivity/satellite/constrained-networks):
 *
 *  - The manifest opts in with `android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED`, which lets
 *    the app use a satellite network (T-Satellite, etc.) when it is the only network available.
 *  - The network request removes NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED so constrained
 *    networks are eligible.
 *  - A network counts as satellite when it has TRANSPORT_SATELLITE or lacks
 *    NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED.
 */
class NetworkMonitor(private val context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkState.OFFLINE)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.let { _state.value = classify(it) }

        val thread = HandlerThread("SatelliteNetworkMonitor").apply { start() }
        val handler = Handler(thread.looper)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                current = network
                _state.value = classify(capabilities)
            }

            override fun onLost(network: Network) {
                if (current == null || network == current) {
                    current = null
                    _state.value = NetworkState.OFFLINE
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivityManager.registerBestMatchingNetworkCallback(buildRequest(), callback, handler)
            } else {
                connectivityManager.registerDefaultNetworkCallback(callback, handler)
            }
        } catch (e: RuntimeException) {
            // Android 16+ can reject requests involving the new capabilities; fall back to the default network.
            Log.w(TAG, "Best-matching network callback rejected, using default network", e)
            connectivityManager.registerDefaultNetworkCallback(callback, handler)
        }
    }

    // The satellite constants are hard-coded so they also work on releases that predate them.
    @SuppressLint("WrongConstant")
    private fun buildRequest(): NetworkRequest {
        val builder = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        try {
            builder.removeCapability(NetworkClassifier.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
        } catch (e: IllegalArgumentException) {
            // The platform predates the capability, so constrained networks do not exist here.
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        @SuppressLint("WrongConstant")
        fun classify(caps: NetworkCapabilities): NetworkState {
            val constrained = NetworkClassifier.constraintCapabilitySupported &&
                !caps.hasCapability(NetworkClassifier.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
            val networkClass = NetworkClassifier.classify(
                satelliteTransport = caps.hasTransport(NetworkClassifier.TRANSPORT_SATELLITE),
                bandwidthConstrained = constrained,
                wifiLike = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
            return NetworkState(
                networkClass = networkClass,
                bandwidthConstrained = constrained,
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
    }
}

object NetworkClassifier {
    /** NetworkCapabilities.TRANSPORT_SATELLITE (API 35); hard-coded so older releases compile against it. */
    const val TRANSPORT_SATELLITE = 10

    /** NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED (API 36, or SDK extension 16). */
    const val NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37

    /**
     * Whether the platform knows about NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED. On older
     * platforms the bit is never set, so its absence must not be read as "constrained".
     */
    val constraintCapabilitySupported: Boolean by lazy {
        when {
            Build.VERSION.SDK_INT >= 36 -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> try {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 16
            } catch (e: RuntimeException) {
                false
            }
            else -> false
        }
    }

    fun classify(
        satelliteTransport: Boolean,
        bandwidthConstrained: Boolean,
        wifiLike: Boolean,
        cellular: Boolean,
        metered: Boolean,
    ): NetworkClass = when {
        satelliteTransport || bandwidthConstrained -> NetworkClass.SATELLITE
        wifiLike -> NetworkClass.WIFI
        cellular -> NetworkClass.CELLULAR
        // VPNs, Bluetooth tethering, etc.: judge by whether the link is metered.
        metered -> NetworkClass.CELLULAR
        else -> NetworkClass.WIFI
    }
}
