# Architecture

ReelGuard is one Kotlin module compiled to a single dex that lives inside the
Instagram process. Paths below are relative to
`app/src/main/java/dev/pier/reelguard/`.

```
Instagram process (main thread only)
│
│ com.instagram.app.InstagramAppShell.onCreate          ← the ONE patched line
└─▶ igpatch/EmbeddedEntry.onApp(Application)            once-per-process guard
    └─▶ igpatch/EnforcementController.attach            owns everything below
        ├── Application.ActivityLifecycleCallbacks      resumed-activity tracking
        ├── igpatch/ModuleGraph                         wiring, one per process
        │     ├── core/UsageEngine        ← core/UsageLedger ← data/SharedPreferences*
        │     │     ├── core/Surface            (reels_tab · dm_player · feed_reel)
        │     │     └── core/DetectionRules ◀── igpatch/data/EmbeddedRules (data!)
        │     └── settings: core/SettingsRepository (SharedPreferences)
        ├── 1 Hz ticker ─┬─ igpatch/SurfaceProbe      decor BFS → surface + handles
        │                ├─ core/UsageEngine.accrueSecond
        │                ├─ evaluate() → igpatch/ui/ActivityOverlay (+ igpatch/VideoControl)
        │                └─ igpatch/ui/RemainingHud
        ├── igpatch/TabLongPress         settings entry (long-press Reels tab)
        └── igpatch/debug/DebugReceiverInstaller   adb control broadcasts
```

`core/` and `data/` (minus `EmbeddedRules`) are plain JVM Kotlin — unit
tested, no Android imports except the `SharedPreferences` adapters in
`data/`. Everything Android-specific lives under `igpatch/`.

## One-time entry, one instance

`EmbeddedEntry.onApp` is the entire host contract: `@JvmStatic`, idempotent
(`synchronized` first-seen flag), delegating to `EnforcementController.attachOnce`
(a `synchronized` singleton). The smali call-site that reaches it is documented
in [BUILD.md](BUILD.md); nothing else in the host refers to the module.

The controller registers two things on the `Application`:

- **ActivityLifecycleCallbacks** — tracks the resumed activity; on pause/
  destroy it tears down overlay, HUD, settings dialog and surface state.
- **A runtime-registered BroadcastReceiver** (`debug/`) — exported on API 33+
  so `adb` reaches it without a manifest (the module has none of its own).

## The 1 Hz tick

`EnforcementController` posts a `Handler` runnable every
`TICK_INTERVAL_MS = 1000`. Each `tick()`:

1. **Probe window** — if a debug probe is active, dump view-class/fragment
   names (tag `ReelGuardProbe`).
2. **`scanSurface()`** — `SurfaceProbe.scan(resumedActivity, rules)`:
   breadth-first walk of the decor collecting which rule view-ids are
   *effectively visible* (`isShown && VISIBLE && non-empty globalVisibleRect`).
   This strictness matters: Instagram keeps tab fragments mounted after
   navigation, and leftover ids from the reels pager would otherwise win the
   verdict on the feed. The walk also captures the control handles — the
   `SimpleVideoLayout` instance (`VideoControl.isReelVideoView`) and the first
   visible paused-marker view. View-id → name mapping is resolved once per
   activity (`WeakHashMap` cache) via `resources.getIdentifier`.
   Then `TabLongPress.maybeAttach` arms the settings gesture on the tab-bar
   cell (once per view instance — Instagram recreates the bar).
3. **Accrue** — if a surface is up: `engine.accrueSecond(surface,
   playing, screenInteractive)`. Playing comes from the scan's
   paused-marker verdict; surfaces without a marker (feed) count as playing
   while visible (`DEFAULT_PLAYING_WITHOUT_MARKER`). Accrual is gated on the
   surface being budgeted, actually-playing, screen-on — and the ledger
   clamps at the limit so `usedSeconds` never overshoots.
4. **`evaluate()`** — the enforcement state machine (below).
5. **`updateHud()`** — remaining-budget pill unless hidden (no budgeted
   overlay-capable surface, screen off, or cover showing).

