# AUR packaging (`umm-bin`)

This directory contains the Arch User Repository (AUR) binary package definition for Umm.

## Publishing to AUR

Once AUR user registration reopens, publish or update the package with these three commands:

```bash
git clone ssh://aur@aur.archlinux.org/umm-bin.git
cp packaging/aur/umm-bin/{PKGBUILD,.SRCINFO} umm-bin/
cd umm-bin && git add PKGBUILD .SRCINFO && git commit -m "Update to X.Y.Z" && git push
```

## Updating version and checksums

To bump the version and regenerate `.SRCINFO`:

```bash
# Using a local release tarball:
scripts/bump-aur.sh X.Y.Z desktop/build/dist/umm-X.Y.Z-linux-x86_64.tar.gz

# Or fetching checksum from GitHub release:
scripts/bump-aur.sh X.Y.Z
```
