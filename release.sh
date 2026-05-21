#!/usr/bin/env bash
# Baut eine APK und legt sie in release/ ab.
# Nutzung:
#   ./release.sh            # release-Build (signed wenn keystore.properties vorhanden)
#   ./release.sh debug      # debug-Build

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VARIANT="${1:-release}"

case "$VARIANT" in
    debug|release) ;;
    *) echo "ERROR: unbekannte Variante '$VARIANT' (erlaubt: debug, release)"; exit 2 ;;
esac

RELEASE_DIR="$SCRIPT_DIR/release"
mkdir -p "$RELEASE_DIR"

GRADLE_TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"

if [[ "$VARIANT" == "release" && ! -f "$SCRIPT_DIR/keystore.properties" ]]; then
    echo "WARN: keystore.properties fehlt – Release wird mit Debug-Key signiert."
    echo "      Lege keystore.properties an (siehe keystore.properties.example) für eine"
    echo "      ordentliche Release-Signatur."
fi

echo "==> Building: ./gradlew $GRADLE_TASK"
if [[ -x "./gradlew" ]]; then
    ./gradlew "$GRADLE_TASK"
else
    bash ./gradlew "$GRADLE_TASK"
fi

APK_DIR="app/build/outputs/apk/$VARIANT"
APK="$(ls -1t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)"
if [[ -z "$APK" ]]; then
    echo "ERROR: keine APK gefunden in $APK_DIR"
    exit 1
fi

VERSION="$(grep -E 'versionName\s*=' app/build.gradle.kts | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
[[ -z "$VERSION" ]] && VERSION="unknown"

SIGNED_SUFFIX=""
[[ "$(basename "$APK")" == *unsigned.apk ]] && SIGNED_SUFFIX="-unsigned"

DEST="$RELEASE_DIR/epf-cockpit-${VERSION}-${VARIANT}${SIGNED_SUFFIX}.apk"
cp "$APK" "$DEST"

echo "==> APK: $DEST"
ls -lh "$DEST"
