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
        │                │                              (+ reels-pager index via reflection)
        │                ├─ core/UsageEngine.accrueSecond
        │                ├─ core/ReelCounter.onIndexObserved   (display-only reels/day)
        │                ├─ evaluate() → igpatch/ui/ActivityOverlay (+ igpatch/VideoControl)
        │                ├─ igpatch/FeedQuietHours.tick  feed quiet-hours: ribbon ·
        │                │                                opaque cell cover · flag-off nudge
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
5. **Reel counter** — while a reel-viewer surface is current, its viewer
   pager (`clips_viewer_view_pager` — a ViewPager **v1** whose child
   `RecyclerView` layout-manager position is the index, read via reflection
   since the module compiles against android.jar only; a ViewPager2 index
   stays the fallback) is compared against a per-surface **high-water mark**:
   only a new high-water while the played-verdict holds credits one reel
   (`core/ReelCounter`; display-only, same day-key/rollover/reset as the
   ledger). Back-scrolls never count and forward-again over already-seen
   reels neither — a reel is credited once per surface visit; a visit's
   entry reel never counts. Feed reels have no per-reel index and are not
   counted.
6. **`updateHud()`** — remaining-budget + reels-today pill unless hidden (no
   budgeted overlay-capable surface, screen off, or cover showing).

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
| `feed_reel` | yes | **no** | — (`video_container` **or** `video_states`, via `coreAnyIdHints`) | counted only; no enforcement overlay — but see [Feed quiet-hours](#feed-quiet-hours) |

The feed's count-only semantics are a product decision: an overlay over the
home feed would fight the infinite-scroll idiom. `UsageEngine.shouldShowOverlay`
honors `overlayEnforced` mechanically, so new surfaces get the right behavior
by declaration.

## Feed quiet-hours

A second, softer mechanism for the home feed (`igpatch/FeedQuietHours.kt`,
`FeedNudger.kt`, `NudgePolicy.kt` — all unit-tested). It runs after
`evaluate()` each tick and is **gated on the Reels-tab budget**: when
`reels_tab` is budgeted, unsnoozed and exhausted, and the current surface is
`feed_reel`, the feed quiets down in three layers:

1. **Best-effort pause/mute walk.** One walk per distinct feed-cell instance:
   the cell's view subtree, then two levels of declared fields (including
   nested `ViewGroups`), trying to pause and mute whatever player-shaped
   object turns up. "Success" is unverifiable in-process — IG's player is
   obfuscated and no public `ExoPlayer` exists in any dex — so hollow calls
   are expected and harmless. Cells rebind on scroll and the player restarts:
   the walk re-arms per new instance.
   **Maintenance.** The quiet is asserted, not assumed: while the cover
   shows, the walk re-asserts on a 2 s cadence (players rebind; the user's
   volume keys multiply the stream gain back up), and the media stream is
   *pinned to 0* on a ≥ 1 s attempt cadence — the pre-cover level is
   remembered and restored when the cover leaves, so volume presses land
   again the moment quiet-hours ends.
2. **Ribbon.** A slim non-clickable "✋ Reels · daily limit reached" bar above
   the tab bar (below it in the z-order sense: appended to the content host,
   the tab bar's sibling) — the feed remains fully scrollable.
3. **Opaque cover.** A ✋ cover on the centered (≥ 0.75-clipped) reel-post
   cell only, never over the stories tray or tab bar, and hidden while a
   scroll gesture is in flight. It **rides the cell**: parented inside the
   centered cell's container it scrolls natively with the content (re-parent
   on cell change; re-added if Litho strips it on rebind). Hosts that reject
   adopted children (`hostAddFailed`, logged once, never retried that
   session) get the fallback: a decor-level cover repositioned **per-frame**
   by a `Choreographer` callback — display-rate tracking, not the 1 Hz
   ticker — whose loop dies with its weak refs when the cover does. Like the
   enforcement cover it is clickable-to-absorb; exit by system back or
   surface change.

The deterministic quiet is the cover, not the pause.

**Quiet-scroll nudge — built, shipped flagged off** (`nudge_enabled=false`):
when a feed cell sits centered for ≥ 1.5 s, `FeedNudger` injects one skip
drag; `NudgePolicy` (pure, tested) allows one nudge per cell with a 3 s gap,
chains up to 3 then cools down 10 s, and grants a fresh budget whenever the
user scrolls manually. It exists to prevent the chore of scroll-past-reel
without turning the feed into a slot machine; it is one `adb shell settings
put` away for whoever wants it.

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
- **`ui/RemainingHud`** — top-right pill `left m:ss · N reels` (count capped
  at `999+`; contentDescription carries the un-abbreviated form), dark plate,
  2 dp stroke; recreated per host activity, hidden under the cover / screen-off /
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
| `reels_count_day_epoch_day` | long | local-date epoch day of the reel counter |
| `reels_count` | int | reels watched today (display-only) |
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
