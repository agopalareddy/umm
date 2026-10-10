#!/usr/bin/env bash
set -euo pipefail

SYSTEM=0
for arg in "$@"; do
    case "$arg" in
        --system)
            SYSTEM=1
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -d "$SCRIPT_DIR/Umm" ]; then
    echo "Error: Umm directory not found in $SCRIPT_DIR" >&2
    exit 1
fi

if [ "$SYSTEM" -eq 1 ]; then
    if [ "$(id -u)" -ne 0 ]; then
        echo "Error: --system install requires root" >&2
        exit 1
    fi
    LIB_DIR="/opt/umm"
    BIN_DIR="/usr/local/bin"
    SHARE_DIR="/usr/local/share"
else
    LIB_DIR="${HOME}/.local/lib/umm"
    BIN_DIR="${HOME}/.local/bin"
    SHARE_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}"
fi

if [ -d "$SCRIPT_DIR/share" ]; then
    SRC_SHARE="$SCRIPT_DIR/share"
else
    SRC_SHARE="$SCRIPT_DIR"
fi

rm -rf "$LIB_DIR"
mkdir -p "$LIB_DIR"
cp -a "$SCRIPT_DIR/Umm/." "$LIB_DIR/"

mkdir -p "$BIN_DIR"
ln -sf "$LIB_DIR/bin/Umm" "$BIN_DIR/umm"

APPS_DIR="$SHARE_DIR/applications"
mkdir -p "$APPS_DIR"
sed "s|@EXEC@|\"$LIB_DIR/bin/Umm\"|" "$SRC_SHARE/io.github.agopalareddy.Umm.desktop.in" > "$APPS_DIR/io.github.agopalareddy.Umm.desktop"
chmod 644 "$APPS_DIR/io.github.agopalareddy.Umm.desktop"

SCALABLE_ICON_DIR="$SHARE_DIR/icons/hicolor/scalable/apps"
PNG_ICON_DIR="$SHARE_DIR/icons/hicolor/256x256/apps"
mkdir -p "$SCALABLE_ICON_DIR"
mkdir -p "$PNG_ICON_DIR"
cp "$SRC_SHARE/io.github.agopalareddy.Umm.svg" "$SCALABLE_ICON_DIR/io.github.agopalareddy.Umm.svg"
cp "$SRC_SHARE/io.github.agopalareddy.Umm.png" "$PNG_ICON_DIR/io.github.agopalareddy.Umm.png"

METAINFO_DIR="$SHARE_DIR/metainfo"
mkdir -p "$METAINFO_DIR"
cp "$SRC_SHARE/io.github.agopalareddy.Umm.metainfo.xml" "$METAINFO_DIR/io.github.agopalareddy.Umm.metainfo.xml"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t "$SHARE_DIR/icons/hicolor" 2>/dev/null || true
fi
