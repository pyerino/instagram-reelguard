#!/usr/bin/env bash
# Embed the ReelGuard module into a merged Instagram apk by direct dex surgery
# (no LSPatch, no Xposed): baksmali classes3 -> ONE call-site in
# com/instagram/app/InstagramAppShell.onCreate -> smali; the whole module rides
# along as classes24.dex; then zipalign + apksigner.
#
# usage: embed-patch.sh <module-debug.apk> [merged.apk] [outdir]
#
# Keystore resolution (first hit wins):
#   1. env REELGUARD_KEYSTORE / REELGUARD_PASS / REELGUARD_ALIAS
#   2. signing/keystore.properties (storeFile/storePassword/keyAlias/keyPassword)
#   3. defaults: signing/reelguard.keystore, password+alias "reelguard"
# A missing keystore is generated on the fly via signing/generate-keystore.sh.
#
# env: REELGUARD_DEX_INDEX (default 24 — IG 453 ships classes1..23)
#      REELGUARD_BUILD_TOOLS (path to a build-tools dir with zipalign/apksigner)
#      REELGUARD_WORK (workdir; default a fresh mktemp, removed on exit)
#      REELGUARD_KEEP_WORK=y to keep the workdir for debugging
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE=${1:?usage: embed-patch.sh <module-debug.apk> [merged.apk] [outdir]}
MERGED=${2:-$ROOT/pipeline/merged-instagram.apk}
OUTDIR=${3:-.}
DEX_INDEX=${REELGUARD_DEX_INDEX:-24}
LIB="$ROOT/pipeline/.tools/lib"

[ -f "$MODULE" ] || { echo "no such module apk: $MODULE"; exit 1; }
[ -f "$MERGED" ] || { echo "no such merged apk: $MERGED (run merge-splits.sh)"; exit 1; }
[ -f "$LIB/baksmali-2.5.2.jar" ] || { echo "smali toolchain missing - run pipeline/fetch-tools.sh"; exit 1; }

# ---- keystore ------------------------------------------------------------------------------
PROPS="$ROOT/signing/keystore.properties"
prop() { awk -F'=' -v key="$1" '$0 !~ /^[[:space:]]*#/ && $1 == key { sub(/^[^=]*=/, ""); gsub(/[[:space:]]$/, ""); print; exit }' "$PROPS" 2>/dev/null || true; }

KEYSTORE=${REELGUARD_KEYSTORE:-$(prop storeFile)}
KEYSTORE=${KEYSTORE:-$ROOT/signing/reelguard.keystore}
case "$KEYSTORE" in /*) ;; *) KEYSTORE="$ROOT/signing/$KEYSTORE" ;; esac
KEYSTORE="${KEYSTORE/#\~/$HOME}"
PASS=${REELGUARD_PASS:-$(prop storePassword)}
PASS=${PASS:-reelguard}
PASS_KEY=${REELGUARD_KEY_PASS:-$(prop keyPassword)}
PASS_KEY=${PASS_KEY:-$PASS}
ALIAS=${REELGUARD_ALIAS:-$(prop keyAlias)}
ALIAS=${ALIAS:-reelguard}

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating keystore $KEYSTORE =="
  REELGUARD_KEYSTORE="$KEYSTORE" REELGUARD_PASS="$PASS" REELGUARD_ALIAS="$ALIAS" \
    "$ROOT/signing/generate-keystore.sh"
fi

# ---- build-tools (zipalign, apksigner) -------------------------------------------------------
find_build_tools() {
  local base cand
  [ -n "${REELGUARD_BUILD_TOOLS:-}" ] && { echo "$REELGUARD_BUILD_TOOLS"; return 0; }
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
    [ -n "$base" ] || continue
    for cand in $(ls -1d "$base"/build-tools/* 2>/dev/null | sort -V | tail -4); do
      if [ -x "$cand/zipalign" ] && [ -x "$cand/apksigner" ]; then echo "$cand"; return 0; fi
    done
  done
  return 1
}
BT=$(find_build_tools) || { echo "zipalign/apksigner not found - install Android build-tools or set REELGUARD_BUILD_TOOLS"; exit 1; }
echo "build-tools: $BT"

# ---- workspace -------------------------------------------------------------------------------
WORK=${REELGUARD_WORK:-"$(mktemp -d "${TMPDIR:-/tmp}/reelguard-embed.XXXXXX")"}
mkdir -p "$WORK" "$OUTDIR"
cleanup() {
  if [ "${REELGUARD_KEEP_WORK:-n}" = y ]; then echo "workdir kept: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

echo "== 1. baksmali IG classes3 + all module dexes =="
unzip -o -q "$MERGED" classes3.dex -d "$WORK"
java -Xmx2g -cp "$LIB/*" org.jf.baksmali.Main d -o "$WORK/d3" "$WORK/classes3.dex" 2>&1 | tail -1 || true
unzip -o -q "$MODULE" 'classes*.dex' -d "$WORK/modapk"
for d in "$WORK"/modapk/classes*.dex; do
  name=$(basename "$d" .dex)
  java -Xmx1g -cp "$LIB/*" org.jf.baksmali.Main d -o "$WORK/modsrc-$name" "$d" 2>&1 | tail -1 || true
done
echo "module smali files: $(find "$WORK"/modsrc-* -name '*.smali' | wc -l | tr -d ' ')"

echo "== 2. module -> its own dex (classes$DEX_INDEX; no IG dex bloat) =="
if unzip -l "$MERGED" | grep -q "classes$DEX_INDEX\.dex"; then
  echo "NOTE: merged apk already has classes$DEX_INDEX.dex - set REELGUARD_DEX_INDEX higher"
  exit 1
fi
mkdir -p "$WORK/modtree"
find "$WORK"/modsrc-* -name '*.smali' | while read -r f; do
  rel=${f#"$WORK"/modsrc-*/}
  dest="$WORK/modtree/$rel"; mkdir -p "$(dirname "$dest")"; cp "$f" "$dest"
