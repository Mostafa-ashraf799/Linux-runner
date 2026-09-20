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

    fun extract(tarFile: File, destDir: File) {
        destDir.mkdirs()
        tarFile.inputStream().use { input ->
            val header = ByteArray(512)
            while (true) {
                val read = input.read(header)
                if (read < 512) break
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
                            val buffer = ByteArray(4096)
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val n = input.read(buffer, 0, toRead)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    else -> { /* symlinks, devices etc. skipped in this minimal version */ }
                }

                // tar pads each entry to a multiple of 512 bytes
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) input.skip(padding)
            }
        }
    }
}
