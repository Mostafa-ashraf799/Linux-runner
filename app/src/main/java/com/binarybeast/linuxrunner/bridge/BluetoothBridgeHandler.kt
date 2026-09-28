package com.binarybeast.linuxrunner.bridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bluetooth bridge. As noted in the roadmap: Linux running under proot has
 * no real access to the Bluetooth radio (BlueZ inside the rootfs can't see
 * real hardware) — every action here is a real call into Android's
 * BluetoothAdapter, triggered by a request coming from a script inside
 * the Linux environment.
 *
 * Scanning for new devices and connecting both require the
 * BLUETOOTH_SCAN / BLUETOOTH_CONNECT runtime permissions (Android 12+,
 * requested from DistroListActivity/a settings screen — see
 * PermissionHelper). If not granted, this handler returns a clear error
 * instead of silently failing.
 */
class BluetoothBridgeHandler(private val context: Context) : BridgeCommandHandler {

    private val supportedActions = setOf("bt_status", "bt_paired_devices", "bt_open_settings")

    override fun canHandle(action: String) = action in supportedActions

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun handle(action: String, request: JSONObject): JSONObject = when (action) {
        "bt_status" -> status()
        "bt_paired_devices" -> pairedDevices()
        "bt_open_settings" -> openSettings()
        else -> JSONObject().apply { put("ok", false); put("error", "unsupported") }
    }

    private fun hasConnectPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun status(): JSONObject {
        val bt = adapter
        return JSONObject().apply {
            put("ok", true)
            put("supported", bt != null)
            put("enabled", bt?.isEnabled ?: false)
        }
    }

    private fun pairedDevices(): JSONObject {
        if (!hasConnectPermission()) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "صلاحية BLUETOOTH_CONNECT غير ممنوحة — فعّلها من إعدادات التطبيق")
            }
        }
        val bt = adapter ?: return JSONObject().apply { put("ok", false); put("error", "البلوتوث غير مدعوم على هذا الجهاز") }

        val devices = JSONArray()
        @Suppress("MissingPermission") // guarded by hasConnectPermission() above
        bt.bondedDevices?.forEach { device ->
            devices.put(JSONObject().apply {
                put("name", device.name)
                put("address", device.address)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("devices", devices)
        }
    }

    private fun openSettings(): JSONObject {
        val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return JSONObject().apply { put("ok", true) }
    }
}
