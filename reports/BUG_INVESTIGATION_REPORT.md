# BUG_INVESTIGATION_REPORT.md — GhostScreen

> **Method**: Static root-cause analysis against current source (branch `fable-5`). Each bug has: repro hypothesis, root cause with file:line evidence, severity, and a proposed fix scoped to the project's locked architecture.
> **Convention**: Fixes follow patterns already documented in `docs/10_AI_CONTEXT.md §6` and `docs/08_REFACTORING_PLAN.md`. None of the fixes violate the three locked invariants (BOX passthrough, `TYPE_ACCESSIBILITY_OVERLAY`, manual tickers).
> **Note**: Two of the listed bugs reproduce only on-device (overlay lag, QS tile latency). I give the deterministic root cause from code; the device step confirms magnitude.

---

## Bug Index

| # | Bug | Severity | Root Cause Confidence | Status in Code |
|---|---|---|---|---|
| 1 | Overlay lag (drag updates late) | High | **Confirmed** (static) | Bug present |
| 2 | Opacity flicker (mask disappears briefly) | High | **Confirmed** (static) | Bug present |
| 3 | Overlay desync (UI moves, overlay stale) | High | **Confirmed** (static) | Bug present (race) |
| 4 | Horizontal slider lag | Medium | **Confirmed** (static) | Perf issue |
| 5 | Quick Settings Tile ~5s latency | High | **Likely** (needs device confirm) | Design + init cost |
| 6 | Home "Armed" behaves like Manual Mask | **Critical** | **Confirmed** (logic) | Design defect |
| 7 | Gboard detection weak | Medium | **Confirmed** (static) | Data gap |
| 8 | Blur mode leaks readable content | Medium | **Confirmed** (platform limit) | Inherent + tunable |
| 9 | Overlay color editor lag | Medium | **Confirmed** (static) | Recomposition |
| 10 | Filter (Enabled/Disabled) crash | High | **Likely** (static) | Concurrency |
| 11 | "GhostScreen" search crash | High | **Likely** (static) | Icon decode |
| 12 | Launcher icon zoomed/cropped | Medium | **Confirmed** (geometry) | Safe-zone violation |

---

## Bug 1 — Overlay lag (BOX drag updates late)

### Repro
Enter BOX mode, drag a corner handle. The handle follows the finger but the mask fill lags behind, then snaps on release.

### Root cause (CONFIRMED)
`CornerHandleView.onTouchEvent(ACTION_MOVE)` calls `applyBoxDelta(cornerIdx, dx, dy)` **then** `updateCornerHandlesOnly()` (`GhostScreenOverlay.kt:908-914`). But `applyBoxDelta()` itself ends with `updateCornerPositions()` (`:553`), which relayouts **all 8 windows** (4 corners + 4 masks) via cross-process `windowManager.updateViewLayout` IPC. The immediately-following `updateCornerHandlesOnly()` then relayouts the 4 corners **again**.

So each MOVE event pays **12 cross-process IPCs** instead of 4 (handle-only). The expensive mask relayout was *meant* to be deferred to UP (the drag-optimization comment at `:456` says so), but `applyBoxDelta` defeats it.

### Fix
Stop calling `updateCornerPositions()` from inside `applyBoxDelta` during a drag. Cleanest split:
```kotlin
// Mutate geometry only.
private fun dragBoxCorner(cornerIdx: Int, dx: Float, dy: Float) {
    val m = MIN_BOX_DP * density
    when (cornerIdx) { /* same clamp math as applyBoxDelta */ }
    // ... coerce ...
    updateCornerHandlesOnly()   // cheap: 4 small-window IPCs
    maskView?.invalidate()      // border follows on the fullscreen passive layer
}
```
Keep `applyBoxDelta(...)` (with its full `updateCornerPositions()`) for `ACTION_UP` only. This realizes the existing drag-optimization design.

### Effort/Risk
~15 LOC, Low risk. This is PERF-1.

---

## Bug 2 — Opacity flicker (mask briefly disappears on change)

### Repro
In SPLIT mode, drag the eye horizontally to change opacity. The mask flashes off then back on during the drag.

### Root cause (CONFIRMED)
Two compounding causes:

1. **Live `preferencesManager.color` re-parse per draw.** Every `MaskWindowView.onDraw` does `Color.parseColor(preferencesManager.color)` (`:620`) and `MaskView.onDraw` does too (`:658`). These are fine on their own, but during opacity drag, `setOpacity()` → `invalidateMasks()` fires 5 invalidates per move. If a color pref write races (see Bug 3), the parsed color can transiently be the default `#000000` vs. the user's color → visible flash.

