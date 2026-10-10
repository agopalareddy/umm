#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "Usage: $0 <version> [tarball]" >&2
    exit 1
fi

VERSION="$1"
TARBALL="${2:-}"

if [ -n "$TARBALL" ]; then
    if [ ! -f "$TARBALL" ]; then
        echo "Error: Tarball not found: $TARBALL" >&2
        exit 1
    fi
    SHA256="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
else
    URL="https://github.com/agopalareddy/umm/releases/download/v${VERSION}/umm-${VERSION}-linux-x86_64.tar.gz"
    echo "Fetching tarball from $URL..." >&2
    SHA256="$(curl -sSfL "$URL" | sha256sum | cut -d' ' -f1)"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUR_DIR="$SCRIPT_DIR/../packaging/aur/umm-bin"
PKGBUILD="$AUR_DIR/PKGBUILD"
SRCINFO="$AUR_DIR/.SRCINFO"

if [ ! -f "$PKGBUILD" ]; then
    echo "Error: PKGBUILD not found at $PKGBUILD" >&2
    exit 1
fi

sed -i "s/^pkgver=.*/pkgver=${VERSION}/" "$PKGBUILD"
sed -i "s/^pkgrel=.*/pkgrel=1/" "$PKGBUILD"
sed -i "s/^sha256sums=.*/sha256sums=('${SHA256}')/" "$PKGBUILD"

(cd "$AUR_DIR" && makepkg --printsrcinfo > .SRCINFO)

echo "Updated PKGBUILD and .SRCINFO for version $VERSION ($SHA256)"
