#!/usr/bin/env bash
# Re-captures corpus reader snapshots from a connected device into :corpus.
#
# Not `./gradlew connectedAndroidTest`: that uninstalls the app when it finishes, taking the
# captured files with it. Installing both APKs and driving the instrumentation directly is
# the only way the output survives long enough to pull.
#
# Regenerating snapshots is a reviewed act. Run this, then read `git diff` before committing:
# the diff is exactly what a reader change did to what the engine sees.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/corpus/src/test/resources/corpus"
PKG=com.okayanshul.docaction
ON_DEVICE="/data/user/0/$PKG/files/corpus-snapshots"

"$ROOT/gradlew" -p "$ROOT" :app:installDebug :app:installDebugAndroidTest

adb shell am instrument -w -e class \
  "$PKG.diagnostic.CorpusCapture" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner"

mkdir -p "$OUT"
for name in $(adb shell "run-as $PKG ls $ON_DEVICE" | tr -d '\r'); do
  case "$name" in
    *.content.json) adb shell "run-as $PKG cat $ON_DEVICE/$name" > "$OUT/$name" ;;
  esac
done

echo "Captured $(ls "$OUT"/*.content.json | wc -l) snapshots into $OUT"
echo "Now read the diff:  git diff --stat $OUT"
