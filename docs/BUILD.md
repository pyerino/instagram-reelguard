# Build & patch pipeline

From clean clone to an Instagram apk with ReelGuard inside. Everything here is
deterministic file surgery — no framework, no runtime injection.

```
module sources ──gradlew──▶ app-debug.apk
                                   │
Instagram store splits ─APKEditor──▶ merged.apk ──baksmali/smali+zip──▶ patched.apk
                                                                        │ zipalign + apksigner (reelguard.keystore)
                                                                        ▼
                                                        instagram-reelguard.apk ──adb install──▶ 📱
```

## 0. Toolchain

| Tool | Used for | Notes |
| --- | --- | --- |
| JDK 17 | Gradle, keytool, `java -jar` | AGP 8.7 requires 17 |
| Android SDK, platform 35, build-tools ≥ 34 | module compile; `zipalign`, `apksigner` | `ANDROID_HOME` or `local.properties` — fresh clones auto-probe both plus the default SDK locations and write `local.properties` when nothing serves platform 35 |
| `adb` | install, device verification | platform-tools |
| `python3`, `zip`, `unzip` | call-site insert, repack | stock on macOS/Linux |
| smali/baksmali 2.5.2, APKEditor 1.4.9 | dex surgery, split merge | `pipeline/fetch-tools.sh` (≈9 MB into `pipeline/.tools/`, git-ignored) |

## 1. Build the module

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

The **debug** build is the embedding artifact (its dexes are extracted and
recompiled; ProGuard/R8 is off — see `app/build.gradle.kts`). Run the tests
too when you touched `core/` or `data/`: `./gradlew :app:testDebugUnitTest`.

## 2. Fetch the patch toolchain

```bash
pipeline/fetch-tools.sh
```

Downloads APKEditor plus smali/baksmali 2.5.2 **and its exact dependency
closure** from Maven Central into `pipeline/.tools/`. Re-runnable; offline
after the first run.

## 3. Merge the store splits

Instagram ships as split apks. Collect the set for your device:

```bash
adb shell pm path com.instagram.android     # lists base.apk + split_config.*.apk
adb pull <each path> splits/
```

Then merge:

```bash
pipeline/merge-splits.sh splits              # → merged-instagram.apk
```

APKEditor rebuilds one installable apk (resources, all 23 dexes, manifest).
Keep the merged apk around — re-patching a new module does not need a new
merge (name it per IG version, e.g. `merged-ig453.apk`, and pass it as arg 2).

## 4. Embed, align, sign

```bash
pipeline/embed-patch.sh app/build/outputs/apk/debug/app-debug.apk [merged.apk] [outdir]
# → instagram-reelguard.apk (+ certificate printout)
```

What it does, in order:

1. **baksmali** the merged apk's `classes3.dex` and every module dex.
2. Recompiles the module tree into a **single new dex**, destined for
   `classesN.dex` (default `classes24`) — the module never inflates IG's
   existing dexes.
3. Inserts the single call-site into
   `com/instagram/app/InstagramAppShell.smali`, method `onCreate()V`, right
   after the `.registers/.locals` directive:

   ```smali
   invoke-static/range {p0 .. p0}, Ldev/pier/reelguard/igpatch/EmbeddedEntry;->onApp(Landroid/app/Application;)V
   ```

   Idempotent: a second run detects `EmbeddedEntry` and skips.
4. **smali** classes3 back, copies both dexes into a copy of the merged apk
   (`zip` updates entries in place).
5. `zipalign -f 4`, then `apksigner sign` and a `verify --print-certs`
   sanity print, then greps both dexes for `EmbeddedEntry` as a smoke check.

### The `classes24` invariant

Instagram 453 ships `classes1.dex … classes23.dex`. The embed appends the
module as the **next free index** because:

- dex filenames are loaded by the `Application` startup path in order; a
  **collision with an existing index would silently replace host code**;
- the name is part of the pipeline contract (zip entry, smoke-check greps).

If a future IG has 24+ dexes, the script refuses and asks for
`REELGUARD_DEX_INDEX=<n+1>` — do not lower it onto an existing dex.

