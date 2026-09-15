package com.marvinformatics.frameremote.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Everything this app talks to lives on the LAN. On Android, sockets use the
 * *default* network — which can be cellular (mobile data on + Wi-Fi without
 * validated internet, e.g. an isolated LAN). Then every request silently
 * leaves through the wrong interface and nothing works. So: find the Wi-Fi
 * [Network] and pin the process to it while the app is in the foreground.
 */
class WifiNet(private val context: Context) {

    private val cm: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /** The currently connected Wi-Fi network, or null when there is none. */
    fun wifiNetwork(): Network? {
        val manager = cm ?: return null
        @Suppress("DEPRECATION")
        return manager.allNetworks.firstOrNull { n ->
            manager.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Pins every socket this process opens (HTTP, WebSocket, UDP) to Wi-Fi.
     * Returns false when there is no Wi-Fi network — callers should tell the
     * user instead of silently searching the wrong interface.
     */
    fun bindProcessToWifi(): Boolean {
        val manager = cm ?: return false
        val network = wifiNetwork() ?: return false
        return manager.bindProcessToNetwork(network)
    }

    fun unbindProcess() {
        cm?.bindProcessToNetwork(null)
    }
}