done
java -Xmx2g -cp "$LIB/*" org.jf.smali.Main a -o "$WORK/module-embed.dex" "$WORK/modtree" 2>&1 | tail -1
ls -la "$WORK/module-embed.dex"

echo "== 3. call-site insert (the entire integration surface) =="
APP_SMALI="$WORK/d3/com/instagram/app/InstagramAppShell.smali"
[ -f "$APP_SMALI" ] || { echo "MISSING $APP_SMALI - insertion point moved? see docs/BUILD.md#retargeting"; exit 1; }

python3 - "$APP_SMALI" <<'PYEOF'
import os, re, sys

path = sys.argv[1]
src = open(path).read()
if "EmbeddedEntry" in src:
    print("  already patched, skipping")
    sys.exit(0)
# first onCreate()V whose body starts right after the .registers/.locals line
pat = re.compile(r"(\.method[^\n]*onCreate\(\)V[^\n]*\n\s*\.(?:registers|locals) \d+\n)")
m = pat.search(src)
if not m:
    print("  no onCreate()V + registers/locals match - inspect the smali manually")
    sys.exit(1)
call = ("    invoke-static/range {p0 .. p0}, "
        "Ldev/pier/reelguard/igpatch/EmbeddedEntry;->onApp(Landroid/app/Application;)V")
open(path, "w").write(src[: m.end(1)] + call + "\n" + src[m.end(1):])
print("  patched InstagramAppShell.onCreate")
PYEOF

echo "== 4. reassemble classes3 =="
java -Xmx3g -cp "$LIB/*" org.jf.smali.Main a -o "$WORK/classes3-new.dex" "$WORK/d3" 2>&1 | tail -2
ls -la "$WORK/classes3-new.dex"

echo "== 5. repack + align + sign =="
cp "$MERGED" "$WORK/patched.apk"
(
  cd "$WORK"
  cp -f module-embed.dex "classes$DEX_INDEX.dex" && zip -q patched.apk "classes$DEX_INDEX.dex"
  mv -f classes3-new.dex classes3.dex && zip -q patched.apk classes3.dex
)
"$BT/zipalign" -f 4 "$WORK/patched.apk" "$WORK/patched-aligned.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$PASS" --ks-key-alias "$ALIAS" --key-pass "pass:$PASS_KEY" \
  --out "$OUTDIR/instagram-reelguard.apk" "$WORK/patched-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUTDIR/instagram-reelguard.apk" | head -2

echo "entry-in-c3:  $(unzip -p "$OUTDIR/instagram-reelguard.apk" classes3.dex | grep -ac EmbeddedEntry || true)"
echo "entry-in-c$DEX_INDEX: $(unzip -p "$OUTDIR/instagram-reelguard.apk" "classes$DEX_INDEX.dex" 2>/dev/null | grep -ac EmbeddedEntry || true)"
ls -la "$OUTDIR/instagram-reelguard.apk"
echo "DONE: $OUTDIR/instagram-reelguard.apk"