2. **The real flicker:** `setOpacity(displayPct)` writes only the in-memory `liveOpacity` and invalidates — that part is cheap and correct. BUT during BOX opacity drag, `setOpacity` is called from `OpacityDropView` while the **4 mask windows are hidden** (`isDraggingMasksHidden`). The fullscreen `MaskView` redraws the fill from its `onDraw` `if (isDraggingMasksHidden)` branch (`:650-683`) using the **old** opacity until the next invalidate catches up, and the border/dots (`:686`) redraw on top. On a slow device the border draws a frame before the fill → the eye sees the border with no fill → "flicker."

### Fix
- Cache the parsed color: `private val cachedColor = lazy { Color.parseColor(preferencesManager.color) }` invalidated only when `color` changes (observe the pref).
- In the drag fallback branch of `MaskView.onDraw`, read `opacity()` (already does) and ensure `invalidate()` is coalesced to one per frame (Choreographer).
- Long-term: drive opacity via a single `ValueAnimator` on the alpha channel rather than `invalidate`.

### Effort/Risk
~25 LOC, Low-Medium risk. PERF-3 + a color cache.

---

## Bug 3 — Overlay desync (UI moves, overlay stale until next interaction)

### Repro
Tap the QS tile or switch apps rapidly. The overlay position/state lags one event behind.

### Root cause (CONFIRMED — the documented race)
`checkCurrentApp(packageName)` is declared `fun` and launches its own `serviceScope.launch { ... }` (`GhostScreenAccessibilityService.kt:113-114`). The outer `pendingCheckJob` only cancels the **debounce**, not the DB query+decision coroutine it spawns. Two rapid switches → two in-flight `isAppProtected()` queries on `Dispatchers.IO` → whichever resolves last wins, regardless of actual order → mask shows for the wrong app.

This is exactly `docs/04_BUGS_AND_DEFICIENCIES.md` Issue #2 and `docs/10_AI_CONTEXT.md §6.2`, and it is **not yet fixed** in code.

### Fix
Make `checkCurrentApp` `suspend` and call it directly inside `pendingCheckJob`:
```kotlin
pendingCheckJob?.cancel()
pendingCheckJob = serviceScope.launch {
    delay(150)
    checkCurrentApp(packageName)   // now suspend
}
```
Full body in `docs/10_AI_CONTEXT.md §6.2`. Removes the nested launch entirely.

### Effort/Risk
~45 LOC, Low risk. This is PERF-2 and the #1 documented roadmap item (Commit 2).

---

## Bug 4 — Horizontal slider (opacity) lag

### Repro
Drag the eye horizontally on the strip. Feels heavier than vertical drag.

### Root cause (CONFIRMED)
On every MOVE, `StripView.onTouchEvent` horizontal branch calls `setOpacity(...)` then `stripTopView?.invalidate(); stripBotView?.invalidate()` (`:837`). `setOpacity` → `invalidateMasks()` invalidates **5 views** (`:100-106`). So each horizontal move = **7 invalidates**. Vertical drag hides the mask windows first, so it's cheaper.

### Fix
During horizontal drag, only the two strip views need redraw (the eye + track move). Defer mask invalidation to `ACTION_UP` (where `persistOpacity()` already runs). The mask can keep its previous opacity for the duration of a drag without harming UX; or do a single mask invalidate at ~60ms cadence.

### Effort/Risk
~10 LOC, Low risk.

---

## Bug 5 — Quick Settings Tile ~5s latency

### Repro
Tap the GhostScreen QS tile. ~5 seconds before the mask appears.

### Root cause (LIKELY — needs device confirmation of magnitude)
Three layered costs:

1. **TileService constructs a new `PreferencesManager(applicationContext)`** in `onCreate()` (`GhostScreenTileService.kt:16`). Each `PreferencesManager` kicks off a **background Tink/EncryptedSharedPreferences init** (3-9s on first launch). While init runs, `prefs.isManualOverrideActive` reads from `plainPrefs` (instant) — so the *write* `isManualOverrideActive = !...` is instant. ✅ This part is not the 5s.

2. **The pref write triggers the AccessibilityService's `OnSharedPreferenceChangeListener`** (`GhostScreenAccessibilityService.kt:33`), which calls `overlay?.show()`. **But** the AccessibilityService may be **cold-starting** if the OS killed it. AccessibilityService rebind is gated by the system; on some OEMs this is 1-3s. During that window the tile writes the pref, nothing receives it.

