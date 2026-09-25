package com.binarybeast.linuxrunner

/**
 * Describes one selectable Linux distribution.
 *
 * rootfsUrl points at an official arm64 rootfs tarball. These are the same
 * kind of images used by proot-distro / Termux — plain tar.gz/tar.xz files,
 * nothing custom-built by us.
 *
 * NOTE: Kali's official rootfs is large (~1.5GB+) and heavier to run under
 * proot on constrained devices — it's included because it was in the
 * original request, but Alpine is the best "first try" for low-end phones.
 */
data class Distro(
    val id: String,
    val displayName: String,
    val description: String,
    val rootfsUrl: String,
    val approxDownloadSizeMb: Int,
    val defaultDesktop: DesktopEnvironment
)

enum class DesktopEnvironment(val packageNames: List<String>, val startCommand: String) {
    // xfce4-terminal / lxterminal included explicitly rather than assumed:
    // the desktop shortcuts (see ProotEngine.installDesktopShortcuts) rely
    // on one of these actually being present to show bridge command output.
    XFCE(
        packageNames = listOf("xfce4", "xfce4-goodies", "xfce4-terminal"),
        startCommand = "startxfce4"
    ),
    LXQT(
        packageNames = listOf("lxqt", "lxterminal"),
        startCommand = "startlxqt"
    )
}

object DistroCatalog {

    val ALL: List<Distro> = listOf(
        Distro(
            id = "ubuntu",
            displayName = "Ubuntu",
            description = "الأشهر والأكثر توافقًا مع البرامج — اختيار جيد للمبتدئين",
            rootfsUrl = "https://partner-images.canonical.com/oci/noble/current/ubuntu-noble-oci-arm64-root.tar.gz",
            approxDownloadSizeMb = 400,
            defaultDesktop = DesktopEnvironment.XFCE
        ),
        Distro(
            id = "debian",
            displayName = "Debian",
            description = "خفيف ومستقر جدًا، أساس Ubuntu نفسه",
            rootfsUrl = "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64/bookworm/rootfs.tar.xz",
            approxDownloadSizeMb = 130,
            defaultDesktop = DesktopEnvironment.XFCE
        ),
        Distro(
            id = "alpine",
            displayName = "Alpine Linux",
            description = "أخف توزيعة على الإطلاق — الأنسب للأجهزة الضعيفة أو التجربة الأولى",
            rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            approxDownloadSizeMb = 8,
            defaultDesktop = DesktopEnvironment.LXQT
        ),
        Distro(
            id = "kali",
            displayName = "Kali Linux",
            description = "لأدوات الأمن السيبراني والاختراق الأخلاقي — أثقل نسبيًا",
            // Kali renamed this file at some point — the old
            // "kalifs-arm64-full.tar.xz" name now 404s. Confirmed current
            // correct name via Kali's own bug tracker (bugs.kali.org #8917).
            rootfsUrl = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-full-arm64.tar.xz",
            approxDownloadSizeMb = 1500,
            defaultDesktop = DesktopEnvironment.XFCE
        )
    )

    fun byId(id: String): Distro = ALL.first { it.id == id }
}
