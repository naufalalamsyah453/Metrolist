#!/usr/bin/env bash
# Assemble a portable AppImage around the Compose Desktop uber jar.
# Runs on the GitHub Actions ubuntu runner (no FUSE required:
# APPIMAGE_EXTRACT_AND_RUN makes the tool AppImage self-extract).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP=Metrolist
ARCH=x86_64
JRE_MAJOR=21
OUT="$ROOT/build/appimage"
APPDIR="$OUT/${APP}.AppDir"

mkdir -p "$OUT"

# --- locate the uber jar -----------------------------------------------------
JAR="$(find "$ROOT/desktopApp/build/compose/jars" -name '*.jar' -print -quit)"
[ -n "$JAR" ] || { echo "uber jar not found under desktopApp/build/compose/jars" >&2; exit 1; }
echo "Packaging jar: $JAR"

# --- fetch a JRE to bundle ---------------------------------------------------
if [ ! -d "$OUT/jre" ]; then
  echo "Downloading Temurin JRE ${JRE_MAJOR}..."
  curl -fL --retry 3 -o "$OUT/jre.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/${JRE_MAJOR}/ga/linux/x64/jre/hotspot/normal/eclipse"
  mkdir -p "$OUT/jre-extract"
  tar -xzf "$OUT/jre.tar.gz" -C "$OUT/jre-extract"
  JRE_SRC="$(find "$OUT/jre-extract" -maxdepth 1 -mindepth 1 -type d -name 'jdk*' -print -quit)"
  mv "$JRE_SRC" "$OUT/jre"
  rm -rf "$OUT/jre-extract" "$OUT/jre.tar.gz"
fi

# --- stage the AppDir --------------------------------------------------------
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib"
cp -r "$OUT/jre" "$APPDIR/usr/lib/jre"
cp "$JAR" "$APPDIR/usr/lib/metrolist.jar"
cp "$ROOT/assets/ic_launcher-playstore.png" "$APPDIR/metrolist.png"
ln -sf metrolist.png "$APPDIR/.DirIcon"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/lib/jre/bin/java" -Xmx2g -jar "$HERE/usr/lib/metrolist.jar" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/${APP}.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Metrolist
GenericName=YouTube Music Client
Comment=Stream and manage your YouTube Music library
Exec=Metrolist %U
Icon=metrolist
Terminal=false
Categories=AudioVideo;Audio;Music;Player;
StartupWMClass=Metrolist
EOF

# --- build the AppImage ------------------------------------------------------
if [ ! -f "$OUT/appimagetool" ]; then
  echo "Downloading appimagetool..."
  curl -fL --retry 3 -o "$OUT/appimagetool" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage"
  chmod +x "$OUT/appimagetool"
fi

export ARCH
export APPIMAGE_EXTRACT_AND_RUN=1
export VERSION=13.7.0
cd "$OUT"
./appimagetool --no-appstream "${APP}.AppDir" "${APP}-${ARCH}.AppImage"

echo "Produced:"
ls -lh "$OUT"/*.AppImage