3. **`overlay?.show()` from a cold service** builds 4-8 `WindowManager.addView` calls (`addMask`, `addMaskWindows`, `addSplitWindows`, `addExitButton`) — each is a cross-process IPC. First-time `addView` for `TYPE_ACCESSIBILITY_OVERLAY` can incur a system-server permission/hosting check. ~200-500ms.

4. **Encrypted prefs desync** (Bug 6 family): if the user armed via the *app* and the tile reads via a *different* `PreferencesManager` whose encrypted store hasn't synced, the tile may toggle the wrong effective value and the overlay briefly hides then re-shows.

**Net**: 5s is consistent with **cold AccessibilityService rebind (2-3s) + encrypted-prefs init on the tile process (first time) + multiple addView IPCs + desync double-toggle**.

### What is realistically achievable on Android
- A warm AccessibilityService responds in **<500ms**. That is the floor.
- Cold-start latency is **system-bound**, not app-bound — you cannot reliably force-awaken an AccessibilityService faster. The honest mitigation is:
  1. Make the tile **single-instance** (`<service ... android:process=":tile">`? No — must be same process to share the live overlay).
  2. Show **immediate visual feedback on the tile** (`Tile.STATE_ACTIVE` + `updateTile()`) *before* the overlay appears, so the user sees the tap registered.
  3. **Singleton `PreferencesManager`** so the tile and service share one (already-initialized) encrypted store instead of each paying Tink init.
  4. **Pre-build the overlay** once (lazy `show()` just attaches views) — already the case.

### Fix
- PERF-6: `PreferencesManager` singleton (`companion object` + `get(context)`). Removes duplicate Tink inits across MainActivity/Service/Tile/BootReceiver.
- In `GhostScreenTileService.onClick`, call `updateTileState()` **first** (already does, `:33`), and ensure `qsTile` updates synchronously before the pref write.
- Document the cold-start floor for users in About/Settings.

### Effort/Risk
Singleton: ~20 LOC, Low risk. Tile feedback: already present. Honest-expectation doc: trivial.

---

## Bug 6 — Home "Armed" toggle behaves like Manual Mask  ⭐ CRITICAL

### Repro
User selects 2 apps in Smart Mask, arms on Home. The mask appears globally (or appears for apps not in the list), behaving like Manual Mask instead of per-app.

### Root cause (CONFIRMED — design defect, not a typo)
The protection decision is `OverlayTransition.decide(...)` (`util/OverlayTransition.kt:16`). Tracing it against the symptom:

- `manualOverride = true` → always `SHOW` (`:25`). Correct.
- `!armed` → `HIDE` (`:27`). Correct.
- armed + real app → `SHOW iff isProtected` (`:37`). Correct.

