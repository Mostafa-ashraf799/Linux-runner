package com.binarybeast.linuxrunner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

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
        /**
         * percent is -1 when the exact progress can't be known yet (e.g.
         * before the file size is read); the UI treats -1 as "still
         * working, show an indeterminate/spinning state" rather than a
         * frozen 0%. Large distros like Kali (1.5GB+) can take several
         * minutes here — this exists specifically so the screen never
         * again looks stuck with zero feedback the way it did before.
         */
        data class Extracting(val percent: Int) : SetupProgress()
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

            // Retry-friendly: if extraction already finished on a previous
            // attempt (and only the apt/apk step failed, e.g. network drop),
            // skip straight to the desktop install instead of re-downloading
            // and re-extracting gigabytes.
            if (!storage.isExtracted(distro)) {
                try {
                    // Start clean: a half-extracted tree from an earlier failed
                    // attempt would otherwise get mixed with the new one.
                    storage.deleteDistro(distro)

                    when (source) {
                        is RootfsSource.Download ->
                            downloadRootfs(distro) { percent -> onProgress(SetupProgress.Downloading(percent)) }
                        is RootfsSource.LocalFile -> {
                            onProgress(SetupProgress.CopyingLocalFile)
                            copyLocalRootfs(source.uri, distro)
                        }
                    }

                    onProgress(SetupProgress.Extracting(-1))
                    extractRootfs(distro) { percent -> onProgress(SetupProgress.Extracting(percent)) }
                } catch (e: Throwable) {
                    // Bad/partial archive: never leave it around to be reused.
                    storage.downloadedTarballFile(distro).delete()
                    storage.deleteDistro(distro)
                    throw e
                }

                // Enforced right after extraction (software cap — a real
                // fixed-size mount isn't possible without root): if the
                // extracted rootfs already exceeds the user's cap, stop
                // before installing anything and let the user raise the cap.
                val usedMb = storage.currentUsageMb(distro)
                val capMb = storage.getStorageCapMb(distro)
                if (usedMb > capMb) {
                    storage.deleteDistro(distro)
                    onProgress(SetupProgress.StorageCapExceeded(usedMb, capMb))
                    return@withContext
                }
                storage.markExtracted(distro)
            }

            onProgress(SetupProgress.InstallingDesktop)
            prepareRootfs(distro)
            installDesktopEnvironment(distro)

            storage.markInstalled(distro)
            onProgress(SetupProgress.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onProgress(SetupProgress.Failed(e.message ?: e.javaClass.simpleName))
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

        // HttpURLConnection parses ALL response headers inside connect();
        // a malformed value in any of them can throw NumberFormatException
        // before we ever look at Content-Length (seen in production as
        // "time=..." fragments). The whole connect + header-read sequence
        // is wrapped so that case degrades to "size unknown" instead of
        // failing the download.
        var totalBytes = -1L
        var status = 200
        val connection = (URL(distro.rootfsUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BinaryBeast-LinuxRunner/1.0")
            try {
                connect()
                status = responseCode
                totalBytes = contentLengthLong.takeIf { it > 0 } ?: -1L
            } catch (e: NumberFormatException) {
                totalBytes = -1L
            }
        }

        if (status !in 200..299) {
            connection.disconnect()
            throw java.io.IOException("فشل التحميل (HTTP $status) — الرابط قد يكون قديماً: ${distro.rootfsUrl}")
        }

        var downloaded = 0L
        var lastPercent = -1
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(256 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val p = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (p != lastPercent) { lastPercent = p; onPercent(p) }
                    }
                }
            }
        }
        connection.disconnect()
        if (totalBytes > 0 && downloaded < totalBytes) {
            throw java.io.IOException("التحميل انقطع قبل الاكتمال ($downloaded من $totalBytes بايت)")
        }
    }

    /**
     * Streams the archive (.tar / .tar.gz / .tar.xz — detected by magic
     * bytes) straight into files, in one pass and two threads
     * (decompress + untar). No intermediate multi-GB .tar file, and the
     * percentage is real: compressed bytes consumed / compressed size.
     * See TarExtractor for details (symlinks, permissions, wrapper dirs).
     */
    private fun extractRootfs(distro: Distro, onPercent: (Int) -> Unit) {
        val tarball = storage.downloadedTarballFile(distro)
        TarExtractor.extract(tarball, storage.rootfsDir(distro), onPercent)
        tarball.delete()
    }

    // ------------------------------------------------------------------
    // Rootfs preparation & desktop installation
    // ------------------------------------------------------------------

    /**
     * Small fixes a fresh rootfs tarball needs before proot can run apt/apk
     * inside it on Android. Done from Kotlin (plain file writes) so they
     * work even before the first command runs in proot.
     */
    private fun prepareRootfs(distro: Distro) {
        val rootfs = storage.rootfsDir(distro)

        // DNS: many rootfs images ship an empty or dangling (systemd-resolved)
        // /etc/resolv.conf; without it apt/apk cannot resolve any host.
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val resolv = File(etc, "resolv.conf")
        try { java.nio.file.Files.deleteIfExists(resolv.toPath()) } catch (_: Exception) {}
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        val hosts = File(etc, "hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")

        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        File(rootfs, "proc").mkdirs()
        File(rootfs, "sys").mkdirs()
        File(rootfs, "dev").mkdirs()

        // apt normally drops privileges to the "_apt" user via setgroups(),
        // which fails under proot ("setgroups 65534 failed"). Telling it to
        // stay root is the standard proot workaround.
        if (distro.id != "alpine") {
            val aptConf = File(etc, "apt/apt.conf.d").apply { mkdirs() }
            File(aptConf, "01-binarybeast-proot").writeText("APT::Sandbox::User \"root\";\n")
        }
    }

    /** Runs, inside the freshly extracted rootfs, the equivalent of the manual Termux steps. */
    private fun installDesktopEnvironment(distro: Distro) {
        val de = distro.defaultDesktop.packageNames.joinToString(" ")
        val install = when (distro.id) {
            "alpine" ->
                // websockify lives in Alpine's "community" repo.
                "apk update && " +
                    "apk add $de tigervnc dbus font-dejavu python3 && " +
                    "(apk add websockify || apk add --repository=http://dl-cdn.alpinelinux.org/alpine/latest-stable/community websockify)"
            else ->
                // xauth/dbus-x11 are needed by XFCE/LXQt sessions; procps gives pkill;
                // python3 is needed by the hardware-bridge CLI.
                "apt-get update && " +
                    "apt-get install -y $de tigervnc-standalone-server tigervnc-common websockify " +
                    "dbus-x11 xauth x11-xserver-utils procps python3"
        }
        runInProot(distro, install, timeoutMinutes = 90)

        writeSessionScript(distro)
        setVncPasswordNonInteractively(distro)
        installBridgeCli(distro)
        installDesktopShortcuts(distro)
    }

    /**
     * The script that brings the whole desktop up inside the rootfs:
     * Xvnc (VNC server + X server in one) -> desktop environment ->
     * websockify (VNC <-> WebSocket for noVNC). Talking to Xvnc directly
     * avoids the perl `vncserver` wrapper, which needs extra packages
     * (perl, xauth, hostname) that minimal rootfs images don't have.
     */
    private fun writeSessionScript(distro: Distro) {
        val script = File(storage.rootfsDir(distro), "root/.vnc/start-session.sh")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            #!/bin/sh
            export HOME=/root USER=root LOGNAME=root DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/runtime-root
            mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
            mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix

            XVNC="${'$'}(command -v Xvnc || command -v Xtigervnc)"
            if [ -z "${'$'}XVNC" ]; then echo "Xvnc not found (tigervnc not installed?)"; exit 1; fi

            "${'$'}XVNC" :1 -geometry 1280x720 -depth 24 -rfbport 5901 \
                -rfbauth /root/.vnc/passwd -SecurityTypes VncAuth \
                -localhost -AlwaysShared &
            # wait until the X socket exists (max ~20s)
            i=0; while [ ! -S /tmp/.X11-unix/X1 ] && [ ${'$'}i -lt 40 ]; do sleep 0.5; i=${'$'}((i+1)); done

            if command -v dbus-launch >/dev/null 2>&1; then
                dbus-launch --exit-with-session ${distro.defaultDesktop.startCommand} &
            else
                ${distro.defaultDesktop.startCommand} &
            fi

            exec websockify 6901 127.0.0.1:5901
            """.trimIndent() + "\n"
        )
        script.setExecutable(true, false)
    }

    /**
     * Copies the hardware-bridge CLI script (assets/bridge-cli/) into the
     * rootfs and makes it callable as a plain command (`binarybeast-bridge`)
     * from any shell script inside the distro. python3 is installed with
     * the desktop packages above.
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
    }

    /**
     * Drops real .desktop launcher files onto the desktop so the hardware
     * bridge is reachable by tapping an icon, not only from a terminal.
     */
    private fun installDesktopShortcuts(distro: Distro) {
        val desktopDir = File(storage.rootfsDir(distro), "root/Desktop")
        desktopDir.mkdirs()

        data class Shortcut(val fileName: String, val label: String, val bridgeCommand: String, val icon: String)

        val shortcuts = listOf(
            Shortcut("wifi-status.desktop", "حالة الواي فاي", "binarybeast-bridge wifi_status", "network-wireless"),
            Shortcut("bluetooth-devices.desktop", "أجهزة البلوتوث المقترنة", "binarybeast-bridge bt_paired_devices", "bluetooth"),
            Shortcut("screen-brightness.desktop", "سطوع الشاشة الحالي", "binarybeast-bridge screen_get_brightness", "display-brightness")
        )

        shortcuts.forEach { shortcut ->
            // xfce4-terminal keeps the window open with --hold; lxterminal has
            // no such flag, so it waits for Enter inside a small sh wrapper.
            val exec = when (distro.defaultDesktop) {
                DesktopEnvironment.XFCE -> "xfce4-terminal --hold -e \"${shortcut.bridgeCommand}\""
                DesktopEnvironment.LXQT -> "lxterminal -e sh -c \"${shortcut.bridgeCommand}; echo; echo Press Enter; read _\""
            }
            val file = File(desktopDir, shortcut.fileName)
            file.writeText(
                "[Desktop Entry]\n" +
                    "Type=Application\n" +
                    "Name=${shortcut.label}\n" +
                    "Comment=Binary Beast - hardware bridge\n" +
                    "Exec=$exec\n" +
                    "Icon=${shortcut.icon}\n" +
                    "Terminal=false\n"
            )
            file.setExecutable(true, false)
        }
    }

    /**
     * `vncpasswd -f` reads the password on stdin and writes the obfuscated
     * passwd file to stdout, so no interactive prompt is needed. The
     * password is generated once per distro and kept in SharedPreferences
     * so DesktopViewerActivity can read it when connecting the viewer.
     */
    private fun setVncPasswordNonInteractively(distro: Distro) {
        val password = vncPassword(distro)
        File(storage.rootfsDir(distro), "root/.vnc").mkdirs()
        // The password is alphanumeric only (see vncPassword), so it is safe
        // to embed in a single-quoted shell string.
        runInProot(
            distro,
            "printf '%s\\n' '$password' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"
        )
    }

    /** Random per-distro password, generated once and cached. */
    private fun vncPassword(distro: Distro): String {
        val prefs = context.getSharedPreferences("vnc_passwords", Context.MODE_PRIVATE)
        prefs.getString(distro.id, null)?.let { return it }

        val alphabet = ('a'..'z') + ('0'..'9')
        val generated = (1..8).map { alphabet.random() }.joinToString("")
        prefs.edit().putString(distro.id, generated).apply()
        return generated
    }

    /** Exposed so DesktopViewerActivity can retrieve the password when connecting the viewer. */
    fun currentVncPassword(): String = vncPassword(distro)

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    private var sessionProcess: Process? = null
    private val sessionLog: File
        get() = File(storage.rootfsDir(distro), "root/.vnc/session.log")

    /**
     * Starts Xvnc + desktop + websockify inside the rootfs (one detached
     * proot process running /root/.vnc/start-session.sh) and waits until
     * the WebSocket port really accepts connections. Returns that port.
     */
    suspend fun startDesktopSession(): Int = withContext(Dispatchers.IO) {
        val websocketPort = 6901

        if (sessionProcess?.isAlive == true && canConnect(websocketPort)) return@withContext websocketPort
        stopDetachedProcess()

        sessionLog.parentFile?.mkdirs()
        sessionLog.writeText("")
        sessionProcess = ProcessBuilder(prootCommand(distro, "exec /bin/sh /root/.vnc/start-session.sh"))
            .apply { applyProotEnvironment(environment()) }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(sessionLog))
            .start()

        // Xvnc + desktop + websockify can take a while on first boot.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val p = sessionProcess
            if (p == null || !p.isAlive) {
                throw java.io.IOException("توقفت جلسة سطح المكتب فجأة:\n" + logTail())
            }
            if (canConnect(websocketPort)) return@withContext websocketPort
            delay(500)
        }
        stopDetachedProcess()
        throw java.io.IOException("انتهت مهلة تشغيل سطح المكتب:\n" + logTail())
    }

    fun stopDesktopSession() {
        stopDetachedProcess()
    }

    private fun stopDetachedProcess() {
        val p = sessionProcess ?: return
        sessionProcess = null
        // --kill-on-exit makes proot take every process in the rootfs down with it.
        p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
    } catch (_: Exception) { false }

    private fun logTail(lines: Int = 15): String =
        try { sessionLog.readLines().takeLast(lines).joinToString("\n") } catch (_: Exception) { "" }

    // ------------------------------------------------------------------
    // proot invocation
    // ------------------------------------------------------------------

    class ProotCommandException(message: String) : Exception(message)

    private fun prootCommand(distro: Distro, shellCommand: String): List<String> {
        val rootfs = storage.rootfsDir(distro)
        return listOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",   // hard links are blocked by Android's SELinux; emulate with symlinks
            "-0",               // fake root (uid 0) so apt/apk are willing to run
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${File(rootfs, "tmp").absolutePath}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/sh", "-c", shellCommand
        )
    }

    private fun applyProotEnvironment(env: MutableMap<String, String>) {
        val nativeDir = context.applicationInfo.nativeLibDir
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        // Android 10+ forbids executing files from app data; the loader must
        // be shipped as a native library (see scripts/fetch_proot.sh).
        val loader = File(nativeDir, "libproot-loader.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        val loader32 = File(nativeDir, "libproot-loader32.so")
        if (loader32.exists()) env["PROOT_LOADER_32"] = loader32.absolutePath
        env["LD_LIBRARY_PATH"] = nativeDir + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
    }

    /**
     * Runs one command inside the rootfs, waits for it (with a timeout),
     * drains its output so it can never block on a full pipe, and throws
     * ProotCommandException with the tail of the output when it fails —
     * so the setup screen shows the real reason (e.g. an apt error)
     * instead of continuing as if everything worked.
     */
    private fun runInProot(distro: Distro, shellCommand: String, timeoutMinutes: Long = 30): String {
        val process = ProcessBuilder(prootCommand(distro, shellCommand))
            .apply { applyProotEnvironment(environment()) }
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val drainer = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.appendLine(line)
                        // keep memory bounded on very chatty installs
                        if (output.length > 64_000) output.delete(0, output.length - 32_000)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw ProotCommandException("انتهت مهلة الأمر ($timeoutMinutes دقيقة): $shellCommand")
        }
        drainer.join(2_000)

        val text = synchronized(output) { output.toString() }
        if (process.exitValue() != 0) {
            throw ProotCommandException(
                "فشل الأمر (كود ${process.exitValue()}): ${shellCommand.take(120)}\n" +
                    text.lines().takeLast(15).joinToString("\n")
            )
        }
        return text
    }
}
