package com.binarybeast.linuxrunner

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.binarybeast.linuxrunner.databinding.ActivityDistroSettingsBinding

/**
 * Lets the user see how much space a distro is actually using, adjust the
 * software-enforced storage cap described in the roadmap (real disk-image
 * mounting isn't available without root, so usage is capped by measuring
 * the rootfs folder size — see DistroStorageManager), and delete a distro
 * entirely to reclaim space.
 */
class DistroSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDistroSettingsBinding
    private lateinit var distro: Distro
    private lateinit var storage: DistroStorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDistroSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val distroId = intent.getStringExtra(EXTRA_DISTRO_ID) ?: error("EXTRA_DISTRO_ID is required")
        distro = DistroCatalog.byId(distroId)
        storage = DistroStorageManager(this)

        binding.settingsTitle.text = "إعدادات ${distro.displayName}"
        renderUsage()

        val currentCap = storage.getStorageCapMb(distro)
        binding.storageCapSeekBar.progress = currentCap
        binding.storageCapValueText.text = "$currentCap MB"

        binding.storageCapSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.storageCapValueText.text = "$progress MB"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.saveCapButton.setOnClickListener {
            storage.setStorageCapMb(distro, binding.storageCapSeekBar.progress)
            renderUsage()
        }

        binding.deleteDistroButton.setOnClickListener { confirmDelete() }
    }

    private fun renderUsage() {
        val usedMb = storage.currentUsageMb(distro)
        val capMb = storage.getStorageCapMb(distro)
        binding.usageText.text = "مستخدم: $usedMb MB من أصل $capMb MB"
        binding.usageBar.max = capMb.toInt().coerceAtLeast(1)
        binding.usageBar.progress = usedMb.toInt().coerceAtMost(capMb.toInt())
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف ${distro.displayName}؟")
            .setMessage("هيتحذف كل شيء داخل هذه التوزيعة نهائيًا. لا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ ->
                storage.deleteDistro(distro)
                finish()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    companion object {
        const val EXTRA_DISTRO_ID = "distro_id"
    }
}
