package com.binarybeast.linuxrunner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Wraps the proot binary and automates everything the user previously had
 * to type by hand in Termux:
 *   1. download the rootfs tarball
 *   2. extract it
 *   3. install XFCE + a VNC server INSIDE the rootfs (first run only)
 *   4. start vncserver automatically on every launch
 *
 * All of this runs from LinuxSessionService so it survives screen
 * navigation and shows a persistent notification (Android requires this
 * for any long-running background work).
 */
class ProotEngine(
    private val context: Context,
    private val distro: Distro
) {
    private val storage = DistroStorageManager(context)
    private val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        // Packaged as a .so under jniLibs/arm64-v8a/ — Android allows shipping
        // arbitrary native executables this way without needing root.

    sealed class SetupProgress {
        data class Downloading(val percent: Int) : SetupProgress()
        object CopyingLocalFile : SetupProgress()
        object Extracting : SetupProgress()
        object InstallingDesktop : SetupProgress()
        object Done : SetupProgress()
        data class Failed(val reason: String) : SetupProgress()
        /** Extraction would exceed the user's configured storage cap for this distro. */
        data class StorageCapExceeded(val usedMb: Long, val capMb: Int) : SetupProgress()
    }

    /** Where the rootfs tarball for this run comes from. */
    sealed class RootfsSource {
        /** Default path: download the official tarball from distro.rootfsUrl. */
        object Download : RootfsSource()
        /** User picked an already-downloaded tarball from their device (e.g. Downloads folder). */
        data class LocalFile(val uri: android.net.Uri) : RootfsSource()
    }

    /**
     * Runs the full first-time setup: get the rootfs (download OR a file the
     * user picked) -> extract -> install XFCE + VNC server inside it. Emits
     * progress via the callback. Safe to call again later; it skips steps
     * already completed.
     */
    suspend fun ensureInstalled(
        source: RootfsSource = RootfsSource.Download,
        onProgress: (SetupProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (storage.isInstalled(distro)) {
                onProgress(SetupProgress.Done)
                return@withContext
            }

            when (source) {
                is RootfsSource.Download ->
                    downloadRootfs(distro) { percent -> onProgress(SetupProgress.Downloading(percent)) }
                is RootfsSource.LocalFile -> {
                    onProgress(SetupProgress.CopyingLocalFile)
                    copyLocalRootfs(source.uri, distro)
                }
            }

            onProgress(SetupProgress.Extracting)
            extractRootfs(distro)

            // Enforced right after extraction (software cap — see roadmap
            // note on why a real fixed-size mount isn't possible without
            // root): if the extracted rootfs already exceeds the user's
            // configured cap, stop before installing anything into it and
            // let the user either raise the cap or pick a lighter distro.
            val usedMb = storage.currentUsageMb(distro)
            val capMb = storage.getStorageCapMb(distro)
            if (usedMb > capMb) {
                storage.deleteDistro(distro)
                onProgress(SetupProgress.StorageCapExceeded(usedMb, capMb))
                return@withContext
            }

            onProgress(SetupProgress.InstallingDesktop)
            installDesktopEnvironment(distro)

            storage.markInstalled(distro)
            onProgress(SetupProgress.Done)
        } catch (e: Exception) {
            onProgress(SetupProgress.Failed(e.message ?: "خطأ غير معروف"))
        }
    }

    /**
     * Copies a rootfs tarball the user selected via the system file picker
     * (SAF content:// Uri, typically from Downloads) into our cache, in the
     * same spot downloadRootfs() would have placed it — so extractRootfs()
     * works identically regardless of where the file came from.
     */
    private fun copyLocalRootfs(uri: android.net.Uri, distro: Distro) {
        val destination = storage.downloadedTarballFile(distro)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("تعذر فتح الملف المختار")
    }

    private fun downloadRootfs(distro: Distro, onPercent: (Int) -> Unit) {
        val destination = storage.downloadedTarballFile(distro)
        val connection = URL(distro.rootfsUrl).openConnection()
        val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L

        connection.getInputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onPercent(((downloaded * 100) / totalBytes).toInt())
                    }
                }
            }
        }
    }

    private fun extractRootfs(distro: Distro) {
        val tarball = storage.downloadedTarballFile(distro)
        val destDir = storage.rootfsDir(distro)
        val tarFile = File(context.cacheDir, "${distro.id}.tar")

        // Auto-detect compression by magic bytes instead of trusting the
        // filename/URL extension — important now that the file can come
        // from the user's own Downloads folder (any name, any extension).
        when (detectCompression(tarball)) {
            Compression.GZIP -> GZIPInputStream(tarball.inputStream()).use { gzip ->
                FileOutputStream(tarFile).use { out -> gzip.copyTo(out) }
            }
            Compression.XZ -> XZInputStream(tarball.inputStream()).use { xz ->
                FileOutputStream(tarFile).use { out -> xz.copyTo(out) }
            }
            Compression.NONE -> tarball.copyTo(tarFile, overwrite = true)
        }

        TarExtractor.extract(tarFile, destDir)

        tarball.delete()
        tarFile.delete()
    }

    private enum class Compression { GZIP, XZ, NONE }

    private fun detectCompression(file: File): Compression {
        val header = ByteArray(6)
        file.inputStream().use { it.read(header) }
        return when {
            header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() -> Compression.GZIP
            header.contentEquals(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00)) -> Compression.XZ
            else -> Compression.NONE
        }
    }

    /** Runs, inside the freshly extracted rootfs, the same commands we tested manually in Termux. */
    private fun installDesktopEnvironment(distro: Distro) {
        val packageManagerInstall = when (distro.id) {
            // websockify lives in Alpine's "community" repo, not "main"
            // (confirmed against pkgs.alpinelinux.org) — apk add
            // --repository enables it inline for this one install without
            // editing /etc/apk/repositories by hand.
            "alpine" -> "apk update && " +
                "apk add ${distro.defaultDesktop.packageNames.joinToString(" ")} tigervnc && " +
                "apk add --repository=http://dl-cdn.alpinelinux.org/alpine/latest-stable/community websockify"
            else -> "apt update && apt install -y ${distro.defaultDesktop.packageNames.joinToString(" ")} tigervnc-standalone-server websockify"
        }
        runInProot(distro, packageManagerInstall)

        // Write the xstartup file automatically instead of asking the user to.
        val xstartup = File(storage.rootfsDir(distro), "root/.vnc/xstartup")
        xstartup.parentFile?.mkdirs()
        xstartup.writeText("#!/bin/sh\n${distro.defaultDesktop.startCommand} &\n")
        xstartup.setExecutable(true)

        setVncPasswordNonInteractively(distro)
        installBridgeCli(distro)
        installDesktopShortcuts(distro)
    }

    /**
     * Copies the hardware-bridge CLI script (assets/bridge-cli/) into the
     * rootfs and makes it callable as a plain command (`binarybeast-bridge`)
     * from any shell script inside the distro — this is the "small library
     * dropped inside every rootfs" mentioned in the roadmap for talking to
     * HardwareBridgeServer running on the Android side.
     */
    private fun installBridgeCli(distro: Distro) {
        val rootfs = storage.rootfsDir(distro)
        val binDir = File(rootfs, "usr/local/bin")
        binDir.mkdirs()

        val script = File(binDir, "binarybeast-bridge")
        context.assets.open("bridge-cli/binarybeast-bridge.py").use { input ->
            script.outputStream().use { output -> input.copyTo(output) }
        }
        script.setExecutable(true, false)

        // python3 is required to run it — installed alongside the desktop
        // packages if not already pulled in as a dependency.
        val ensurePython3 = when (distro.id) {
            "alpine" -> "apk add --no-cache python3"
            else -> "apt install -y python3"
        }
        runInProot(distro, ensurePython3)
    }

    /**
     * Drops real .desktop launcher files onto the XFCE/LXQt desktop so the
     * hardware bridge is actually reachable by tapping an icon — not just
     * a CLI command the user would need a terminal to run. Each launcher
     * runs binarybeast-bridge and shows the JSON result in a simple
     * terminal-output popup (xfce4-terminal/lxterminal, whichever the
     * desktop environment ships) so the result is visible without the
     * user needing to know shell redirection.
     */
    private fun installDesktopShortcuts(distro: Distro) {
        val desktopDir = File(storage.rootfsDir(distro), "root/Desktop")
        desktopDir.mkdirs()

        val terminalCommand = when (distro.defaultDesktop) {
            DesktopEnvironment.XFCE -> "xfce4-terminal --hold -e"
            DesktopEnvironment.LXQT -> "lxterminal --hold -e"
        }

        data class Shortcut(val fileName: String, val label: String, val bridgeCommand: String, val icon: String)

        val shortcuts = listOf(
            Shortcut("wifi-status.desktop", "حالة الواي فاي", "binarybeast-bridge wifi_status", "network-wireless"),
            Shortcut("bluetooth-devices.desktop", "أجهزة البلوتوث المقترنة", "binarybeast-bridge bt_paired_devices", "bluetooth"),
            Shortcut("screen-brightness.desktop", "سطوع الشاشة الحالي", "binarybeast-bridge screen_get_brightness", "display-brightness")
        )

        shortcuts.forEach { shortcut ->
            val file = File(desktopDir, shortcut.fileName)
            file.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=${shortcut.label}
                Comment=Binary Beast — جسر التحكم بالهاردوير
                Exec=sh -c '$terminalCommand "${shortcut.bridgeCommand}; echo; read -p \"اضغط Enter للإغلاق\""'
                Icon=${shortcut.icon}
                Terminal=false
                """.trimIndent()
            )
            file.setExecutable(true, false)
        }
    }

    /**
     * vncserver normally prompts interactively for a password the first
     * time it runs (that's the manual step we did by hand in Termux).
     * vncpasswd supports reading the password from stdin instead, which
     * lets us set a fixed generated password automatically with no
     * interactive prompt for the user.
     *
     * The password is generated once per distro and stored in
     * SharedPreferences (not hardcoded) so DesktopViewerActivity can read
     * it back when connecting the VNC viewer.
     */
    private fun setVncPasswordNonInteractively(distro: Distro) {
        val password = vncPassword(distro)
        val vncDir = File(storage.rootfsDir(distro), "root/.vnc")
        vncDir.mkdirs()

        // `vncpasswd -f` reads the password from stdin and writes the
        // encrypted passwd file to stdout — no TTY/interactive input needed.
        val rootfs = storage.rootfsDir(distro)
        val process = ProcessBuilder(
            prootBinary.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root",
            "/bin/sh", "-c", "vncpasswd -f > /root/.vnc/passwd"
        ).redirectErrorStream(true).start()

        process.outputStream.use { it.write((password + "\n").toByteArray()) }
        process.waitFor()

        File(vncDir, "passwd").setReadable(true, true)
    }

    /** Deterministic-but-not-guessable per-distro password, generated once and cached. */
    private fun vncPassword(distro: Distro): String {
        val prefs = context.getSharedPreferences("vnc_passwords", Context.MODE_PRIVATE)
        prefs.getString(distro.id, null)?.let { return it }

        val generated = (1..8).map { ('a'..'z') + ('0'..'9') }
            .map { it.random() }.joinToString("")
        prefs.edit().putString(distro.id, generated).apply()
        return generated
    }

    /** Exposed so DesktopViewerActivity can retrieve the password when connecting the viewer. */
    fun currentVncPassword(): String = vncPassword(distro)

    /**
     * Starts the VNC server automatically (equivalent of the manual
     * `vncserver :1 -geometry ... -depth 24` step), then starts websockify
     * bridging that VNC port to a WebSocket port — required because noVNC
     * (running in a WebView) speaks WebSocket, not raw VNC/RFB directly.
     * Returns the WebSocket port the viewer should connect to.
     */
    suspend fun startDesktopSession(): Int = withContext(Dispatchers.IO) {
        val vncPort = 5901
        val websocketPort = 6901

        runInProot(distro, "vncserver :1 -geometry 1280x720 -depth 24")

        // websockify ships with most noVNC installs; install it alongside
        // the desktop environment if missing, then bridge vnc<->websocket.
        // Run detached (&) since this call must return once the bridge is
        // listening, not block for the lifetime of the session.
        runInProotDetached(
            distro,
            "websockify $websocketPort localhost:$vncPort"
        )

        websocketPort
    }

    fun stopDesktopSession() {
        runInProot(distro, "vncserver -kill :1")
        runInProot(distro, "pkill websockify")
    }

    private fun runInProot(distro: Distro, shellCommand: String) {
        val rootfs = storage.rootfsDir(distro)
        val process = ProcessBuilder(
            prootBinary.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm",
            "/bin/sh", "-c", shellCommand
        ).redirectErrorStream(true).start()
        process.waitFor()
    }

    /**
     * Same as runInProot but does not block waiting for the command to
     * exit — used for long-running background processes (websockify) that
     * are meant to keep running after this call returns. The process is
     * tied to this ProotEngine instance's lifetime; stopDesktopSession()
     * kills it via pkill inside the rootfs.
     */
    private fun runInProotDetached(distro: Distro, shellCommand: String) {
        val rootfs = storage.rootfsDir(distro)
        ProcessBuilder(
            prootBinary.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm",
            "/bin/sh", "-c", "$shellCommand &"
        ).redirectErrorStream(true).start()
        // Intentionally no waitFor() — this call returns immediately once
        // the backgrounded process has been launched inside the rootfs.
    }
}
