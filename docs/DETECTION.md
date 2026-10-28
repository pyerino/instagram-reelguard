# Detection: rules, current tables, re-harvest runbook

Surface detection is **data, not code**
(`app/src/main/java/dev/pier/reelguard/igpatch/data/EmbeddedRules.kt`, parsed by
`core/DetectionRules.kt`). An Instagram update normally means re-running the
harvest at the bottom of this file — never touching logic.

## Principles

1. **Match view-id resource *names*, never hex values, never class names.**
   Hex ids are rehashed every build; `X/*` smali symbols are R8-renamed. Id
   *names* (`com.instagram.android:id/clips_pause_button`) survive in
   `public.xml` and in `AccessibilityNodeInfo.viewIdResourceName` / the live
   `View` hierarchy — Instagram keeps them un-obfuscated for its own tests.
2. **One window, many fragments.** Everything runs in
   `InstagramMainActivity` (+ modal hosts); surfaces differ by *which ids are
   co-visible*, not by activity. Hence set intersection, plus negative vetoes
   for shared shells (the DM viewer renders the whole clips viewer!).
3. **Only visible ids count.** IG keeps tabs mounted after navigation; the
   probe walks the decor but counts a view only if it `isShown`, is `VISIBLE`
   and has a non-empty `globalVisibleRect` (`igpatch/SurfaceProbe.kt`).
4. **Paused ⟺ marker present.** On the reels/DM surfaces, while a video
   *plays*, no play/pause button exists in the hierarchy at all;
   `clips_pause_button` appears exactly when paused (verified across long
   sessions, not a transient toast). One id ⇒ the playing verdict *and* the
   resume-click handle.
5. **Feed counts, never overlays.** `feed_reel` has no trustworthy marker
   (its `video_container` leaks into other surfaces), so it counts as playing
   while visible — acceptable for a count-only surface
   (`Surface.overlayEnforced = false`).

Signal-stability ranking, for when you must choose a new id (best first):
window component names → view-id names → non-obfuscated `com.instagram.*` /
`instagram.features.*` classes → literal text (locale!) → hex ids → `X/*`
symbols. Never runtime-depend on the last two.

## Rule schema

| Field | Semantics |
| --- | --- |
| `coreIdHints` | ALL must be present (confident match) |
| `idHints` | if no core ids: ANY present; else documentation/disambiguation |
| `negativeIdHints` | veto; bare id, `pkg:id/name`, or `prefix*` glob; `(...)` remarks ignored |
| `pausedWhenIdPresent` | marker: present ⇒ surface ∧ NOT playing; also the resume-click handle |
| `windowActivityPatterns` | documentation only — matching never uses activities |
| `textOrDescHints` | reserved; keep empty, text is locale-dependent |
| `samples[]` | regression fixtures replayed by `DetectionRulesTest` |

Evaluation order = `Surface` declaration order (`reels_tab`, `dm_player`,
`feed_reel`); first match wins — put the most specific surface first and make
the broader ones veto the specific shell (see the reels/DM pair below).

## IG 453.0.0.55.79 — harvested tables

`versionCode 385914243`, `minSdk 28`. Live-verified via uiautomator tree
dumps on-device; static smali/resource analysis as cross-check.

### Shared shell

| id | role |
| --- | --- |
| `tab_bar` | bottom navigation (5 cells; `clips_tab` = Reels) |
| `clips_video_container` | reels pager container |
| `root_clips_layout` | viewer root, shared reels ↔ DM viewer |
| `clips_viewer_view_pager` | the vertical pager |
| `clips_media_component` | media block inside a page |
| `scrubber` | progress strip |

### `reels_tab`

- core: `clips_video_container`
- hints: `root_clips_layout`, `clips_viewer_view_pager`,
  `clips_media_component`, `clips_ufi_more_button_component`, `scrubber`,
  `tab_bar`
