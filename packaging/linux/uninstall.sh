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

if [ "$SYSTEM" -eq 1 ]; then
    if [ "$(id -u)" -ne 0 ]; then
        echo "Error: --system uninstall requires root" >&2
        exit 1
    fi
    LIB_DIR="/opt/umm"
    BIN_DIR="/usr/local/bin"
    SHARE_DIR="/usr/local/share"
    AUTOSTART_DIR="/etc/xdg/autostart"
else
    LIB_DIR="${HOME}/.local/lib/umm"
    BIN_DIR="${HOME}/.local/bin"
    SHARE_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}"
    AUTOSTART_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/autostart"
fi

APPS_DIR="$SHARE_DIR/applications"
SCALABLE_ICON_DIR="$SHARE_DIR/icons/hicolor/scalable/apps"
PNG_ICON_DIR="$SHARE_DIR/icons/hicolor/256x256/apps"
METAINFO_DIR="$SHARE_DIR/metainfo"

rm -rf "$LIB_DIR"
rm -f "$BIN_DIR/umm"
rm -f "$APPS_DIR/io.github.agopalareddy.Umm.desktop"
rm -f "$SCALABLE_ICON_DIR/io.github.agopalareddy.Umm.svg"
rm -f "$PNG_ICON_DIR/io.github.agopalareddy.Umm.png"
rm -f "$METAINFO_DIR/io.github.agopalareddy.Umm.metainfo.xml"
rm -f "$AUTOSTART_DIR/io.github.agopalareddy.Umm.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t "$SHARE_DIR/icons/hicolor" 2>/dev/null || true
fi

if [ "$SYSTEM" -eq 0 ]; then
    if [ -d "$APPS_DIR" ]; then
        shopt -s nullglob
        desktops=("$APPS_DIR"/*.desktop)
        if [ "${#desktops[@]}" -eq 0 ]; then
            rm -f "$APPS_DIR/mimeinfo.cache"
        fi
    fi
    if [ -d "$SHARE_DIR/icons/hicolor" ]; then
        remaining_icons=$(find "$SHARE_DIR/icons/hicolor" -type f ! -name "icon-theme.cache" 2>/dev/null | head -n 1)
        if [ -z "$remaining_icons" ]; then
            rm -f "$SHARE_DIR/icons/hicolor/icon-theme.cache"
        fi
    fi

    rmdir "$APPS_DIR" 2>/dev/null || true
    rmdir "$METAINFO_DIR" 2>/dev/null || true
    rmdir "$SCALABLE_ICON_DIR" 2>/dev/null || true
    rmdir "$SHARE_DIR/icons/hicolor/scalable" 2>/dev/null || true
    rmdir "$PNG_ICON_DIR" 2>/dev/null || true
    rmdir "$SHARE_DIR/icons/hicolor/256x256" 2>/dev/null || true
    rmdir "$SHARE_DIR/icons/hicolor" 2>/dev/null || true
    rmdir "$SHARE_DIR/icons" 2>/dev/null || true
    rmdir "$SHARE_DIR" 2>/dev/null || true
    rmdir "$BIN_DIR" 2>/dev/null || true
    rmdir "${HOME}/.local/lib" 2>/dev/null || true
    rmdir "${HOME}/.local" 2>/dev/null || true
    rmdir "$AUTOSTART_DIR" 2>/dev/null || true
    rmdir "${XDG_CONFIG_HOME:-${HOME}/.config}" 2>/dev/null || true
fi