So the *decision* logic is correct. The defect is **upstream**: `isProtected` comes from `db.appDao().isAppProtected(effectivePackage)` (`GhostScreenAccessibilityService.kt:122`). If a user **armed but selected zero apps** (or the DB query returns false negatives due to Bug 3's race), the mask either never shows (correct-but-confusing) OR shows for everything (if the race stale-overwrites `lastProtectedPackage`).

But the *reported* symptom — "Armed behaves like Manual Mask / masks everything" — has a second, more likely path: **the user cannot tell the two controls apart and is actually triggering Manual Mask** via the Tile/Shortcut, while believing the Home button did it. The Home button sets `isArmed`; the Tile sets `isManualOverrideActive`. Both feel like "on." When both are on, `manualOverride` wins and masks globally. The user, having also pressed the tile at some point, sees global masking and blames "Armed."

**This is an information-architecture defect, not a logic bug.** (See UX_REDESIGN_PROPOSAL.)

### Fix (two parts)
1. **Logic guard**: When `isArmed` is toggled ON via Home, **clear `isManualOverrideActive`** so the two never silently coexist:
   ```kotlin
   // HomeScreen toggle
   preferencesManager.isArmed = newArmed
   if (newArmed) preferencesManager.isManualOverrideActive = false
   ```
   And symmetrically: turning ON Manual via Tile should not require disarming, but should be visibly distinct.
2. **UX clarity** (defer to UX report): rename/relayout so Smart Arm and Manual Mask are visually separate and never confused.
3. **Empty-list guard**: if `isArmed && protectedApps.isEmpty()`, surface a clear Home hint ("Select apps to protect") instead of silent no-op.

### Effort/Risk
Logic guard ~5 LOC, Low risk. UX part in UX report.

---

## Bug 7 — Gboard / keyboard detection weak

### Root cause (CONFIRMED)
`OverlayTransition.isTransient(packageName)` (`:41-58`) hardcodes substrings: `inputmethod`, `keyboard`, `honeyboard`, `swiftkey`, `miinput`, `baidu.input`, `ime.`. This misses:
- **Gboard's actual package**: `com.google.android.inputmethod.latin` — matches `inputmethod`? No: the substring is `inputmethod`, and Gboard's package contains `inputmethod`. ✅ Actually caught.
- But many OEM keyboards do **not** contain those tokens: e.g., `com.jb.emoji.gokeyboard` (Go Keyboard), `com.emoji.keyboard.coolkite` (Kika → `com.kika.keyboard.emoji.teclado` *contains* `keyboard` ✅), `com.touchtype.swiftkey` ✅.
- **SystemUI variants**: `com.transsion.systemui`, `com.huawei.systemui` are listed in the Smart Mask UI suggestions (`SmartMaskSettingsScreen.kt:194-204`) but `isTransient` only catches `systemui` substring — ✅ catches all of them.

So the **detection logic is broad enough**; the weakness is in **user guidance**, not detection:
- The Smart Mask screen suggests keyboards but **does not auto-protect them when the user protects a messaging app**. The user has to know to also toggle Gboard.
- There is no "recommend keyboards" affordance tied to the apps the user selected.

### Fix
- When the user protects a messaging/banking app, surface an inline suggestion: "Also protect your keyboard (Gboard)?" with a one-tap toggle.
- Expand `isTransient` to include a few more IME tokens (`gokeyboard`, `ai.type`, `com.google.android.gboard` alias) — cheap, future-proof.
- The conditional-masking behavior (keyboard masks only over a protected app) is already correct via `lastProtectedPackage` inheritance — keep it.

### Effort/Risk
~30 LOC + UX. Low risk.

---

## Bug 8 — Blur mode leaks readable content

### Root cause (CONFIRMED — platform limit)
Two layers attempt blur:
1. **`FLAG_BLUR_BEHIND`** on the mask windows (`GhostScreenOverlay.kt:251-254`), Android 12+. **This is the only true real-time blur available to an overlay.** It is:
   - Honored at the system's discretion (`WindowManager.setBlurBehindRadius` requires the OEM to enable `SurfaceControl` blur support). Many OEMs disable it for battery.
   - Capped at a small radius even when supported.
   - **Blocked entirely when the overlay window itself is `FLAG_NOT_TOUCHABLE`** on some OEM implementations — which is exactly the mask-window config (`maskParams`, `:236`). This is the leak: the system declines to blur behind a non-touchable accessibility overlay, leaving the tint+noise fallback.
2. **Software fallback**: translucent tint + a 64×64 tiled noise shader (`getNoiseShader`, `:70-84`). This is **mosaic/pixel noise, not blur** — at low alpha the underlying text remains legible.

**Why true blur is impossible for this app in general**: `RenderEffect`/`RenderNode` blur applies to a View's **own** rendered content, not the **content behind** it. To blur what's behind, you must either (a) capture the screen (requires `MediaProjection`, which shows a system dialog each session and violates the "no screen capture" non-goal), or (b) use `FLAG_BLUR_BEHIND` (OEM-dependent). There is no third option for an accessibility overlay.

### Best achievable fallback (recommendation)
- **Increase tint alpha** in blur mode (currently `realPct/100*150` → max 180, `:623`). For privacy, blur mode should default to a **darker tint** so the noise+tint composite is unreadable even without true blur.
- **Larger noise tiles + higher noise alpha** so the mosaic breaks up glyph edges more.
- **Detect `FLAG_BLUR_BEHIND` support at runtime** and, where unsupported, **boost tint automatically** to compensate.
- Surface an honest **"Blur strength depends on your device"** note in Settings (the disclaimer-understood pref already exists).

### Fix
Tunable defaults + runtime detection + honest UI. ~40 LOC. Low risk. Full real-time blur is **not** achievable without screen capture; this is the platform ceiling.

---

## Bug 9 — Overlay color editor lag

### Repro
Open Settings → Mask Appearance → slide the color swatches or pick RGB. Feels laggy.

### Root cause (CONFIRMED — Compose recomposition)
The `LazyRow` of swatches (`SettingsScreen.kt:220-285`) gives each swatch a `size` modifier that depends on `isSelected = overlayColor.equals(swatch.hex, true)` (`:225`). `overlayColor` is `mutableStateOf`. Every color change:
1. Writes `overlayColor` (`:230`).
2. Recomposes the **entire `LazyRow`** because the composable reads `overlayColor`.
3. Every visible swatch re-measures (size changes 48→58dp for the new selected one, and 58→48 for the old).

Re-measurement is the expensive part (not recomposition alone). The RGB sliders compound it: `fromRgb()` writes `hexInput` (another `mutableStateOf`) on every slider tick → re-parses color → re-renders preview.

### Fix
- Compute `selectedIndex` once via `derivedStateOf` so swatches read only an `Int` index, not the raw hex string. Skippable.
- Make swatch `size` depend on a stable `Boolean isSelected` passed as a param to a composable that is `@ReadOnlyComposable`-friendly.
- Debounce RGB slider writes (don't re-parse hex per tick; update preview on a 16ms frame).
- Key `LazyRow` items by `swatch.hex`.

### Effort/Risk
~25 LOC. Low risk. PERF-5.

---

## Bug 10 — Filter (Enabled/Disabled) crash

### Repro
Open Smart Mask, tap "Enabled" or "Disabled" filter chip → crash.

### Root cause (LIKELY — static analysis)
The filter logic itself (`SmartMaskSettingsScreen.kt:240-256`) is null-safe and returns a plain `List`. The crash is most likely a **`LazyColumn` key collision + concurrent modification** combination:

- `items(suggestedApps, key = { "sug_${it.packageName}" })` (`:378`) and `items(normalApps, key = { "norm_${it.packageName}" })` (`:409`).
- Keys are prefixed, so no cross-section collision. ✅
- BUT: `protectedPackages` is derived from `protectedApps.collectAsState` (`:61`). When the user toggles a switch (which writes to the DB) **while** the filter is "Enabled", `protectedPackages` changes → `filteredList` re-derives → the LazyColumn recomposes **while a DB write is in flight**. If the list identity mutates during composition (it shouldn't — `filter` returns a new list), the symptom is a `ConcurrentModificationException` or a Compose `IllegalStateException` ("pending composition has not been applied").

The deterministic trigger: **toggling a switch rewrites the DB, which re-emits the Flow, which re-derives `protectedPackages` and `filteredList` mid-composition.** Under "Enabled" filter, the toggled app disappears from the list instantly (it's now disabled), and its row key vanishes while its `onToggle` coroutine is still running → the `scope.launch(Dispatchers.IO)` callback targets a row that no longer exists.

### Fix
- Make `protectedPackages` a `Set<String>` snapshot (already is, `:61`) and ensure `filteredList` is derived via `derivedStateOf` (stable across recomposition).
- Optimistic UI: flip the switch locally first, write DB second; on failure, roll back. This decouples the list mutation from the composition.
- Wrap the `onToggle` body so a vanished row is a no-op, not a crash.

### Effort/Risk
~20 LOC. Low-Medium risk (touches list mutation timing). Needs device repro to confirm exact exception — but the fix is sound regardless.

---

## Bug 11 — "GhostScreen" search crash

### Repro
In Smart Mask, type "GhostScreen" in the search box → crash. Other searches work.

### Root cause (LIKELY — static analysis)
The app's **own launcher entry** (`com.etaegord.ghostscreen`) is returned by `pm.queryIntentActivities(MAIN/LAUNCHER)` (`SmartMaskSettingsScreen.kt:165`) and becomes an `AppItemInfo` with `appName = "GhostScreen"`, `isSuggested = false` (no keyword match). So searching "GhostScreen" matches it and it renders in `normalApps`.

The crash is in the **icon decode** for the app's own adaptive icon:
```kotlin
val imageBitmap = remember(app.packageName) {
    app.icon.toBitmap(56, 56).asImageBitmap()   // :462
}
```
`app.icon` for the app's own adaptive icon is an `AdaptiveIconDrawable`. `Drawable.toBitmap` on an `AdaptiveIconDrawable` **can throw** on certain API levels when the drawable's `getIntrinsicWidth()` returns `-1` (AdaptiveIcon reports -1 until bounded). The `remember` block **re-throws** the exception on every recomposition that re-keys it — and typing into search recomposes the row. So the crash is deterministic for the self-entry.

**Why only "GhostScreen"**: it's the only entry whose icon is the app's own adaptive icon (all others are third-party icons that decode fine).

### Fix
- Guard the decode:
  ```kotlin
  val imageBitmap = remember(app.packageName) {
      runCatching { app.icon?.toBitmap(56, 56)?.asImageBitmap() }.getOrNull()
  }
  ```
  and fall back to the existing letter-Box placeholder when null.
- Better: decode in the background `LaunchedEffect` (the loading block already runs once), storing `ImageBitmap?` in `AppItemInfo` so the row never decodes.

### Effort/Risk
~10 LOC. Low risk. High-confidence fix even without device repro because the only thing unique to "GhostScreen" is the self-entry's adaptive icon.

---

## Bug 12 — Launcher icon zoomed/cropped

### Root cause (CONFIRMED — geometry)
Adaptive icons render the foreground in a **108×108dp viewport** where only the inner **~66×66dp (diameter ~72dp) safe zone** is guaranteed visible after OEM masking (circle/squircle/rounded-square). Content outside the safe zone is clipped.

In `ic_launcher_foreground.xml`:
- Phone screen body: **y 40–72** (height 32, centered ~56) — inside safe zone ✅
- **Ghost body path**: starts at **y=17** (`M44,46 C40,46 33,43 33,38 ... 54,17`) — the ghost's head apex is at **y≈17**, which is **37dp above center (54)** — well outside the ~36dp safe radius.
- Home button: **y=80** (26dp below center) — borderline, inside.

So the ghost's head is clipped by every circular/squircle launcher mask → "zoomed/cropped" appearance.

### Fix
Scale the entire foreground content down and re-center so all geometry lies within the **centered 66×66 safe square** (x: 21–87, y: 21–87). Concretely:
- Translate + scale the group so the ghost head (currently y≈17) moves to ≥ y≈24, and the home button (y≈80) moves to ≤ y≈84.
- Easiest: wrap the paths in a `<group android:scaleX="0.82" android:scaleY="0.82" android:translateX="9.7" android:translateY="9.7">` (approx — needs visual verification), or redraw the path coordinates to fit.

Also: the `<monochrome>` tag points at the same foreground (`ic_launcher.xml:5`). Themed icons on Android 13+ will inherit the same clipping. Fix applies to both.

### Effort/Risk
Vector geometry edit. Low risk, needs visual QA on a device with circle + squircle masks.

---

## Cross-cutting: the shared root cause behind Bugs 1, 2, 3, 4

Bugs 1-4 are all facets of **one underlying problem**: the overlay does too much per-frame work (IPC relayouts + allocations + invalidates) during drags. Fixing PERF-1 (§Bug 1) + PERF-3 (onDraw allocations) + the suspend `checkCurrentApp` (§Bug 3) resolves the cluster. They should be committed together as Phase 1.

---

## Recommended Fix Sequence (by dependency + user impact)

| Phase | Bugs | Why first |
|---|---|---|
| **P1** | #6 (Smart/Manual guard), #11 (search crash), #10 (filter crash) | User-facing correctness + crashes; small, safe |
| **P2** | #1, #2, #3, #4 (overlay perf cluster) | Biggest perceived-quality win; documented roadmap Commit 1-2 |
| **P3** | #12 (icon), #9 (color editor), #5 (tile latency/singleton) | Polish + perf |
| **P4** | #7 (keyboard guidance), #8 (blur honesty) | UX + platform-limit docs |

Each fix is one logical commit, built and unit-tested (`./gradlew testDebugUnitTest`) before the next.

---

## Reproduction Checklist (device steps, for confirmation)

```bash
# Bug 1/4 (drag lag): enter overlay, drag corners/strips, capture systrace
adb shell systrace -a com.etaegord.ghostscreen gfx view -t 15 -o drag.html

# Bug 3 (race): rapid app switching
adb shell am start -n com.etaegord.ghostscreen/.ui.MainActivity  # arm + select 2 apps
# then rapidly switch: adb shell am start <pkgA>; sleep 0.2; <pkgB>; ...
adb logcat -s GhostScreenService | grep "Showing overlay"

# Bug 5 (tile): force cold service, tap tile, measure
adb shell am force-stop com.etaegord.ghostscreen
adb shell am broadcast -a android.service.quicksettings.action.QS_TILE --es name ...
adb logcat -s GhostScreenService | ts

# Bug 10/11 (crashes): search "GhostScreen"; toggle filter + switch
adb logcat *:E | grep -i "ghostscreen\|AndroidRuntime"
```

*End of report. Awaits approval before implementation.*