- veto: `clips_action_bar_*` (that's the camera/editor family),
  `story_item_top_progress_bar_stub`, `reply_bar_container`,
  `reel_viewer_message_composer` — the latter two carve the DM viewer out
- marker: `clips_pause_button`

### `dm_player`

- core: `root_clips_layout` **and** `reply_bar_container`
- hints: `reel_viewer_message_composer`, `sender_username_or_fullname`,
  `sender_timestamp`, `scrubber`, `clips_media_component`,
  `clips_viewer_view_pager`, `reply_bar_emoji_button_inside_composer`
- marker: `clips_pause_button` (same viewer shell ⇒ same marker)

### `feed_reel`

- core: `video_container`
- hints: `video_states`, `feed_tab`, `tab_bar`
- veto: `root_clips_layout`, `reply_bar_container`
- no marker ⇒ counts as playing while visible

### Ids that look useful but are NOT (453 ground truth)

`clips_video_player`, `clips_ufi_like_button`, `video_player_view`,
`video_player_container`, `player_controls_top/bottom` — present in static
resources, absent from every runtime tree we captured. Do not re-add without
a live dump showing them.

### Paused-marker verification protocol (the load-bearing rule)

1. Reels tab, video **playing**: dump ⇒ no `clips_pause_button`, no
   `clips_play_button` (while playing, neither exists).
2. Tap to pause: dump ⇒ `clips_pause_button` present inside
   `clips_pause_and_mute_component`.
3. Repeat in the DM viewer — same result (shared shell).
4. Muted playback must still count: it does — the marker tracks pause state,
   not audio.

## Re-harvest runbook (per Instagram update, ≈15 min)

**Trigger**: any IG update you want to keep using ReelGuard with, or any
detection misbehavior (check `adb logcat -s ReelGuardPatch` first —
`pause skipped: no video view … ids=[…]` prints the live id set already).

### A. Runtime harvest (primary evidence)

1. Patch & install the new IG with the current module (pipeline may warn on
   the old ids — fine), or use the stock build for harvesting.
2. Open the probe window and exercise each surface for ~60 s:

   ```bash
   adb logcat -c && adb logcat -s ReelGuardProbe &
   adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.RESET_TODAY --ez probe true
   # play a reel; pause it; open a DM reel; scroll the feed…
   ```

   Each second logs `ids=[…]` — the visible-id set — plus view classes and
   fragment names. Compare against `EmbeddedRules`.
3. Cross-check ambiguous moments with uiautomator:

   ```bash
   adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
   grep -o 'resource-id="com.instagram.android:id/[^"]*"' ui.xml | sort -u
   ```

   ⚠️ `uiautomator dump` often times out waiting for idle **while video
   plays** — pause first, or retry; it succeeds on the paused UI.
   ⚠️ `adb exec-out screencap -p` intermittently yields 0 bytes on video
   surfaces — retry, or `screencap` to file + `adb pull`.
4. Verify the marker protocol (section above) on the new build, reels **and**
   DM.

### B. Static cross-check (optional, catches ids before first launch)

```bash
# decode with apktool, then audit the id families in one grep:
grep -E 'type="id" name="(clips_|reel_|video_|direct_|tab_|feed_)' \
  <decoded>/res/values/public.xml
# and confirm the hosts:
grep -nE 'InstagramMainActivity|MainTabActivity|ModalActivity|ClipsPiP' \
  <decoded>/AndroidManifest.xml
```

Every id referenced by `EmbeddedRules` must appear in `public.xml`; missing ⇒
renamed family — re-derive from the `clips_*` / `reel_*` / `video_*` families
and re-verify at runtime (step A).

### C. Ship the new rules

1. Edit `EmbeddedRules`: ids, `igVersionName`, `igVersionCode`, `source`.
2. Refresh `samples[]` with one real captured set per surface-state
   (playing/paused) — these are the regression tests.
3. `./gradlew :app:testDebugUnitTest` — `DetectionRulesTest` must pass.
4. Rebuild, re-embed, device-verify each surface with the probe; watch
   `ReelGuardPatch` for `pause verified`.

### Deprecation triggers — rebuild matchers when

- `InstagramMainActivity` vanishes or a `*Reels*Activity` appears (manifest).
- the `clips_*` family disappears (feature renamed) — re-derive from
  `reel_*`/`video_*` families.
- IG ships a real `MediaSession` for foreground playback — prefer it over
  view probes for the playing verdict (check
  `adb shell dumpsys media_session` during playback; on 453 there is none —
  only background-audio).
- PiP reels (`ClipsPiPInternalActivity`) become common — today they are a
  separate window and uncounted.
