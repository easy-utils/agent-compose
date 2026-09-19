#!/usr/bin/env bash
# Package the Compose Desktop distributable as a .deb for the appstore.
#
# Why not `gradle :packageReleaseDeb`? The Compose plugin's own deb bundler
# resolves the main jar from a temp dir that is not populated when the
# (broken-on-headless) ProGuard step is skipped, so jpackage fails with
# "configured main jar does not exist". Assembling the deb ourselves is
# deterministic and keeps the same layout jpackage would produce.
#
# Usage: tool/package-deb.sh [out-dir]
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$DIR/build/dist}"
VERSION="$(grep -m1 '^version = ' "$DIR/build.gradle.kts" | sed -E 's/.*"([^"]+)".*/\1/')"
ARCH="amd64"
PKG="easy-agent-compose"
APP_NAME="Easy Agent"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "==> gradle :createDistributable (skipProguard)"
(cd "$DIR" && JAVA_HOME="${JAVA_HOME:-/opt/tools/mise/installs/java/17.0.2}" \
  gradle :createDistributable -PskipProguard -q)

SRC="$DIR/build/compose/binaries/main/app/$APP_NAME"
[ -d "$SRC" ] || { echo "distributable not found at $SRC"; exit 2; }
[ -f "$DIR/src/desktopMain/resources/icon.png" ] || { echo "icon.png missing"; exit 3; }

echo "==> assemble deb tree ($PKG $VERSION)"
ROOT="$STAGE/$PKG"
mkdir -p "$ROOT/DEBIAN" \
  "$ROOT/opt/$PKG/bin" "$ROOT/opt/$PKG/lib" \
  "$ROOT/usr/bin" \
  "$ROOT/usr/share/applications" \
  "$ROOT/usr/share/icons/hicolor/256x256/apps"
cp -a "$SRC/bin/." "$ROOT/opt/$PKG/bin/"
cp -a "$SRC/lib/." "$ROOT/opt/$PKG/lib/"
cp "$DIR/src/desktopMain/resources/icon.png" \
   "$ROOT/usr/share/icons/hicolor/256x256/apps/$PKG.png"
# Launcher shim so the app is on PATH.
printf '#!/bin/sh\nexec "/opt/%s/bin/%s" "$@"\n' "$PKG" "$APP_NAME" > "$ROOT/usr/bin/$PKG"
chmod 755 "$ROOT/usr/bin/$PKG"

cat > "$ROOT/usr/share/applications/$PKG.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Easy Agent (Compose)
Comment=Standalone ABC agent chat client (Compose Multiplatform)
Exec=$PKG
Icon=$PKG
Terminal=false
Categories=Utility;Network;
EOF

INSTALLED_KB="$(du -sk "$ROOT" | cut -f1)"
cat > "$ROOT/DEBIAN/control" <<EOF
Package: $PKG
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Maintainer: dev@easylab.local
Installed-Size: $INSTALLED_KB
Description: Easy Agent (Compose Multiplatform, desktop)
 Standalone ABC agent chat client built with Compose Multiplatform; talks to
 the abc standalone agent (agent.v1.AgentService) over Connect.
EOF

mkdir -p "$OUT_DIR"
DEB="$OUT_DIR/${PKG}_${VERSION}_${ARCH}.deb"
rm -f "$DEB"
dpkg-deb --build --root-owner-group "$ROOT" "$DEB" >/dev/null
echo "DEB_OK $DEB"
ls -la "$DEB"
sha256sum "$DEB"
