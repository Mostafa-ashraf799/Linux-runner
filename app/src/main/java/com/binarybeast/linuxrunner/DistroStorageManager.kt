package com.binarybeast.linuxrunner

import android.content.Context
import java.io.File

/**
 * Handles where each distro's rootfs lives on disk, and enforces a
 * user-chosen storage cap in software (see roadmap note: without root we
 * can't mount a real fixed-size disk image, so we cap usage by measuring
 * the folder size before allowing new writes/extraction).
 */
class DistroStorageManager(private val context: Context) {

    private val baseDir: File
        get() = File(context.filesDir, "distros").apply { mkdirs() }

    fun rootfsDir(distro: Distro): File =
        File(baseDir, distro.id).apply { mkdirs() }

    fun downloadedTarballFile(distro: Distro): File =
        File(context.cacheDir, "${distro.id}-rootfs.tar")

    fun isInstalled(distro: Distro): Boolean {
        val marker = File(rootfsDir(distro), ".installed")
        return marker.exists()
    }

    fun markInstalled(distro: Distro) {
        File(rootfsDir(distro), ".installed").writeText("ok")
    }

    /** Recursively sums the size in bytes of an installed distro's rootfs. */
    fun currentUsageBytes(distro: Distro): Long =
        rootfsDir(distro).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun currentUsageMb(distro: Distro): Long = currentUsageBytes(distro) / (1024 * 1024)

    /** User-configured cap per distro, stored in SharedPreferences. */
    fun getStorageCapMb(distro: Distro): Int {
        val prefs = context.getSharedPreferences("storage_caps", Context.MODE_PRIVATE)
        return prefs.getInt(distro.id, DEFAULT_CAP_MB)
    }

    fun setStorageCapMb(distro: Distro, capMb: Int) {
        val prefs = context.getSharedPreferences("storage_caps", Context.MODE_PRIVATE)
        prefs.edit().putInt(distro.id, capMb).apply()
    }

    fun isOverCap(distro: Distro): Boolean =
        currentUsageMb(distro) >= getStorageCapMb(distro)

    fun deleteDistro(distro: Distro) {
        rootfsDir(distro).deleteRecursively()
    }

    companion object {
        const val DEFAULT_CAP_MB = 4096 // 4GB default cap, user-adjustable in setup screen
    }
}
