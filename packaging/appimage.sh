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
export LD_LIBRARY_PATH="$HERE/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$HERE/usr/lib/jre/bin/java" -Xmx2g -Dmetrolist.mpv.library="$HERE/usr/lib/libmpv.so.2" -jar "$HERE/usr/lib/metrolist.jar" "$@"
EOF
chmod +x "$APPDIR/AppRun"

# --- bundle libmpv and its shared-library closure -----------------------------
# Skips glibc core (present on every target system); everything else — ffmpeg,
# libplacebo, … — is copied so playback works without a system mpv install.
MPV_LIB="$(find /usr/lib /lib -name 'libmpv.so.2' -print -quit 2>/dev/null || true)"
[ -n "$MPV_LIB" ] || { echo "libmpv.so.2 not found (apt install libmpv2)" >&2; exit 1; }

worklist=("$MPV_LIB")
seen=" "
while [ "${#worklist[@]}" -gt 0 ]; do
  so="${worklist[0]}"
  worklist=("${worklist[@]:1}")
  case "$seen" in *" $so "*) continue ;; esac
  seen="$seen$so "
  name="$(basename "$so")"
  case "$name" in
    libc.so*|libm.so*|libpthread*|libdl.so*|librt.so*|ld-linux*|libgcc_s.so*) continue ;;
  esac
  real="$(readlink -f "$so")"
  if [ ! -e "$APPDIR/usr/lib/$name" ]; then
    cp -L "$real" "$APPDIR/usr/lib/$name"
    while read -r dep; do
      [ -n "$dep" ] && [ -e "$dep" ] && worklist+=("$dep")
    done < <(ldd "$real" 2>/dev/null | awk '{ for (i = 1; i <= NF; i++) if ($i == "=>") print $(i + 1) }')
  fi
done
echo "Bundled $(ls "$APPDIR/usr/lib" | wc -l) native libraries alongside the JRE"

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
