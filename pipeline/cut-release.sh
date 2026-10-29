#!/usr/bin/env bash
# Stage (and optionally publish) a GitHub Release for the patched apk.
#
#   cut-release.sh <signed.apk>          stage dist/, tag locally, print next steps
#   cut-release.sh <signed.apk> --push   ... and push the tag
#   cut-release.sh <signed.apk> --gh     ... and open a DRAFT release via gh (if installed)
#
# The apk never enters git history (GitHub warns >100 MB; history bloat): the
# release carries it. dist/ is git-ignored — delete it to re-stage cleanly.
# The tag is v<versionName> from app/build.gradle.kts unless --tag overrides;
# the release must be cut from a clean build of exactly that commit.
#
# env: REELGUARD_BUILD_TOOLS / ANDROID_HOME / ANDROID_SDK_ROOT for apksigner
#      (signature verification + cert fingerprint; skipped when absent)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; }

APK="" TAG="" OUT="$ROOT/dist" PUSH=false GH=false
while [ $# -gt 0 ]; do
  case $1 in
    --tag) TAG=${2:?--tag needs a value}; shift 2 ;;
    --out) OUT=${2:?--out needs a value}; shift 2 ;;
    --push) PUSH=true; shift ;;
    --gh) GH=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) APK=$1; shift ;;
  esac
done
[ -n "$APK" ] || { usage; exit 1; }
[ -f "$APK" ] || { echo "no such apk: $APK"; exit 1; }

VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
[ -n "$VERSION" ] || { echo "cannot parse versionName from app/build.gradle.kts"; exit 1; }
TAG=${TAG:-v$VERSION}

IGVER=$(sed -n 's/.*"igVersionName": "\([^"]*\)".*/\1/p' \
  app/src/main/java/dev/pier/reelguard/igpatch/data/EmbeddedRules.kt | head -1)

# ---- optional signature verification + cert fingerprint --------------------------------------
find_build_tools() {
  local base cand
  for base in "${REELGUARD_BUILD_TOOLS:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
    [ -n "$base" ] || continue
    for cand in $(ls -1d "$base"/build-tools/* 2>/dev/null | sort -V | tail -4); do
      [ -x "$cand/apksigner" ] && { echo "$cand"; return 0; }
    done
  done
  return 1
}
BT=$(find_build_tools 2>/dev/null || true)

# ---- stage artifacts -------------------------------------------------------------------------
mkdir -p "$OUT"
ASSET="$OUT/instagram-reelguard-$VERSION.apk"
cp -f "$APK" "$ASSET"

FINGERPRINT=""
if [ -n "$BT" ]; then
  echo "== verifying signature =="
  "$BT/apksigner" verify --print-certs "$ASSET" | tee "$OUT/certs.txt"
  FINGERPRINT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/certs.txt")
else
  echo "NOTE: apksigner not found — skipping signature verification/fingerprint"
fi

SUMS="$OUT/SHA256SUMS"
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }
SHA=$(sha256 "$ASSET" | awk '{print $1}')
# relative filenames so `sha256sum -c` works from the download dir
(cd "$OUT" && sha256 "$(basename "$ASSET")") > "$SUMS"

# notes are generated once; edit by hand afterwards (rm to regenerate)
NOTES="$OUT/NOTES.md"
if [ ! -f "$NOTES" ]; then
  cat > "$NOTES" <<EOF

# ReelGuard $VERSION

Patched Instagram with the ReelGuard daily limiter embedded
(module $CODE; built for Instagram **$IGVER** — other builds need re-harvested
detection ids, see docs/DETECTION.md).

## Install

\`\`\`bash
adb uninstall com.instagram.android   # signature differs from Play's; wipes login
adb install instagram-reelguard-$VERSION.apk
adb logcat -s ReelGuardPatch          # expect life signs
\`\`\`

Long-press the **Reels** tab for settings. Details: docs/BUILD.md.

## Artifacts

| file | sha256 |
| --- | --- |
| \`instagram-reelguard-$VERSION.apk\` | \`$SHA\` |

## Signature

Signer certificate SHA-256:

    $FINGERPRINT

Rebuilds from source are signed by *your* keystore — they are **not
update-compatible** with this apk unless you keep the same key
(docs/RELEASING.md, "signature compatibility").
EOF
  echo "staged $NOTES (edit freely; rm it to regenerate)"
fi

# ---- tag ------------------------------------------------------------------------------------
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "tag $TAG already exists locally"
else
  git tag -a "$TAG" -m "ReelGuard $VERSION (module $CODE, Instagram $IGVER)"
  echo "tagged $TAG -> $(git rev-parse --short HEAD)"
fi

# ---- distribution channels --------------------------------------------------------------------
SLUG=$(git remote get-url origin 2>/dev/null | sed -E 's#^(git@|https://)?github\.com[:/]##; s#\.git$##' || true)
NEW_URL="https://github.com/${SLUG:-<owner>/<repo>}/releases/new?tag=$TAG"

if $PUSH; then
  git push origin "refs/tags/$TAG"
  echo "pushed $TAG"
fi

if $GH; then
  if command -v gh >/dev/null; then
    gh auth status >/dev/null 2>&1 || { echo "gh not authenticated - run gh auth login"; exit 1; }
    git ls-remote --tags origin "refs/tags/$TAG" | grep -q . || $PUSH || {
      echo "NOTE: tag not on the remote yet - pushing it first"; git push origin "refs/tags/$TAG"; }
    gh release create "$TAG" --draft --title "ReelGuard $VERSION" \
      --notes-file "$NOTES" "$ASSET" "$SUMS"
    echo "draft release created - review & publish at https://github.com/$SLUG/releases"
  else
    echo "gh not installed - upload manually at:"
    echo "  $NEW_URL"
    echo "files: $(basename "$ASSET") $(basename "$SUMS") + paste NOTES.md"
  fi
fi

echo
echo "== staged in $OUT =="
ls -la "$OUT" | awk 'NR>1 {print " ", $5, $9}'
echo "next: review $NOTES, then re-run with --push (and --gh, or upload at $NEW_URL)"
