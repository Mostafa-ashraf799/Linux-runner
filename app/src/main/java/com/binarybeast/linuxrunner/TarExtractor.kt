package com.binarybeast.linuxrunner

import java.io.File
import java.io.FileOutputStream

/**
 * Minimal tar extractor sufficient for rootfs tarballs (regular files,
 * directories, and symlinks). For production use, swap this for a
 * well-tested library (e.g. Apache Commons Compress) — this is a compact
 * reference implementation so the whole pipeline is inspectable.
 */
object TarExtractor {

    /**
     * onPercent reports progress against tarFile's total byte size as
     * entries are read — this is what keeps the setup screen showing real
     * movement instead of a frozen "جاري فك الضغط..." during a large
     * archive's unpacking (previously the biggest visible symptom of a
     * multi-minute Kali install: nothing on screen indicated it was still
     * working at all).
     */
    fun extract(tarFile: File, destDir: File, onPercent: (Int) -> Unit = {}) {
        destDir.mkdirs()
        val totalSize = tarFile.length().takeIf { it > 0 } ?: -1L
        var bytesConsumed = 0L

        tarFile.inputStream().use { input ->
            val header = ByteArray(512)
            while (true) {
                val read = input.read(header)
                if (read < 512) break
                bytesConsumed += 512
                if (header.all { it == 0.toByte() }) continue // end-of-archive padding block

                val name = String(header, 0, 100).trim('\u0000').trim()
                if (name.isEmpty()) continue
                val sizeOctal = String(header, 124, 12).trim('\u0000').trim()
                val size = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
                val typeFlag = header[156].toInt().toChar()

                val outFile = File(destDir, name)
                when (typeFlag) {
                    '5' -> outFile.mkdirs() // directory
                    '0', '\u0000' -> { // regular file
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var remaining = size
                            val buffer = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val n = input.read(buffer, 0, toRead)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                remaining -= n
                                bytesConsumed += n
                            }
                        }
                    }
                    else -> { /* symlinks, devices etc. skipped in this minimal version */ }
                }

                if (totalSize > 0) {
                    onPercent(((bytesConsumed * 100) / totalSize).toInt().coerceIn(0, 100))
                }

                // tar pads each entry to a multiple of 512 bytes
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) {
                    input.skip(padding)
                    bytesConsumed += padding
                }
            }
        }
    }
}