### Signing & keystore resolution

`embed-patch.sh` resolves the keystore **first hit wins**:

1. env `REELGUARD_KEYSTORE` / `REELGUARD_PASS` / `REELGUARD_ALIAS`
   (`REELGUARD_KEY_PASS` for a distinct key password)
2. `signing/keystore.properties` — `storeFile` (relative to `signing/` unless
   absolute), `storePassword`, `keyAlias`, `keyPassword`
3. defaults: `signing/reelguard.keystore`, password and alias `reelguard`

Missing keystore? It is generated on the fly via
[`signing/generate-keystore.sh`](../signing/generate-keystore.sh) (RSA 2048,
10 000 days, CN=ReelGuard). The keystore is **local material — never
committed** (`.gitignore` covers `*.keystore`, `keystore.properties`).

### Environment reference

| Variable | Default | Meaning |
| --- | --- | --- |
| `REELGUARD_DEX_INDEX` | `24` | index of the appended module dex |
| `REELGUARD_KEYSTORE` / `_PASS` / `_ALIAS` / `_KEY_PASS` | properties file / `reelguard` | signing material |
| `REELGUARD_BUILD_TOOLS` | auto-discover | build-tools dir containing `zipalign`+`apksigner` |
| `REELGUARD_WORK` | `mktemp -d` | scratch dir; `REELGUARD_KEEP_WORK=y` keeps it |
| `REELGUARD_TOOLS` | `pipeline/.tools` | where `fetch-tools.sh` puts / scripts find jars |

## 5. Install

```bash
adb uninstall com.instagram.android        # signature differs from Play's
adb install instagram-reelguard.apk
adb shell am start -n com.instagram.android/.activity.MainTabActivity
```

- **Expect one login**: a new signature means new app data. There is no
  in-place upgrade from the Play build.
- **Rollback**: `adb uninstall com.instagram.android` then install the
  original splits (or a Play download). Re-patching over your own build keeps
  the signature, so `adb install -r` works there.
- **MIUI/HyperOS**: the system may show an install-confirmation sheet for
  adb installs — confirm on the screen or the `adb install` times out.
- Verify the module is alive:

  ```bash
  adb logcat -s ReelGuardPatch ReelGuardProbe
  ```

## Retargeting a newer Instagram

1. **Merge** the new splits (step 3) and count dexes:
   `unzip -l merged.apk | grep -c 'classes.*\.dex'`. If ≥ 24, export
   `REELGUARD_DEX_INDEX=<count+1>`.
2. **Insertion point**: confirm
   `com/instagram/app/InstagramAppShell.smali` still exists in `classes3`
   and still has an `onCreate()V`. If the application class moved (check
   `AndroidManifest.xml` `android:name=` in the merged apk), adapt
   `APP_SMALI` and the smali call in `pipeline/embed-patch.sh` — it is one
   path plus one `invoke-static` line.
3. **Detection ids**: run the re-harvest checklist in
   [DETECTION.md](DETECTION.md) — usually the only real work. Update
   `EmbeddedRules`, bump its `igVersionName`/`igVersionCode`, and let
   `DetectionRulesTest` guard the samples.
4. Re-run steps 1→4; device-verify with the probe broadcast
   (`--ez probe true`) on each surface before trusting it.

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| `no onCreate()V + registers/locals match` | obfuscation changed the shell method — locate the new `Application` subclass, adapt the recipe (above) |
| `merged apk already has classes24.dex` | IG grew a dex — raise `REELGUARD_DEX_INDEX` |
| `apksigner verify` fails after sign | stale zipalign — the pipeline aligns before signing; make sure you sign the *aligned* file (the script does) |
| Crash on any ReelGuard view at runtime | a view escaped the `ContextThemeWrapper` wrap — every view, every time (see AGENTS.md §invariants) |
| Settings/overlay texts render with wrong theme colors | same — theme-wrap, never style via IG resources |
| `adb install` hangs on MIUI | confirm the on-screen prompt |
| ReelGuard silent after IG update | ids moved — re-harvest (DETECTION.md), check `ReelGuardPatch` logs for `pause skipped: no video view` |
