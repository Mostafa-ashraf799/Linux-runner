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

# Termux's official apt repo, mirrored on cdimage.debian.org (Debian's
# infrastructure mirrors Termux's repo verbatim). If this specific
# version 404s in the future because Termux published a newer build,
# check https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/p/proot/
# for the current filename and update PROOT_DEB_URL below.
PROOT_DEB_URL="https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.78-1_aarch64.deb"

echo "==> Binary Beast: تحميل حزمة proot الرسمية من مستودع Termux..."
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

mkdir -p "$OLDPWD/$DEST_DIR"
cp "$PROOT_BINARY" "$OLDPWD/$DEST_DIR/libproot.so"
chmod 755 "$OLDPWD/$DEST_DIR/libproot.so"

echo "==> تم: $DEST_DIR/libproot.so"
file "$OLDPWD/$DEST_DIR/libproot.so" || true
