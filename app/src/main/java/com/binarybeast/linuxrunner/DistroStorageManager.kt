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

    /** Set once the archive is fully unpacked, so a failed apt/apk step can be retried without re-extracting. */
    fun isExtracted(distro: Distro): Boolean = File(rootfsDir(distro), ".extracted").exists()

    fun markExtracted(distro: Distro) {
        File(rootfsDir(distro), ".extracted").writeText("ok")
    }

    fun markInstalled(distro: Distro) {
        File(rootfsDir(distro), ".installed").writeText("ok")
    }

    /** Recursively sums the size in bytes of an installed distro's rootfs. */
    fun currentUsageBytes(distro: Distro): Long = TarExtractor.sizeOfTree(rootfsDir(distro))

    fun currentUsageMb(distro: Distro): Long = currentUsageBytes(distro) / (1024 * 1024)

    /** User-configured cap per distro, stored in SharedPreferences. */
    fun getStorageCapMb(distro: Distro): Int {
        val prefs = context.getSharedPreferences("storage_caps", Context.MODE_PRIVATE)
        return prefs.getInt(distro.id, defaultCapMb(distro))
    }

    fun setStorageCapMb(distro: Distro, capMb: Int) {
        val prefs = context.getSharedPreferences("storage_caps", Context.MODE_PRIVATE)
        prefs.edit().putInt(distro.id, capMb).apply()
    }

    fun isOverCap(distro: Distro): Boolean =
        currentUsageMb(distro) >= getStorageCapMb(distro)

    fun deleteDistro(distro: Distro) {
        // Symlink-safe: File.deleteRecursively() would follow the rootfs's
        // symlinks (some point at absolute paths) while deleting.
        TarExtractor.deleteTree(File(baseDir, distro.id))
    }

    /** Kali's full image alone expands past 4GB, so it needs a larger default cap. */
    private fun defaultCapMb(distro: Distro): Int =
        if (distro.id == "kali") KALI_DEFAULT_CAP_MB else DEFAULT_CAP_MB

    companion object {
        const val DEFAULT_CAP_MB = 4096 // 4GB default cap, user-adjustable in setup screen
        const val KALI_DEFAULT_CAP_MB = 16384
    }
}
