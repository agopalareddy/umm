#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "Usage: $0 <version> [tarball]" >&2
    exit 1
fi

VERSION="$1"
TARBALL="${2:-}"

# pacman's pkgver cannot contain a hyphen, and only stable releases go to the AUR.
case "$VERSION" in
    *-*)
        echo "Error: $VERSION is a pre-release; the AUR package tracks stable releases only." >&2
        exit 1
        ;;
esac
if ! command -v makepkg > /dev/null 2>&1; then
    echo "Error: makepkg not found. Run this on Arch Linux." >&2
    exit 1
fi

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

# Keep PKGBUILD and .SRCINFO in step: if anything below fails, put the old PKGBUILD back.
cp "$PKGBUILD" "$PKGBUILD.bak"
trap 'mv -f "$PKGBUILD.bak" "$PKGBUILD"; rm -f "$SRCINFO.new"' ERR

sed -i "s/^pkgver=.*/pkgver=${VERSION}/" "$PKGBUILD"
sed -i "s/^pkgrel=.*/pkgrel=1/" "$PKGBUILD"
sed -i "s/^sha256sums=.*/sha256sums=('${SHA256}')/" "$PKGBUILD"

(cd "$AUR_DIR" && makepkg --printsrcinfo > "$SRCINFO.new")
mv -f "$SRCINFO.new" "$SRCINFO"
trap - ERR
rm -f "$PKGBUILD.bak"

echo "Updated PKGBUILD and .SRCINFO for version $VERSION ($SHA256)"
