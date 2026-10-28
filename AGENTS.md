# AGENTS.md — guide for AI collaborators (and humans in a hurry)

ReelGuard is a Kotlin module **embedded into Instagram's dexes**. It runs
inside the host process with no framework, no resources of its own and one
smali call-site — most traditional Android assumptions do not hold. Read the
invariants before changing anything; they are all load-bearing and all were
learned the hard way.

## Ground rules

- **Scope**: everything ships inside `com.instagram.android`. If a change
  would need a manifest entry, a new dependency, a resource, or a second
  process — rethink; the answer is usually reflection against host classes
  or programmatic views.
- **Verify on device, not in theory.** The unit tests cover the pure engine
  only (`core/`, `data/`). Anything touching views/activities needs a real
  device run: see "Device loop" below.
- **Never break the host.** Every host-touching call is `runCatching`-guarded
  and must fail soft; a crash in our code is a crash in Instagram.

## Commands

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug  # tests + apk
./gradlew --no-daemon :app:dependencies                          # graph sanity

# full pipeline (needs device splits; see docs/BUILD.md)
pipeline/fetch-tools.sh
pipeline/embed-patch.sh app/build/outputs/apk/debug/app-debug.apk

# device loop
adb install -r instagram-reelguard.apk          # same signature → keep data
adb logcat -s ReelGuardPatch ReelGuardProbe     # our two tags ONLY
adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.SET_LIMIT --ei minutes 1
adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.RESET_TODAY --ez probe true
```

SDK lives at `~/Library/Android/sdk` or `$ANDROID_HOME`; fresh clone wants
`cp local.properties.example local.properties` (or export ANDROID_HOME).
JDK 17. No other toolchain.

## Hard invariants — do not violate

1. **Theme-wrap every view, every time.**
   `ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)` for the
   *root context of each view subtree* — overlay, HUD, dialog, all of them.
   Instagram's runtime theme crashes foreign views resolving themed attrs.
   This bug has shipped three times; it is the first thing to check when a
   view misrenders or crashes.
2. **No module resources at runtime.** The dex is merged into the host: our
   `R.drawable.x`/`R.string.y` resolve against *Instagram's* table — wrong
   entries or collision. All strings are literals (`ui/Str.kt`); all colors
   are ARGB constants (`Palette.kt`); all ids are resolved via
   `resources.getIdentifier("name", "id", hostPackage)`. Do not add
   `res/`, do not import the module `R`.
3. **`classes24` discipline.** The module dex must be the next free index
   (453 has 1–23); a collision silently replaces host code. The pipeline
   refuses on collision — raise `REELGUARD_DEX_INDEX`, never lower it onto an
   existing dex.
4. **One call-site, one entrypoint.** The whole host integration is the
   single `invoke-static` in `InstagramAppShell.onCreate` →
   `EmbeddedEntry.onApp`. Keep `EmbeddedEntry` `@JvmStatic` and its signature
   frozen — the smali line in `pipeline/embed-patch.sh` is typed against it.
   The package `dev.pier.reelguard.igpatch` is part of that contract too.
5. **Never blind-toggle playback.** Pause = method-if-present → real injected
   touch → performClick, then **verify via the paused-marker re-probe** and
   only re-issue while unconfirmed (400 ms × 6, ≥1.2 s between real taps —
   the pause overlay has render latency; a fast second tap *resumes*).
   Dispatch touches on the **content host**, not the decor (our scrim lives
   above the content and eats decor dispatches).
6. **Single signature.** One keystore, stable across builds, or every update
   is an uninstall (data loss). Credentials come from env/properties only —
   **never commit keystores or passwords**; `signing/generate-keystore.sh`
   regenerates.
7. **Detection is data.** New ids go into `EmbeddedRules` via
   [docs/DETECTION.md](docs/DETECTION.md) — with samples. Never hardcode an
   id in Kotlin, never match on `X/*` classes, hex ids or locale strings.
8. **Visibility-gate every scan.** Leftover fragments are real: a view only
   exists for detection if `isShown && VISIBLE && non-empty globalVisibleRect`
   (`SurfaceProbe.effectivelyVisible`). Weaken it and the feed inherits the
   reels pager's ghost ids.
9. **Main thread only.** Lifecycle, receiver, ticker, views. No executors, no
   handlers of our own beyond the existing tick.

## Logging & debugging

- `ReelGuardPatch` — control flow: pause/resume attempts + verification,
  enforce-retries, skipped ticks, settings. `Log.i`/`w` only, host-safe.
- `ReelGuardProbe` — the 60 s ground-truth dump (`--ez probe true` on any
  control broadcast): visible ids, view classes, fragment names, per-second.
- The overlay has no buttons by design: exit = back. To watch state
  changes live: `adb logcat -s ReelGuardPatch` while tapping.

## Device quirks (device-specific, learned by scar tissue)

- **MIUI/HyperOS**: adb installs pop a system confirm sheet — tap it or the
  install times out; `adb install` exit 0 with no app = you missed it.
- **`adb exec-out screencap -p`** intermittently returns 0 bytes on video
  surfaces. Retry, or `screencap /sdcard/x.png && adb pull`.
- **`uiautomator dump`** often can't reach idle while video plays → "ERROR:
  could not get idle state". Pause the video first, or retry a few times.
- **Play-signature ≠ our signature**: installing over Play's build requires
  uninstall first (wipes login). Rollbacks likewise. Budget for one re-login
  per signature change.
- **IG keeps fragments mounted** after tab switches — see invariant 8 before
  trusting any "present id" from a dump taken right after navigation.

## Commits & PRs

- Conventional Commits, imperative, no noise:
  `feat(enforce): …`, `fix(pause): …`, `docs: …`, `chore(build): …`.
  Scope by layer: `core`, `rules`, `patch`, `enforce`, `ui`, `pipeline`,
  `docs`. History is clean by design — no "wip", no "fix typo" floods; squash
  before sharing.
- A PR changes **behavior, docs, or both — say which**. Behavior changes to
  pause/verify must state the device evidence (logcat excerpt) in the body.
- Tests: engine changes need `core/` coverage; rule changes need refreshed
  `samples[]` (that IS the test). Never weaken an assertion to green.
- Docs are part of the code: `ARCHITECTURE.md` (how it works),
  `DETECTION.md` (ids + harvest), `BUILD.md` (pipeline) must match reality
  after your change — stale docs here cost device-hours.
- No binaries in git: apks, keystores, toolchain jars are generated
  (`.gitignore` covers them; gradle-wrapper.jar is the exception).
- No absolute paths or credentials in tracked files — env, properties
  templates and relative paths only.

## Where things are

| Touch | File(s) |
| --- | --- |
| Budget/snooze/rollover | `app/.../core/UsageEngine.kt`, `UsageLedger.kt` |
| Detection semantics | `app/.../core/DetectionRules.kt`, `.../igpatch/data/EmbeddedRules.kt` |
| Scan/probe | `app/.../igpatch/SurfaceProbe.kt` |
| Pause/resume mechanics | `app/.../igpatch/VideoControl.kt`, `EnforcementController.kt` |
| Overlay/HUD/settings UI | `app/.../igpatch/ui/` |
| Host entry + wiring | `app/.../igpatch/EmbeddedEntry.kt`, `ModuleGraph.kt` |
| adb controls | `app/.../igpatch/debug/DebugReceiverInstaller.kt` |
| dex surgery | `pipeline/embed-patch.sh` |

(`app/...` = `app/src/main/java/dev/pier/reelguard`.)
