#!/usr/bin/env bash
# Fetch the dex-surgery toolchain into pipeline/.tools/ (git-ignored).
#
#   pipeline/.tools/APKEditor-<ver>.jar   store-split merger (self-contained)
#   pipeline/.tools/lib/*.jar             smali/baksmali 2.5.2 + runtime closure
#
# Idempotent: existing jars are kept. Override the destination by exporting
# REELGUARD_TOOLS. Nothing here is committed; re-run after a fresh clone.
set -euo pipefail
cd "$(dirname "$0")"

DEST=${REELGUARD_TOOLS:-.tools}
MC=https://repo1.maven.org/maven2
APKEDITOR_VER=1.4.9
mkdir -p "$DEST/lib"

need() { # need <url> <rel-path>
  if [ -f "$DEST/$2" ]; then
    echo "have  $2"
  else
    echo "fetch $2"
    curl -fL --retry 3 --silent --show-error -o "$DEST/$2" "$1"
  fi
}

# APKEditor: merges the Play store splits into one installable apk.
need "https://github.com/REAndroid/APKEditor/releases/download/V${APKEDITOR_VER}/APKEditor-${APKEDITOR_VER}.jar" \
  "APKEditor-${APKEDITOR_VER}.jar"

# smali / baksmali 2.5.2 and the exact runtime dependency closure, so the
# tools run with: java -cp ".tools/lib/*" org.jf.baksmali.Main ...
while read -r coord; do
  [ -n "$coord" ] || continue
  need "$MC/$coord" "lib/$(basename "$coord")"
done <<EOF
org/smali/baksmali/2.5.2/baksmali-2.5.2.jar
org/smali/smali/2.5.2/smali-2.5.2.jar
org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
org/smali/util/2.5.2/util-2.5.2.jar
com/google/guava/guava/27.1-android/guava-27.1-android.jar
com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar
com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar
com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar
com/google/errorprone/error_prone_annotations/2.2.0/error_prone_annotations-2.2.0.jar
org/codehaus/mojo/animal-sniffer-annotations/1.17/animal-sniffer-annotations-1.17.jar
com/google/j2objc/j2objc-annotations/1.1/j2objc-annotations-1.1.jar
org/checkerframework/checker-compat-qual/2.5.2/checker-compat-qual-2.5.2.jar
antlr/antlr/2.7.7/antlr-2.7.7.jar
org/antlr/antlr/3.5.2/antlr-3.5.2.jar
org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar
org/antlr/ST4/4.0.8/ST4-4.0.8.jar
org/antlr/stringtemplate/3.2.1/stringtemplate-3.2.1.jar
com/beust/jcommander/1.64/jcommander-1.64.jar
EOF

echo
echo "tools ready in $DEST (apkeditor $(ls "$DEST"/APKEditor-*.jar | xargs -n1 basename), smali $(ls "$DEST"/lib/smali-*.jar | xargs -n1 basename))"
