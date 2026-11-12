package io.github.loopholetv.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build

/**
 * A Wi-Fi lock for the length of a cast, so power saving can't slow the stream to the other TVs:
 * low-latency mode from Android 10, high-performance mode before. Harmless on Ethernet.
 */
fun castWifiHold(context: Context): () -> AutoCloseable = {
    val lock = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createWifiLock(wifiLockMode(), "loophole-cast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
    AutoCloseable { runCatching { lock?.release() } }
}

@Suppress("DEPRECATION")
internal fun wifiLockMode(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
} else {
    WifiManager.WIFI_MODE_FULL_HIGH_PERF
}

/**
 * Watches the default network: `onChange` runs on a system thread when it appears, goes, or its
 * addresses change. Closing the result stops the watch; without a usable platform service it
 * watches nothing.
 */
fun defaultNetworkChanges(context: Context): (onChange: () -> Unit) -> AutoCloseable = { onChange ->
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
    }
    val registered = try {
        cm?.registerDefaultNetworkCallback(callback)
        cm != null
    } catch (_: RuntimeException) {
        false // For example, too many callbacks already registered.
    }
    AutoCloseable { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
}
