package com.binarybeast.linuxrunner.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject

/**
 * Network bridge — the easiest one, per the roadmap, because proot already
 * shares Android's own network stack for actual traffic (ping/curl/apt
 * work without any bridge at all). What this bridge adds is *status
 * information* a script inside Linux otherwise has no way to query:
 * is wifi on, what's the SSID, is there a working internet connection.
 *
 * NOTE on toggling wifi on/off: since Android 10 (API 29), apps can no
 * longer programmatically enable/disable wifi (WifiManager.setWifiEnabled
 * is a documented no-op for non-system apps). So this bridge only exposes
 * read/status actions, plus opening the system wifi settings screen as the
 * closest thing to a "control" action — that's a real, honest limitation,
 * not a bug to fix later.
 */
class NetworkBridgeHandler(private val context: Context) : BridgeCommandHandler {

    private val supportedActions = setOf("wifi_status", "network_status", "open_wifi_settings")

    override fun canHandle(action: String) = action in supportedActions

    override fun handle(action: String, request: JSONObject): JSONObject = when (action) {
        "wifi_status" -> wifiStatus()
        "network_status" -> networkStatus()
        "open_wifi_settings" -> openWifiSettings()
        else -> JSONObject().apply { put("ok", false); put("error", "unsupported") }
    }

    private fun wifiStatus(): JSONObject {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        return JSONObject().apply {
            put("ok", true)
            put("enabled", wifiManager.isWifiEnabled)
            // SSID requires location permission on modern Android to avoid
            // exposing it for tracking purposes — intentionally omitted
            // here rather than requesting a broad permission for it.
        }
    }

    private fun networkStatus(): JSONObject {
        val connectivityManager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

        return JSONObject().apply {
            put("ok", true)
            put("connected", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
            put("transport", transport)
        }
    }

    private fun openWifiSettings(): JSONObject {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return JSONObject().apply { put("ok", true) }
    }
}
