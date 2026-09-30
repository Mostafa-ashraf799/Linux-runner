#!/usr/bin/env bash
# Binary Beast — Linux Runner
#
# Downloads the OFFICIAL proot .deb package from Termux's own apt
# repository (arm64/aarch64 build) and extracts the real proot binary
# from it — this is the actual proot maintained by the Termux project,
# not a random third-party binary. Places it where the Android build
# expects native libraries to live (jniLibs), renamed to libproot.so
# (Android's packaging only allows shipping arbitrary executables under
# jniLibs if they're named lib*.so — the file itself is still the plain
# proot ELF binary, untouched).
#
# Run locally with: ./scripts/fetch_proot.sh
# Also run automatically by the GitHub Actions build workflow.

set -euo pipefail

DEST_DIR="app/src/main/jniLibs/arm64-v8a"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Termux's official apt repository (packages.termux.dev / its CDN
# endpoint packages-cf.termux.dev). Rather than hardcoding a specific
# .deb filename/version — which breaks the moment Termux publishes a
# newer proot build and removes the old one — this script reads the
# repo's own package index ("Packages" file, the same file apt itself
# uses) and extracts whatever the CURRENT aarch64 proot filename and
# path actually are. This is the same mechanism `apt update && apt
# download proot` uses internally, just done manually with curl+grep
# since we don't have a full Termux/apt environment in CI.
TERMUX_REPO_BASE="https://packages-cf.termux.dev/apt/termux-main"
PACKAGES_INDEX_URL="$TERMUX_REPO_BASE/dists/stable/main/binary-aarch64/Packages"

echo "==> Binary Beast: جلب فهرس حزم Termux الرسمي..."
curl -fL "$PACKAGES_INDEX_URL" -o "$TMP_DIR/Packages"

# The Packages file is a series of RFC822-style stanzas, one per package,
# separated by blank lines. Find the "Package: proot" stanza and pull its
# Filename field (relative to the repo base) — e.g.
# "pool/main/p/proot/proot_5.1.108-1_aarch64.deb".
PROOT_RELATIVE_PATH="$(awk '
    /^Package: proot$/ { in_proot=1 }
    in_proot && /^Filename:/ { print $2; exit }
    /^$/ { in_proot=0 }
' "$TMP_DIR/Packages")"

if [ -z "$PROOT_RELATIVE_PATH" ]; then
    echo "خطأ: لم يتم العثور على حزمة proot في فهرس Termux — راجع $PACKAGES_INDEX_URL يدويًا." >&2
    exit 1
fi

PROOT_DEB_URL="$TERMUX_REPO_BASE/$PROOT_RELATIVE_PATH"
echo "==> تحميل proot الحالي فعليًا من: $PROOT_DEB_URL"
curl -fL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"

echo "==> فك حزمة .deb..."
cd "$TMP_DIR"
ar x proot.deb                      # .deb is an ar archive containing control.tar.* and data.tar.*
mkdir -p data
tar -xf data.tar.* -C data          # handles .xz/.gz/.zst transparently with modern tar

# The actual binary inside a Termux .deb lives under
# ./data/data/com.termux/files/usr/bin/proot
PROOT_BINARY="$(find data -type f -name proot | head -n1)"

if [ -z "$PROOT_BINARY" ]; then
    echo "خطأ: لم يتم العثور على binary اسمه proot جوه الحزمة — تأكد إن الرابط لسه صالح." >&2
    exit 1
fi

OUT="$OLDPWD/$DEST_DIR"
mkdir -p "$OUT"
cp "$PROOT_BINARY" "$OUT/libproot.so"
chmod 755 "$OUT/libproot.so"

# ---- proot's loader ---------------------------------------------------
# Since Android 10 an app may only execute files that live in its native
# library dir. proot needs a small helper ("loader") executable to start the
# guest programs, so it has to be shipped the same way (lib*.so name) and
# pointed to at runtime through PROOT_LOADER (see ProotEngine.kt).
LOADER="$(find data -type f -path '*libexec/proot/loader' | head -n1)"
LOADER32="$(find data -type f -path '*libexec/proot/loader32' | head -n1)"
if [ -n "$LOADER" ]; then
    cp "$LOADER" "$OUT/libproot-loader.so"; chmod 755 "$OUT/libproot-loader.so"
    echo "==> loader: $OUT/libproot-loader.so"
