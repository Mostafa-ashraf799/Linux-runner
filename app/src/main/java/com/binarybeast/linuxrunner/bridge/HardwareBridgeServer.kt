package com.binarybeast.linuxrunner.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * The Android-side half of the hardware bridge described in the roadmap:
 *
 *   [script inside the Linux rootfs]
 *           |  (local TCP socket on 127.0.0.1)
 *           v
 *   [this server, running inside the app]
 *           |  (real Android SDK APIs)
 *           v
 *   [actual hardware: wifi / bluetooth / screen brightness]
 *
 * Protocol: one JSON object per line, request and response both JSON.
 *   Request:  {"action": "wifi_status"}
 *   Response: {"ok": true, "enabled": true}
 *
 * This is intentionally a plain loopback TCP socket (not a Unix socket)
 * because Android's ProcessBuilder/proot environment can reach 127.0.0.1
 * without any extra permission, and JSON-over-TCP is trivial to call from
 * any shell script with nothing more than `nc` (netcat) or a tiny Python
 * snippet — both commonly available or easily installed in the rootfs.
 */
class HardwareBridgeServer(
    private val context: Context,
    private val port: Int = 7912
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handlers: List<BridgeCommandHandler> = listOf(
        NetworkBridgeHandler(context),
        BluetoothBridgeHandler(context),
        ScreenBridgeHandler(context)
    )

    fun start() {
        if (serverSocket != null) return // already running
        scope.launch {
            try {
                // Bind explicitly to loopback only — this must never be
                // reachable from outside the device.
                val socket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                while (!socket.isClosed) {
                    val client = socket.accept()
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                // Server loop ended (stop() called, or a real bind error) — nothing to
                // recover here; stop() is the intended way to end this loop.
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = s.getOutputStream().bufferedWriter()

            val line = reader.readLine() ?: return
            val response = try {
                val request = JSONObject(line)
                val action = request.optString("action")
                val handler = handlers.firstOrNull { it.canHandle(action) }
                handler?.handle(action, request)
                    ?: errorResponse("أمر غير معروف: $action")
            } catch (e: Exception) {
                errorResponse(e.message ?: "طلب غير صالح")
            }

            writer.write(response.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun errorResponse(message: String) = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }
}

/** Common interface every hardware bridge handler implements. */
interface BridgeCommandHandler {
    fun canHandle(action: String): Boolean
    fun handle(action: String, request: JSONObject): JSONObject
}
