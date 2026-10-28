#!/usr/bin/env bash
# Generate the ReelGuard signing keystore. Local-only material: the keystore is
# git-ignored; keep it stable across builds or signature-based data survives
# only per install (see docs/BUILD.md, "signature & updates").
#
# Env: REELGUARD_KEYSTORE (default <repo>/signing/reelguard.keystore)
#      REELGUARD_PASS   (default reelguard)  REELGUARD_ALIAS (default reelguard)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE=${REELGUARD_KEYSTORE:-$ROOT/signing/reelguard.keystore}
KEYSTORE="${KEYSTORE/#\~/$HOME}"
PASS=${REELGUARD_PASS:-reelguard}
ALIAS=${REELGUARD_ALIAS:-reelguard}

if [ -f "$KEYSTORE" ]; then
  echo "already exists: $KEYSTORE"
  exit 0
fi
mkdir -p "$(dirname "$KEYSTORE")"

keytool -genkeypair \
  -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=ReelGuard, O=ReelGuard, C=US"

echo "generated $KEYSTORE (alias $ALIAS)"
echo "tip: record the credentials in signing/keystore.properties (see the example)"
