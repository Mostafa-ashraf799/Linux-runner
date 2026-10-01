package com.binarybeast.linuxrunner.bridge

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * Screen bridge — brightness read/write via Settings.System. Writing
 * requires the WRITE_SETTINGS permission, which Android treats specially:
 * it can't be requested via the normal runtime-permission dialog, the user
 * has to grant it manually in a system settings screen
 * (Settings.ACTION_MANAGE_WRITE_SETTINGS). "screen_open_write_settings"
 * exists specifically to send the user to that screen the first time a
 * write is attempted without permission.
 */
class ScreenBridgeHandler(private val context: Context) : BridgeCommandHandler {

    private val supportedActions = setOf("screen_get_brightness", "screen_set_brightness", "screen_open_write_settings")

    override fun canHandle(action: String) = action in supportedActions

    override fun handle(action: String, request: JSONObject): JSONObject = when (action) {
        "screen_get_brightness" -> getBrightness()
        "screen_set_brightness" -> setBrightness(request)
        "screen_open_write_settings" -> openWriteSettings()
        else -> JSONObject().apply { put("ok", false); put("error", "unsupported") }
    }

    private fun getBrightness(): JSONObject {
        val value = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1
        )
        return JSONObject().apply {
            put("ok", true)
            put("brightness_0_255", value)
        }
    }

    private fun setBrightness(request: JSONObject): JSONObject {
        if (!Settings.System.canWrite(context)) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "صلاحية تعديل الإعدادات (WRITE_SETTINGS) غير ممنوحة — استخدم screen_open_write_settings أولاً")
            }
        }
        val value = request.optInt("value", -1)
        if (value !in 0..255) {
            return JSONObject().apply { put("ok", false); put("error", "value يجب أن تكون بين 0 و 255") }
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return JSONObject().apply { put("ok", true) }
    }

    private fun openWriteSettings(): JSONObject {
        val intent = android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        intent.data = android.net.Uri.parse("package:${context.packageName}")
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return JSONObject().apply { put("ok", true) }
    }
}
