package com.binarybeast.linuxrunner

import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Streaming rootfs extractor: .tar / .tar.gz / .tar.xz  ->  files on disk,
 * in ONE pass with no intermediate giant .tar file.
 *
 * Why this replaced the old two-step design (decompress everything to a
 * temporary .tar, then untar it):
 *   - Kali's rootfs expands to several GB; writing it to disk once just to
 *     read it back doubled the I/O and needed a lot of free space.
 *   - Decompression (CPU heavy, esp. pure-Java XZ) and file writing (I/O
 *     heavy) now run in two threads connected by a 4MB pipe, so they
 *     overlap instead of running back to back.
 *   - Progress is now REAL: it is the number of COMPRESSED bytes consumed
 *     divided by the compressed file size, which is exactly known.
 *
 * It also fixes correctness problems of the old hand-written tar parser
 * that would have produced a broken rootfs even when extraction finished:
 *   - symlinks (/bin -> usr/bin ...) were skipped   -> now created
 *   - hard links were skipped                       -> now created
 *   - file permissions (exec bit!) were dropped     -> now applied
 *   - GNU long names / PAX headers (very common in Kali) were not parsed
 *     and corrupted every following entry           -> handled by Commons Compress
 *   - a wrapper folder (Kali's "kali-arm64/") ended up as the rootfs top
 *     level, so /usr/bin/env etc. were never found  -> auto-stripped
 */
object TarExtractor {

    private const val PIPE_BUFFER = 4 * 1024 * 1024
    private const val FILE_BUFFER = 1024 * 1024

    private val FHS_TOP_LEVEL = setOf(
        "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "media", "mnt",
        "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var"
    )

    /** Counts bytes read from the underlying (compressed) file. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }

    /**
     * @param archive  the downloaded / user-picked file (.tar, .tar.gz or .tar.xz;
     *                 detected by magic bytes, not by file name)
     * @param destDir  rootfs directory to extract into
     * @param onPercent 0..100, called only when the whole-number percent changes
     */
    fun extract(archive: File, destDir: File, onPercent: (Int) -> Unit = {}) {
        destDir.mkdirs()
        val compressedSize = archive.length().coerceAtLeast(1L)

        val counting = CountingInputStream(FileInputStream(archive).buffered(FILE_BUFFER))
        val decompressed = openDecompressed(archive, counting)

        // Decompress in a background thread, untar in this one.
        val pipeIn = PipedInputStream(PIPE_BUFFER)
        val pipeOut = PipedOutputStream(pipeIn)
        val producerError = AtomicReference<Throwable?>(null)
        // Set once the tar reader has legitimately finished; any "pipe closed"
        // error the producer hits afterwards (trailing zero padding) is expected.
        val consumerDone = AtomicBoolean(false)
        val producer = Thread({
            try {
                decompressed.use { src ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        pipeOut.write(buf, 0, n)
                    }
                }
            } catch (t: Throwable) {
                if (!consumerDone.get()) producerError.set(t)
            } finally {
                try { pipeOut.close() } catch (_: IOException) {}
            }
        }, "rootfs-decompress").apply { isDaemon = true }
        producer.start()

        var lastPercent = -1
        fun report() {
            val p = ((counting.count * 100) / compressedSize).toInt().coerceIn(0, 99)
            if (p != lastPercent) {
                lastPercent = p
                onPercent(p)
            }
        }

        try {
            TarArchiveInputStream(BufferedInputStream(pipeIn, FILE_BUFFER)).use { tar ->
                extractEntries(tar, destDir, ::report)
                // Must be set BEFORE use{} closes the pipe, or the producer's
                // resulting "pipe closed" error would look like a real failure.
                consumerDone.set(true)
            }
        } catch (t: Throwable) {
            // Prefer the decompressor's error (truncated / corrupt archive)
            // over the secondary "pipe closed" error it causes here.
            producerError.get()?.let { throw IOException("الملف المضغوط تالف أو ناقص: ${it.message}", it) }
            throw t
        } finally {
            try { pipeIn.close() } catch (_: IOException) {}
            producer.join(5_000)
        }
        producerError.get()?.let { throw IOException("الملف المضغوط تالف أو ناقص: ${it.message}", it) }
        onPercent(100)
    }

    private fun openDecompressed(file: File, counting: InputStream): InputStream {
        val header = ByteArray(6)
        file.inputStream().use { it.read(header) }
        return when {
            header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() ->
                GZIPInputStream(counting, 256 * 1024)
            header[0] == 0xFD.toByte() && header[1] == '7'.code.toByte() &&
                header[2] == 'z'.code.toByte() && header[3] == 'X'.code.toByte() &&
                header[4] == 'Z'.code.toByte() && header[5] == 0.toByte() ->
                XZInputStream(counting)
            else -> counting // plain .tar
        }
    }

    private fun extractEntries(tar: TarArchiveInputStream, destDir: File, report: () -> Unit) {
        val destPath = destDir.toPath().toAbsolutePath().normalize()
        var strip: String? = null   // wrapper folder to strip, decided on first entry
        var decided = false
        val deferredDirModes = ArrayList<Pair<File, Int>>()

        while (true) {
            val entry: TarArchiveEntry = tar.nextTarEntry ?: break
            var name = entry.name.removePrefix("./").trimStart('/')

            if (!decided && name.isNotEmpty()) {
                decided = true
                val first = name.trimEnd('/').substringBefore('/')
                // A lone non-FHS top-level folder ("kali-arm64/") is a wrapper.
                if (first !in FHS_TOP_LEVEL && (entry.isDirectory || name.contains('/'))) strip = first
            }
            val wrapper = strip
            if (wrapper != null) {
                name = when {
                    name.trimEnd('/') == wrapper -> ""
                    name.startsWith("$wrapper/") -> name.removePrefix("$wrapper/")
                    else -> name
                }
            }
            name = name.trimEnd('/')
            if (name.isEmpty()) { report(); continue }

            val out = File(destDir, name)
            // Zip-slip guard — lexical, NOT resolving symlinks (rootfs symlinks
            // are absolute paths that only make sense inside proot).
            if (!out.toPath().toAbsolutePath().normalize().startsWith(destPath)) { report(); continue }

            val mode = entry.mode and 0x1FF
            when {
                entry.isDirectory -> {
                    out.mkdirs()
                    // Owner rwx forced on so we can keep writing into it; the
                    // original mode is applied after everything is extracted.
                    chmod(out, mode or 0b111_000_000)
                    deferredDirModes.add(out to mode)
                }
                entry.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    deleteIfExists(out)
                    try {
                        Os.symlink(entry.linkName, out.absolutePath)
                    } catch (e: ErrnoException) {
                        throw IOException("تعذر إنشاء رابط رمزي $name -> ${entry.linkName}: ${e.message}", e)
                    }
                }
                entry.isLink -> { // hard link
                    out.parentFile?.mkdirs()
                    deleteIfExists(out)
                    var target = entry.linkName.removePrefix("./").trimStart('/')
                    if (wrapper != null && target.startsWith("$wrapper/")) {
                        target = target.removePrefix("$wrapper/")
                    }
                    val targetFile = File(destDir, target)
                    try {
                        Os.link(targetFile.absolutePath, out.absolutePath)
                    } catch (e: ErrnoException) {
                        // Android's SELinux policy often forbids link(): copy instead.
                        if (targetFile.isFile) targetFile.copyTo(out, overwrite = true)
                    }
                }
                entry.isFile -> {
                    out.parentFile?.mkdirs()
                    deleteIfExists(out)
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                    // Keep owner rw so later steps (installing our bridge CLI,
                    // proot's fake-root package installs) can modify the file.
                    chmod(out, mode or 0b110_000_000)
                }
                else -> { /* device nodes, FIFOs: /dev is bind-mounted by proot */ }
            }
            report()
        }

        // Restore real directory modes, deepest first.
        for ((dir, mode) in deferredDirModes.asReversed()) chmod(dir, mode or 0b111_000_000)
    }

    private fun chmod(file: File, mode: Int) {
        try { Os.chmod(file.absolutePath, mode) } catch (_: ErrnoException) {}
    }

    private fun deleteIfExists(file: File) {
        try { Files.deleteIfExists(file.toPath()) } catch (_: IOException) {}
    }

    /** Sum of regular-file sizes, never following symlinks. */
    fun sizeOfTree(dir: File): Long {
        var total = 0L
        Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: java.nio.file.Path, exc: IOException): java.nio.file.FileVisitResult =
                java.nio.file.FileVisitResult.CONTINUE
        })
        return total
    }

    /** Deletes a directory tree without following symlinks (safe for rootfs). */
    fun deleteTree(dir: File) {
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(d: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                try { Os.chmod(d.toString(), 0b111_000_000) } catch (_: ErrnoException) {}
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: java.nio.file.Path, exc: IOException?): java.nio.file.FileVisitResult {
                Files.deleteIfExists(d); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
