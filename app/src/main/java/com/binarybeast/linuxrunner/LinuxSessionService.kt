package com.binarybeast.linuxrunner

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.binarybeast.linuxrunner.bridge.HardwareBridgeServer
import kotlinx.coroutines.*

/**
 * Android kills background work aggressively unless it's a foreground
 * service with a visible notification. This is what keeps proot + vncserver
 * alive while the user is on the desktop viewer screen (or has briefly
 * switched away from the app).
 */
class LinuxSessionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var engine: ProotEngine? = null
    private val binder = LocalBinder()
    private val bridgeServer by lazy { HardwareBridgeServer(applicationContext) }

    inner class LocalBinder : Binder() {
        fun getService(): LinuxSessionService = this@LinuxSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val distroId = intent?.getStringExtra(EXTRA_DISTRO_ID) ?: return START_NOT_STICKY
        val distro = DistroCatalog.byId(distroId)
        engine = ProotEngine(applicationContext, distro)

        startForeground(NOTIFICATION_ID, buildNotification(distro.displayName))
        bridgeServer.start()
        return START_STICKY
    }

    suspend fun startDesktop(): Int? = engine?.startDesktopSession()

    fun getVncPassword(): String? = engine?.currentVncPassword()

    override fun onDestroy() {
        engine?.stopDesktopSession()
        bridgeServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(distroName: String): Notification {
        val channelId = "linux_session"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "جلسة لينكس نشطة", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("$distroName شغال")
            .setContentText("اضغط للرجوع لسطح المكتب — Binary Beast")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_DISTRO_ID = "distro_id"
        private const val NOTIFICATION_ID = 1001
    }
}
