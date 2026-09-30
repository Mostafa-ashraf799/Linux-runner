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
# exist on every phone, but libtalloc does not — bundle it and rename the
# reference so Android's "lib*.so" packaging rule and the dynamic linker
# both find it.
echo "==> اعتماديات proot:"
readelf -d "$OUT/libproot.so" | grep NEEDED || true

NEEDED_TALLOC="$(readelf -d "$OUT/libproot.so" | grep -o 'libtalloc[^]]*' | head -n1 || true)"
if [ -n "$NEEDED_TALLOC" ]; then
    TALLOC_REL="$(awk '
        /^Package: libtalloc$/ { in_p=1 }
        in_p && /^Filename:/ { print $2; exit }
        /^$/ { in_p=0 }
    ' "$TMP_DIR/Packages")"
    if [ -z "$TALLOC_REL" ]; then
        echo "خطأ: proot يحتاج libtalloc ولم أجد حزمتها في فهرس Termux." >&2; exit 1
    fi
    curl -fL "$TERMUX_REPO_BASE/$TALLOC_REL" -o "$TMP_DIR/libtalloc.deb"
    mkdir -p "$TMP_DIR/talloc" && cd "$TMP_DIR/talloc"
    ar x ../libtalloc.deb && mkdir -p data && tar -xf data.tar.* -C data
    TALLOC_SO="$(find data \( -type f -o -type l \) -name 'libtalloc.so*' | head -n1)"
    cp -L "$TALLOC_SO" "$OUT/libtalloc.so"; chmod 755 "$OUT/libtalloc.so"
    patchelf --replace-needed "$NEEDED_TALLOC" libtalloc.so "$OUT/libproot.so"
    echo "==> تم ربط libtalloc ($NEEDED_TALLOC -> libtalloc.so)"
    cd "$TMP_DIR"
fi

# Fail the build loudly (instead of failing silently on the phone) if proot
# still needs a library we can't provide.
UNKNOWN="$(readelf -d "$OUT/libproot.so" | grep -o 'Shared library: \[[^]]*\]' \
    | sed 's/.*\[\(.*\)\]/\1/' | grep -vE '^(libc|libm|libdl|liblog|libtalloc)\.so' || true)"
if [ -n "$UNKNOWN" ]; then
    echo "خطأ: proot يعتمد على مكتبات غير متوفرة على أندرويد: $UNKNOWN" >&2
    exit 1
fi

echo "==> تم: $DEST_DIR"
ls -l "$OUT"
