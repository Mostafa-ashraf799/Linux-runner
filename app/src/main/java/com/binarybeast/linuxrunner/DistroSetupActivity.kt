package com.binarybeast.linuxrunner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.binarybeast.linuxrunner.databinding.ActivityDistroSetupBinding
import kotlinx.coroutines.launch

/**
 * This screen is where "manual Termux typing" becomes "automatic":
 * it drives ProotEngine.ensureInstalled() end-to-end (get rootfs -> extract
 * -> install XFCE+VNC inside it) and shows live progress, with zero
 * commands for the user to type.
 *
 * The user picks up front where the rootfs tarball comes from:
 *   - "تحميل تلقائي": the app downloads it itself (original flow)
 *   - "اختيار ملف من التنزيلات": the user already has the tarball on their
 *     device (e.g. downloaded manually, or shared to them) and picks it via
 *     the system file picker instead of re-downloading it.
 */
class DistroSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDistroSetupBinding
    private lateinit var distro: Distro
    private lateinit var engine: ProotEngine

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            beginSetup(ProotEngine.RootfsSource.LocalFile(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDistroSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val distroId = intent.getStringExtra(EXTRA_DISTRO_ID)
            ?: error("EXTRA_DISTRO_ID is required")
        distro = DistroCatalog.byId(distroId)
        engine = ProotEngine(applicationContext, distro)

        binding.setupTitle.text = "تجهيز ${distro.displayName}"

        binding.launchDesktopButton.setOnClickListener {
            val intent = Intent(this, DesktopViewerActivity::class.java)
            intent.putExtra(DesktopViewerActivity.EXTRA_DISTRO_ID, distro.id)
            startActivity(intent)
        }

        if (DistroStorageManager(this).isInstalled(distro)) {
            // Already set up before — skip straight past the source choice.
            binding.sourceChoiceLayout.visibility = View.GONE
            beginSetup(ProotEngine.RootfsSource.Download)
            return
        }

        binding.downloadAutoButton.setOnClickListener {
            binding.sourceChoiceLayout.visibility = View.GONE
            beginSetup(ProotEngine.RootfsSource.Download)
        }

        binding.pickFileButton.setOnClickListener {
            // Accept any type since rootfs tarballs are usually shared with
            // a generic MIME type (application/octet-stream, application/gzip,
            // application/x-xz, etc.) rather than a single standard one.
            filePicker.launch(arrayOf("*/*"))
        }
    }

    private fun beginSetup(source: ProotEngine.RootfsSource) {
        binding.sourceChoiceLayout.visibility = View.GONE
        binding.setupProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            engine.ensureInstalled(source) { progress ->
                runOnUiThread { render(progress) }
            }
        }
    }

    private fun render(progress: ProotEngine.SetupProgress) {
        when (progress) {
            is ProotEngine.SetupProgress.Downloading -> {
                binding.setupProgressBar.progress = progress.percent
                binding.setupStatusText.text = "جاري تحميل النظام... ${progress.percent}%"
            }
            is ProotEngine.SetupProgress.CopyingLocalFile -> {
                binding.setupProgressBar.isIndeterminate = true
                binding.setupStatusText.text = "جاري نسخ الملف المختار..."
            }
            is ProotEngine.SetupProgress.Extracting -> {
                binding.setupProgressBar.isIndeterminate = true
                binding.setupStatusText.text = "جاري فك الضغط..."
            }
            is ProotEngine.SetupProgress.InstallingDesktop -> {
                binding.setupStatusText.text = "جاري تثبيت سطح المكتب و VNC (أول مرة فقط)..."
            }
            is ProotEngine.SetupProgress.Done -> {
                binding.setupProgressBar.isIndeterminate = false
                binding.setupProgressBar.progress = 100
                binding.setupStatusText.text = "جاهز!"
                binding.launchDesktopButton.visibility = View.VISIBLE
            }
            is ProotEngine.SetupProgress.Failed -> {
                binding.setupStatusText.text = "حصل خطأ: ${progress.reason}"
            }
            is ProotEngine.SetupProgress.StorageCapExceeded -> {
                binding.setupProgressBar.isIndeterminate = false
                binding.setupStatusText.text =
                    "حجم التوزيعة (${progress.usedMb} MB) أكبر من الحد المسموح (${progress.capMb} MB). " +
                    "ارفع الحد من إعدادات التوزيعة (بعد أول تثبيت) أو اختر توزيعة أخف مثل Alpine."
            }
        }
    }

    companion object {
        const val EXTRA_DISTRO_ID = "distro_id"
    }
}
