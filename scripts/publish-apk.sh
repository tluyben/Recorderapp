#!/usr/bin/env bash
#
# Build the Recorder APK and publish it to sharefiles as recorder.apk (+ recorder.json).
#
#   ./scripts/publish-apk.sh              # build + upload
#   ./scripts/publish-apk.sh --no-upload  # build only, leaves build/recorder.apk
#
# Target: the `recorder-app` folder of tycho@appsalad.com on sharefiles.eu (no-delete
# lock on; the writable link cannot delete). The link is a write credential and is not
# in the repo: RECORDER_PUBLISH_URL from the environment or ~/.config/recorder/publish.env.
# Download: https://sharefiles.eu/direct/ANmFHssycH3ZHJDt0YXqgm9zSIfVoDdg/recorder.apk
#
# Versioning needs no commit: versionCode = minutes since 2020-01-01 (always goes up),
# versionName = VERSION file + short commit. The APK is a minified release build signed
# with THIS machine's ~/.android/debug.keystore — Android only installs an update over
# an existing install with the same signature, so always build on netcup-2.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

upload=1
for a in "$@"; do
  case "$a" in
    --no-upload) upload=0 ;;
    *) echo "usage: $0 [--no-upload]" >&2; exit 2 ;;
  esac
done

if [[ -z "${RECORDER_PUBLISH_URL:-}" && -f "$HOME/.config/recorder/publish.env" ]]; then
  RECORDER_PUBLISH_URL=$(sed -n 's/^RECORDER_PUBLISH_URL=["'\'']\{0,1\}\([^"'\'']*\).*/\1/p' "$HOME/.config/recorder/publish.env" | tail -1)
fi
(( upload )) && : "${RECORDER_PUBLISH_URL:?set RECORDER_PUBLISH_URL (or ~/.config/recorder/publish.env)}"

[[ -z "${JAVA_HOME:-}" && -x "$HOME/jdk21/bin/java" ]] && export JAVA_HOME=$HOME/jdk21
[[ -n "${JAVA_HOME:-}" ]] && export PATH=$JAVA_HOME/bin:$PATH
[[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]] && export ANDROID_HOME=$HOME/Android/Sdk
[[ -f local.properties ]] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./scripts/fetch-model.sh

COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo nogit)
[[ -z "$(git status --porcelain -- app 2>/dev/null)" ]] || COMMIT="$COMMIT-dirty"
VERSION="$(cat VERSION)-$COMMIT"
CODE=$(( ($(date +%s) - 1577836800) / 60 ))
echo "== recorder android $VERSION ($CODE)"

./gradlew --no-daemon -q :app:testDebugUnitTest --tests '*CommandsTest*'
./gradlew --no-daemon -q assembleRelease -Precorder.versionCode=$CODE -Precorder.versionName=$VERSION
APK=app/build/outputs/apk/release/app-release.apk
[[ -f $APK ]] || { echo "build produced no $APK" >&2; exit 1; }

mkdir -p build
cp "$APK" build/recorder.apk
SHA=$(sha256sum build/recorder.apk | cut -d' ' -f1)
printf '{"version":"%s","versionCode":%s,"sha256":"%s","commit":"%s","builtAt":"%s"}\n' \
  "$VERSION" "$CODE" "$SHA" "$COMMIT" "$(date -u +%FT%TZ)" > build/recorder.json
echo "built $(du -h build/recorder.apk | cut -f1) sha256 $SHA"
(( upload )) || { echo "not uploaded: build/recorder.apk"; exit 0; }

served_sha() {  # sha256 the share currently serves for $1 ("" if none/unreachable)
  curl -fsS --max-time 60 "$RECORDER_PUBLISH_URL?format=json" 2>/dev/null | NAME=$1 python3 -c '
import sys, json, os
f = next((f for f in json.load(sys.stdin)["files"] if f["name"] == os.environ["NAME"]), {})
print(f.get("sha256", ""))' 2>/dev/null || true
}

# the APK first, then the manifest, so the manifest never names an APK that isn't there yet
for f in recorder.apk recorder.json; do
  want=$(sha256sum "build/$f" | cut -d' ' -f1)
  # sharefiles.eu answers 502/504 under load, often AFTER it stored the file, so check
  # what the share serves before sending 47 MB again
  for try in 1 2 3 4 5; do
    curl -fsS --max-time 300 -X POST "$RECORDER_PUBLISH_URL" -F "files=@build/$f" >/dev/null && break
    sleep 10; [[ "$(served_sha $f)" == "$want" ]] && { echo "   $f landed despite the error"; break; }
    (( try == 5 )) && { echo "upload of $f failed" >&2; exit 1; }
    echo "   retrying $f in 20s"; sleep 20
  done
  echo "-> uploaded $f"
done
curl -fsS "$RECORDER_PUBLISH_URL?format=json" | SHA=$SHA python3 -c '
import sys, json, os
d = json.load(sys.stdin)
apk = next((f for f in d["files"] if f["name"] == "recorder.apk"), None)
assert apk and apk["sha256"] == os.environ["SHA"], "the share does not serve the APK just built"
print("ok: share serves recorder.apk %s (%s bytes)" % (os.environ["SHA"][:12], format(apk["size"], ",")))
'
