#!/usr/bin/env bash
# Merge the Play-store split apks (base + config.*) into ONE installable apk.
#
# usage: merge-splits.sh <dir-with-splits> [out.apk]
#
# The splits are the files `adb bundle` / a Play download yields for your
# device (base.apk plus the split_config.* apks). Never commit the result.
set -euo pipefail
cd "$(dirname "$0")"

SPLITS=${1:?usage: merge-splits.sh <splits-dir> [out.apk]}
OUT=${2:-merged-instagram.apk}

TOOLS=${REELGUARD_TOOLS:-.tools}
APKEDITOR=$(ls "$TOOLS"/APKEditor-*.jar 2>/dev/null | sort -V | tail -1 || true)
[ -n "$APKEDITOR" ] || { echo "APKEditor missing - run ./fetch-tools.sh first"; exit 1; }

[ -d "$SPLITS" ] || { echo "no such dir: $SPLITS"; exit 1; }
count=$(ls "$SPLITS"/*.apk 2>/dev/null | wc -l | tr -d ' ')
[ "$count" -ge 1 ] || { echo "no .apk files in $SPLITS"; exit 1; }

echo "== merging $count splits -> $OUT =="
java -Xmx4g -jar "$APKEDITOR" m -i "$SPLITS" -o "$OUT"
echo "wrote $(ls -la "$OUT" | awk '{print $5, $9}')"