else
    echo "تحذير: لم يتم العثور على proot loader داخل الحزمة (قد يكون مدمجاً في الـ binary)." >&2
fi
if [ -n "$LOADER32" ]; then
    cp "$LOADER32" "$OUT/libproot-loader32.so"; chmod 755 "$OUT/libproot-loader32.so"
fi

# ---- shared-library dependencies -------------------------------------
# Termux's proot is dynamically linked. Bionic libs (libc/libm/libdl/liblog)
# exist on every phone, but Termux-specific ones (libtalloc, libandroid-shmem)
# do not, so they are bundled from Termux's own packages. Android only
# packages files named lib*.so, so a versioned name like libtalloc.so.2 is
# renamed inside proot's NEEDED entry with patchelf.
echo "==> اعتماديات proot:"
readelf -d "$OUT/libproot.so" | grep NEEDED || true

# bundle_lib <termux-package-name> <soname-as-referenced-in-NEEDED>
bundle_lib() {
    local pkg="$1" soname="$2" target rel dir
    target="${soname%%.so*}.so"          # libtalloc.so.2 -> libtalloc.so
    rel="$(awk -v p="Package: $pkg" '
        $0 == p { in_p=1 }
        in_p && /^Filename:/ { print $2; exit }
        /^$/ { in_p=0 }
    ' "$TMP_DIR/Packages")"
    if [ -z "$rel" ]; then
        echo "خطأ: proot يحتاج $soname ولم أجد حزمة $pkg في فهرس Termux." >&2; exit 1
    fi
    dir="$TMP_DIR/bundle-$pkg"; mkdir -p "$dir"
    curl -fL "$TERMUX_REPO_BASE/$rel" -o "$dir/pkg.deb"
    ( cd "$dir" && ar x pkg.deb && mkdir -p data && tar -xf data.tar.* -C data )
    local so
    so="$(find "$dir/data" \( -type f -o -type l \) -name "${target%.so}.so*" | head -n1)"
    if [ -z "$so" ]; then echo "خطأ: لم أجد $target داخل حزمة $pkg" >&2; exit 1; fi
    cp -L "$so" "$OUT/$target"; chmod 755 "$OUT/$target"
    if [ "$soname" != "$target" ]; then
        patchelf --replace-needed "$soname" "$target" "$OUT/libproot.so"
    fi
    echo "==> تم ربط $pkg ($soname -> $target)"
}

NEEDED_TALLOC="$(readelf -d "$OUT/libproot.so" | grep -o 'libtalloc[^]]*' | head -n1 || true)"
[ -n "$NEEDED_TALLOC" ] && bundle_lib libtalloc "$NEEDED_TALLOC"
if readelf -d "$OUT/libproot.so" | grep -q 'libandroid-shmem.so'; then
    bundle_lib libandroid-shmem libandroid-shmem.so
fi

# Fail the build loudly (instead of failing silently on the phone) if anything
# we ship still needs a library that is neither on Android nor bundled here.
for f in "$OUT"/libproot.so "$OUT"/libtalloc.so "$OUT"/libandroid-shmem.so; do
    [ -f "$f" ] || continue
    for need in $(readelf -d "$f" | grep -o 'Shared library: \[[^]]*\]' | sed 's/.*\[\(.*\)\]/\1/'); do
        case "$need" in
            libc.so|libm.so|libdl.so|liblog.so) ;;
            *) [ -f "$OUT/$need" ] || { echo "خطأ: $(basename "$f") يعتمد على $need وهي غير متوفرة على أندرويد ولا مضمّنة." >&2; exit 1; } ;;
        esac
    done
done

echo "==> تم: $DEST_DIR"
ls -l "$OUT"
