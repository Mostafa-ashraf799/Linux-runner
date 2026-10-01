package com.binarybeast.linuxrunner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.binarybeast.linuxrunner.databinding.ActivityDistroListBinding

class DistroListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDistroListBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDistroListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        requestBluetoothPermissionsIfNeeded()
        showVersionLabel()

        val storage = DistroStorageManager(this)

        binding.distroRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.distroRecyclerView.adapter = DistroListAdapter(
            distros = DistroCatalog.ALL,
            storage = storage,
            onClick = { distro ->
                val intent = Intent(this, DistroSetupActivity::class.java)
                intent.putExtra(DistroSetupActivity.EXTRA_DISTRO_ID, distro.id)
                startActivity(intent)
            },
            onLongClick = { distro ->
                if (storage.isInstalled(distro)) {
                    val intent = Intent(this, DistroSettingsActivity::class.java)
                    intent.putExtra(DistroSettingsActivity.EXTRA_DISTRO_ID, distro.id)
                    startActivity(intent)
                }
            }
        )
    }

    /**
     * Android 13+ (API 33) requires runtime consent to show any
     * notification, including the persistent one LinuxSessionService
     * relies on to stay alive in the background. Asked once, up front,
     * so the session notification actually appears later.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Needed for the Bluetooth hardware bridge (BluetoothBridgeHandler) —
     * without these, a script inside the Linux distro asking for paired
     * devices will get a clear "permission not granted" error instead of
     * silently failing.
     */
    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
            if (needed.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    /**
     * Reads versionName directly from the installed package rather than
     * hardcoding it here, so this label always matches whatever was set
     * in build.gradle.kts for the build actually running — no risk of it
     * going stale in a second place. This is also a fast way to confirm
     * which build is really installed if update confusion comes up again.
     */
    private fun showVersionLabel() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        binding.versionLabel.text = versionName?.let { "v$it" } ?: ""
    }

    override fun onResume() {
        super.onResume()
        // Refresh installed/usage status when returning from setup or the desktop viewer.
        binding.distroRecyclerView.adapter?.notifyDataSetChanged()
    }
}
