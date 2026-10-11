#!/usr/bin/env bash
# Prints the changes CHANGELOG.md lists for one version, for the release notes. A pre-release (1.3.0-beta.1) uses
# its base version's section. Exits 1 when the version has no section or the section has no changes.
set -euo pipefail

version="${1:?usage: changelog-section.sh <version>}"
base="${version%%-*}"
file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md"

section="$(awk -v version="$base" '
    /^## / {
        heading = substr($0, 4)
        sub(/ - .*$/, "", heading)
        inside = (heading == version)
        next
    }
    inside && /^- / { print }
' "$file")"

if [ -z "$section" ]; then
    echo "CHANGELOG.md has no changes listed under '## $base'" >&2
    exit 1
fi
printf '%s\n' "$section"