Every stage is `runCatching`-guarded: a hostile host state degrades to a
skipped tick, never a crash inside Instagram.

## Detection data model

Rules are **data** (`igpatch/data/EmbeddedRules.kt`, JSON parsed by
`core/DetectionRules.kt`), because host updates move ids, not logic:

```jsonc
"reels_tab": {
  "coreIdHints":  ["…id/clips_video_container"],       // ALL must be present
  "idHints":      ["…id/root_clips_layout", …],        // disambiguation
  "negativeIdHints": ["clips_action_bar_*", …],        // veto; bare/prefixed/glob
  "pausedWhenIdPresent": "…id/clips_pause_button",     // marker ⇒ NOT playing
  "windowActivityPatterns": […],                       // documentation only
  "samples": [{ "label": …, "presentIds": […], "expectPlaying": true }, …]
}
```

- Surfaces evaluate in `Surface` declaration order; first match wins.
- A rule matches when all `coreIdHints` co-occur (or, if none are defined,
  any `idHint`), minus negative vetoes. Negatives accept bare ids,
  package-qualified ids and `prefix*` globs.
- `pausedWhenIdPresent` encodes the live-verified invariant
  **`clips_pause_button` present ⟺ paused** (while playing, the button does
  not exist in the hierarchy at all). It doubles as the "which view to
  click to resume" handle.
- `samples` are regression fixtures: `DetectionRulesTest` replays each against
  the parser and asserts surface + playing verdict.

Current tables (IG **453.0.0.55.79**) and the re-harvest runbook:
[DETECTION.md](DETECTION.md).

## Surfaces

| Surface | Budgeted by default | Overlay | Marker | Notes |
| --- | --- | --- | --- | --- |
| `reels_tab` | yes | yes | `clips_pause_button` | full-screen player + tab bar; vetoes on DM reply-bar ids |
| `dm_player` | yes | yes | `clips_pause_button` | same clips viewer shell in a modal host + DM chrome |
| `feed_reel` | yes | **no** | — (`video_container`…) | counted only; `Surface.overlayEnforced = false` |

The feed's count-only semantics are a product decision: an overlay over the
home feed would fight the infinite-scroll idiom. `UsageEngine.shouldShowOverlay`
honors `overlayEnforced` mechanically, so new surfaces get the right behavior
by declaration.

## Enforcement & the pause/verify state machine

`evaluate()` runs after every scan, accrual and settings change:

```
            ┌────────────────────────────────────────────────────────┐
            │ budgeted ∧ overlay-capable ∧ exhausted ∧ ¬snooze        │
            ▼                                                        │
  ┌───────────────────┐  pause fails/missing   ┌───────────────────┐ │
  │  cover NOT shown  │ ─────────────────────▶ │ cover shown       │ │
  │  (HUD visible)    │   pausePlayback("enforce") │ pausedByUs=true│ │
  └───────────────────┘                        └─────────┬─────────┘ │
        ▲  ▲│ budget returns                             │ verify loop
        │  ││◀─────────────────────────────────────────── ┘ 400 ms
        │  └│ hide cover + resumePlayback + pausedByUs=false        ▼
        │   │                                              ┌──────────────────┐
        │   └──────────── enforce-retry every 2 s ────────▶│ re-probe marker  │
        │                                                  │ verified? stop   │
        │                                                  │ <1.2 s? re-read  │
        │                                                  │ attempts<6? tap  │
        │                                                  │ else: log loudly,│
        │                                                  │ cover is fallback│
        │                                                  └──────────────────┘
```

Deliberate details:

- **Never blind-toggle.** `VideoControl.pause` tries, in order: a no-arg
  reflective `pause*`/`suspendPlayback` on the video view (future-proofing),
  then a **real injected touch**, then `performClick` as last resort. The
  injected DOWN/UP (80 ms apart) lands at the video's center +210/+150 px —
  clear of the centered mute/pause overlays — and is dispatched on the
  **content host (`android.R.id.content`), not the decor**: the enforcement
  cover lives on the decor *above* the content, and a decor dispatch while it
  shows would be absorbed by our own scrim.
