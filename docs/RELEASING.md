# Releasing

Release = **a git tag + GitHub release assets**. The apk (≈245 MB) never
enters the git tree — GitHub warns past 100 MB and history bloats; release
assets host up to 2 GB for free. Everything is cut by
[`pipeline/cut-release.sh`](../pipeline/cut-release.sh) from a local build.

## The one rule: signature compatibility

Users can only update an installed apk from a build signed with the **same
key**. The maintainer's keystore therefore never changes, and is never
committed (`signing/` is git-ignored by design — `generate-keystore.sh`
recreates a fresh one, which is only correct for *self-builders*, whose
builds then differ in signature from released ones).

| Install | Update from | Must |
| --- | --- | --- |
| released apk (v0.6.0) | next released apk | nothing — same key |
| released apk | self-built apk | uninstall first (data wiped) |
| self-built apk | self-built, same keystore | nothing |
| any | Play's Instagram | uninstall first |

## Cut checklist

1. **Version bump** in `app/build.gradle.kts` (`versionCode` +1, `versionName`
   semver), commit as `chore(release): v0.6.1`. The tag is derived from
   `versionName` — the release must be cut from a clean build of exactly
   that commit.
2. **Full pipeline** on the tagged state (see [BUILD.md](BUILD.md)): tests →
   `assembleDebug` → `merge-splits` → `embed-patch`. Confirm the smoke lines
   (`entry-in-c3: 1`, `entry-in-c24`) and a device sanity pass
   (`adb logcat -s ReelGuardPatch`).
3. **Stage**:

   ```bash
   pipeline/cut-release.sh <signed.apk>          # → dist/
   ```

   Produces `dist/instagram-reelguard-<v>.apk`, `SHA256SUMS`, `certs.txt`
   and — once — `NOTES.md` (edit by hand; `rm dist/NOTES.md` to regenerate).
   The script also creates the local annotated tag `v<version>`.
4. **Publish**:

   ```bash
   pipeline/cut-release.sh <signed.apk> --push    # push the tag
   pipeline/cut-release.sh <signed.apk> --push --gh   # + draft release via gh
   ```

   `--gh` needs the [gh CLI](https://cli.github.com/) authenticated; without
   it, upload `dist/*` at the printed `…/releases/new?tag=…` URL and paste
   `NOTES.md`. Draft first, read it once as a user, then publish.
5. **Post**: the tag's commit is the release; `main` moves on. Old releases
   stay downloadable — their signature keeps working for updates.

## What users can verify

```bash
shasum -a 256 instagram-reelguard-<v>.apk        # must match SHA256SUMS
apksigner verify --print-certs instagram-reelguard-<v>.apk
```

…the certificate SHA-256 printed there must match the fingerprint in the
release notes (`dist/certs.txt` / NOTES.md "Signature").

## Why no release CI

The final artifact needs the **Play splits as input** (device/licensed
material, ~250 MB, per-device variants) — there is nothing self-contained
for a runner to build from. Module + unit tests could run in CI later; the
apk itself is always cut locally. Keep the splits archived per release
version (private), so a rebuilt-with-same-key artifact stays possible.

## Emergency rebuild with the same signature

Only the original keystore can produce an update-compatible apk. If it is
lost, the release train restarts: new key → next minor gets a
"requires reinstall" note in its release summary. This is why
`signing/keystore.properties` + keystore backups are machine-level
responsibility, not repo state.
