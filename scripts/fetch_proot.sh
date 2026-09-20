#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="app/src/main/jniLibs/arm64-v8a"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TERMUX_REPO_BASE="https://packages-cf.termux.dev/apt/termux-main"
PACKAGES_INDEX_URL="$TERMUX_REPO_BASE/dists/stable/main/binary-aarch64/Packages"

echo "==> Binary Beast: جلب فهرس حزم Termux الرسمي..."
curl -fL "$PACKAGES_INDEX_URL" -o "$TMP_DIR/Packages"

PROOT_RELATIVE_PATH="$(awk '
    /^Package: proot$/ { in_proot=1 }
    in_proot && /^Filename:/ { print $2; exit }
    /^$/ { in_proot=0 }
' "$TMP_DIR/Packages")"

if [ -z "$PROOT_RELATIVE_PATH" ]; then
    echo "خطأ: لم يتم العثور على حزمة proot في فهرس Termux." >&2
    exit 1
fi

PROOT_DEB_URL="$TERMUX_REPO_BASE/$PROOT_RELATIVE_PATH"
echo "==> تحميل proot الحالي فعليًا من: $PROOT_DEB_URL"
curl -fL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"

echo "==> فك حزمة .deb..."
cd "$TMP_DIR"
ar x proot.deb
mkdir -p data
tar -xf data.tar.* -C data

PROOT_BINARY="$(find data -type f -name proot | head -n1)"

if [ -z "$PROOT_BINARY" ]; then
    echo "خطأ: لم يتم العثور على binary اسمه proot." >&2
    exit 1
fi

mkdir -p "$OLDPWD/$DEST_DIR"
cp "$PROOT_BINARY" "$OLDPWD/$DEST_DIR/libproot.so"
chmod 755 "$OLDPWD/$DEST_DIR/libproot.so"

echo "==> تم: $DEST_DIR/libproot.so"
file "$OLDPWD/$DEST_DIR/libproot.so" || true