- **Verify-then-retry.** 400 ms after each attempt the marker is re-probed.
  A `1.2 s` minimum gap between real taps covers the paused-marker render
  latency — an immediate re-tap lands on the just-paused video and *resumes*
  it (the classic double-press bug). Six attempts (~2.4 s) span the pause
  overlay animation; exhaustion is logged on `ReelGuardPatch`, the cover
  remains as the visual fallback, and `evaluate` re-arms every 2 s.
- **Resume** prefers `clips_pause_button.performClick()` (the button exists
  exactly while paused), else the same touch path — so un-pausing uses the
  host's own widget semantics.

## Overlay & HUD

Both are plain views added directly to the activity's `decorView` — no
dialogs, no windows, no permissions:

- **`ui/ActivityOverlay`** — full-match `FrameLayout`, near-opaque dark
  (`#FA141419`), centered ✋🏻 + “Reels · daily limit reached”, clickable to
  *absorb* touches (exit = system back or surface change). No buttons by
  design; the pause must hold while it shows.
- **`ui/RemainingHud`** — top-right pill `left m:ss`, dark plate, 2 dp stroke;
  recreated per host activity, hidden under the cover / screen-off /
  non-budgeted surfaces.
- **`ui/SettingsDialog`** — an `android.app.Dialog` themed dark: limit stepper
  (1-min steps ≤ 5, then 5; store accepts 1–1440 for adb-driven tests), a
  budgeted-toggle per surface, reset-today. Cold-start variant appears once
  when the app opens with the budget spent.

**Every view is constructed through `ContextThemeWrapper(context,
android.R.style.Theme_DeviceDefault)`.** Instagram's runtime theme breaks
themed-attribute resolution for foreign views — unwrapped contexts crash
with `ThemeDeviceDefault`-missing attr errors. This was the recurring
production bug; it is a hard invariant (AGENTS.md).

Strings are literals in `ui/Str.kt` — the module must not resolve its own
`R.string/id` at runtime (host table collisions); the single
`Palette.kt` per layer holds ARGB constants only.

## Persistence

One `SharedPreferences` file, **`reelguard`**, in the host's data dir
(`ModuleGraph.PREFERENCES_NAME`):

| Key | Type | Meaning |
| --- | --- | --- |
| `budget_day_epoch_day` | long | local-date epoch day of the ledger |
| `budget_used_seconds` | int | seconds accrued that day |
| `limit_minutes` | int | daily budget (clamped 1–1440 on write) |
| `surface_budgeted_<id>` | bool | per-surface kill switch (default = declaration) |
| `snooze_until_millis` | long | absolute expiry; 0 = none |

`UsageLedger` reads-as-zero on a stale day and rolls the day forward on the
next write; `accrue` clamps to the limit so budget checks are exact.

## Threading & lifecycle

Main thread only — lifecycle callbacks, receiver.onReceive, ticker, view
mutations. Cross-thread state is limited to `@Volatile` singletons
(`EnforcementController.instance`, `EmbeddedEntry` first-seen flag). Anything
touching views is wrapped in `runCatching`; failures log under
`ReelGuardPatch` and skip the tick.

## Build-time composition

The module is an `application`-plugin Android project whose *only* consumable
is `classes*.dex` (see [BUILD.md](BUILD.md)): the pipeline baksmalis the
module, re-smalis it into one dex appended as `classes24.dex`, and stitches a
single `invoke-static` into `InstagramAppShell.onCreate`. No manifest
components of ours ship; `minSdk 27`, `targetSdk 34`; zero `implementation`
dependencies — reflection only against host classes, and `org.json` comes from
the Android platform at runtime (`testImplementation` here purely so the rule
parser is unit-testable on the JVM).

## Adding a surface

1. `Surface` entry (order = match priority; decide `budgetedByDefault`,
   `overlayEnforced`).
2. Rule JSON in `EmbeddedRules` — core id, hints, vetoes, marker if one
   exists, and samples.
3. `DetectionRulesTest` fixture +, if overlaying, a `SettingsDialog` row comes
   free (`Surface.entries`).
4. Re-harvest real ids per [DETECTION.md](DETECTION.md) before shipping.
