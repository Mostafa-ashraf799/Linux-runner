#!/usr/bin/env bash
# Binary Beast — Linux Runner
#
# Downloads the OFFICIAL noVNC release tarball (from the noVNC project's
# own GitHub releases, https://github.com/novnc/noVNC) and copies its
# core/ folder into app/src/main/assets/novnc/core — this is what
# vnc.html (already committed in this repo) imports as
# './core/rfb.js'. Not vendoring the tarball's contents directly in this
# repo since it's a full third-party library under its own license
# (MPL-2.0); this script fetches it fresh from the source every time,
# same as a package manager would.
#
# Run locally with: ./scripts/fetch_novnc.sh
# Also run automatically by the GitHub Actions build workflow.

set -euo pipefail

DEST_DIR="app/src/main/assets/novnc/core"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# If a newer stable noVNC version is released later, update the version
# number here — check https://github.com/novnc/noVNC/releases for the
# current tag.
NOVNC_VERSION="1.5.0"
NOVNC_URL="https://github.com/novnc/noVNC/archive/refs/tags/v${NOVNC_VERSION}.tar.gz"

echo "==> Binary Beast: تحميل noVNC v${NOVNC_VERSION} الرسمية..."
curl -fL "$NOVNC_URL" -o "$TMP_DIR/novnc.tar.gz"

echo "==> فك الضغط..."
tar -xzf "$TMP_DIR/novnc.tar.gz" -C "$TMP_DIR"

SRC_CORE_DIR="$TMP_DIR/noVNC-${NOVNC_VERSION}/core"
if [ ! -d "$SRC_CORE_DIR" ]; then
    echo "خطأ: مجلد core مش موجود في الأرشيف المُنزّل — تأكد إن رقم الإصدار لسه صحيح." >&2
    exit 1
fi

rm -rf "$DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
cp -r "$SRC_CORE_DIR" "$DEST_DIR"

echo "==> تم نسخ noVNC core إلى: $DEST_DIR"
ls "$DEST_DIR" | head -20
