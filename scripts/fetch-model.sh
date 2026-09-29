#!/usr/bin/env bash
# Downloads the offline Vosk English model into the APK's assets (68 MB, not in git).
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/model-en-us
NAME=vosk-model-small-en-us-0.15
[[ -f $DEST/am/final.mdl ]] && { echo "model already in $DEST"; exit 0; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
curl -fL --progress-bar -o "$TMP/m.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
unzip -q "$TMP/m.zip" -d "$TMP"
mkdir -p "$(dirname $DEST)"; rm -rf "$DEST"; mv "$TMP/$NAME" "$DEST"
echo "model installed in $DEST"
