# PERFORMANCE_OPTIMIZATION_PLAN.md — GhostScreen

> **Goal**: Smooth 60 FPS (or better) on low-end devices, with minimal memory allocations, low CPU/battery impact, and stable service/WindowManager behavior.
> **Method**: Static analysis of every hot path (overlay `onDraw`, touch handlers, recomposition sites, coroutine graph, WindowManager update calls). Device profiling steps are included as action items because no device is attached in this session.
> **Trust the code**: Several "performance bugs" in older docs are already fixed in code (e.g. Home idle recomposition is already gone). This report distinguishes *real* hotspots from *documented-but-fixed* ones.

---

## 0. Performance Scorecard (current state, evidence-backed)

| Area | State | Evidence |
|---|---|---|
| Overlay drag smoothness (SPLIT) | ⚠️ Good but has a known stall | `setMaskWindowsVisible(false)` on drag start, full relayout on UP (`GhostScreenOverlay.kt:589-605`) |
| Overlay drag smoothness (BOX) | ⚠️ Has corner-drag optimization | `updateCornerHandlesOnly()` during drag (`:457`), full `updateCornerPositions()` on UP |
| Opacity live update | ⚠️ `invalidate()`-based, not a window relayout | `setOpacity()` → `invalidateMasks()` (`:92-106`) — cheap, but see flicker root cause in BUG report |
| Home idle recomposition | ✅ Already fixed | Single `InfiniteTransition` + `graphicsLayer` (`HomeScreen.kt:87-105`); guarded by `if (isArmed)` |
| App-icon bitmap allocation | ✅ Cached | `remember(app.packageName) { icon.toBitmap(56,56) }` (`SmartMaskSettingsScreen.kt:461`) |
| Service debounce | ✅ 150ms | `pendingCheckJob?.cancel()` + `delay(150)` (`GhostScreenAccessibilityService.kt:102-107`) |
| Service coroutine hygiene | ❌ Nested untracked coroutine | `checkCurrentApp` launches its own `serviceScope.launch` (`:114`) — race + extra dispatch |
| Overlay `onDraw` allocations | ⚠️ Mixed | Some `Paint` pre-allocated; but `drawEye`/`MaskView.onDraw` allocate `Path`, `RectF`, `Paint` per frame (`:754`, `:662-674`, `:977`) |
| WindowManager update batching | ❌ Not batched | Every `safeUpdate` is a cross-process IPC; corner-drag pays 4× per move |
| Encrypted prefs init | ⚠️ Async (correct) but desync risk | `CoroutineScope(IO).launch` in init (`PreferencesManager.kt:59`) |
| Search/filter recomposition | ❌ Unoptimized | `remember(appList, searchQuery, filterMode, protectedPackages)` re-filters whole list on each keystroke |

---

## 1. Critical Hot Path #1 — Overlay Dragging (BOX mode)

### 1.1 Current flow (per `ACTION_MOVE`)
```
CornerHandleView.onTouchEvent(MOVE)
  → applyBoxDelta(cornerIdx, dx, dy)     // updates boxLeft/Top/Right/Bottom
    → updateCornerPositions()            // ⚠️ expensive: 4 corner + 4 mask + opacityBtn safeUpdate
  → updateCornerHandlesOnly()            // re-updates the 4 corners (redundant with above!)
  → scheduleEditDim()
```
**Bug**: `applyBoxDelta()` calls `updateCornerPositions()` (full, 8-window relayout) and *then* `updateCornerHandlesOnly()` is called again immediately after (`GhostScreenOverlay.kt:908-914`). The expensive path runs on every move — the "drag optimization" is effectively dead. This is the **root cause of overlay drag lag in BOX mode**.

### 1.2 Fix
- `applyBoxDelta()` must NOT call `updateCornerPositions()` during an active drag. Split into:
  - `applyBoxDelta(cornerIdx, dx, dy, relayout: Boolean)` — or
  - A dedicated `dragBoxCorner()` that only mutates geometry + calls `updateCornerHandlesOnly()`.
- Reserve `updateCornerPositions()` for `ACTION_UP` (already called at `:923`).
- **Expected gain**: ~8× fewer cross-process IPCs per move event (8 → 1).

### 1.3 Further: coalesce move events
`MotionEvent.ACTION_MOVE` can fire at 60–240Hz on high-refresh panels. Coalesce:
```kotlin
private val moveRunnable = Runnable { performBoxDrag() }
// in MOVE: post latest dx/dy to mainHandler, remove + repost (16ms bucket)
```
Or use `Choreographer.postFrameCallback` to align redraws with vsync.

---

## 2. Critical Hot Path #2 — Overlay Dragging (SPLIT mode)

### 2.1 Current flow
```
StripView.onTouchEvent(MOVE)
  → setOpacity(...) or strip position update
  → maskView?.invalidate()
  → safeUpdate(this@StripView)         // relayouts the strip window
```
Vertical drag hides mask windows (`setMaskWindowsVisible(false)` at `:813`) — good. But **horizontal (opacity) drag** does NOT hide them, and calls `setOpacity` → `invalidateMasks()` (5 invalidates) **plus** `stripTopView.invalidate(); stripBotView.invalidate()` (`:837`) per move. On a long drag this is fine for `invalidate()` (cheap, no IPC) but the **strip window itself** is relayouted via `safeUpdate` only on vertical drag — horizontal drag is OK here.

### 2.2 Fix
- During horizontal opacity drag, only `stripTopView` + `stripBotView` need redraw (the eye position changes). `invalidateMasks()` (5 invalidates) is **unnecessary during opacity drag** — the mask alpha changes, yes, but it can be deferred to UP unless live preview is desired. Decision: keep live preview but reduce to a single `maskView?.invalidate()` + let `MaskWindowView` read opacity directly (it already does via `opacity()`).

---

## 3. Hot Path #3 — `onDraw` Allocations

Every `onDraw` that allocates objects triggers GC pressure on low-end devices. Found allocators:

| File:Line | Allocation | Fix |
|---|---|---|
| `GhostScreenOverlay.kt:662-674` (`MaskView.onDraw`) | `List<RectF>` + `RectF` instances per frame | Pre-allocate 4 `RectF` fields, mutate in place |
| `GhostScreenOverlay.kt:754` (`StripView.drawEye`) | `Paint(...)` + `Path()` per draw | Pre-allocate; reuse `Path.reset()` |
| `GhostScreenOverlay.kt:777-782` | `Path().apply{...}` (eye shape) per draw | Pre-allocate `eyePath`, `reset()` + rebuild, or cache the path (it only depends on `cx, cy, r`) |
| `GhostScreenOverlay.kt:792` | `Paint(arrow_).apply{...}` per draw | Pre-allocate `chP` |
| `GhostScreenOverlay.kt:977` (`OpacityDropView.onDraw`) | `Paint(...).apply{...}` (highlight) per draw | Pre-allocate |
| `GhostScreenOverlay.kt:983-988` | `Path().apply{...}` (drop shape) per draw | Pre-allocate; shape is constant, cache it |
| `getNoiseShader()` (`:70-84`) | `Bitmap.createBitmap(64,64)` once — OK (cached) | ✅ already cached |

**Expected gain**: Eliminates per-frame allocations in the hottest draw paths → no GC stalls during long drags.

---

## 4. WindowManager Update Frequency

Every `windowManager.updateViewLayout(view, params)` is a **cross-process IPC** to the system server. Current update sites:

| Site | Frequency | Notes |
|---|---|---|
| `updateStripLayouts()` | per vertical move (SPLIT) | 4 calls (2 strips + 2 masks) per move |
| `updateCornerPositions()` | per move (BOX) — **bug, should be UP-only** | 8 calls per move |
| `updateCornerHandlesOnly()` | per move (BOX) | 4 calls per move |
| `updateExitPos()` | per exit-button drag | OK (single window) |

**Fix**: During drags, update **only handle windows** (cheap, small windows). Defer mask-window relayout to UP. Already the intent for BOX — broken by the `applyBoxDelta` bug (§1.1). Apply the same discipline to SPLIT vertical drag: only update strip windows during move, masks on UP.

---

## 5. Recomposition Audit (Compose)

### 5.1 HomeScreen
- ✅ Pulse uses `InfiniteTransition` + `graphicsLayer` — does not recompose, only re-draws GPU layer.
- ⚠️ `db.appDao().getAllProtectedApps().collectAsState(initial = emptyList())` + `getAllAuditLogs()` (`:77-78`) — two DB flows collected; each emits on every write. Fine, but the count is derived via `.size` on the collected list — wrap in `derivedStateOf` to avoid recomputing the chip text on unrelated recompositions.
- ⚠️ `StatusWarningBanner` and `StatCard` are top-level composables but not skipped-eligible unless their params are stable. Pass `Boolean`/`String` (stable) — OK.

### 5.2 SmartMaskSettingsScreen
- ❌ **`filteredList` re-filters the entire app list on every keystroke** (`:240`). With ~100+ installed apps this is jank on typing.
- ❌ **`partition` runs inside `remember(filteredList)`** (`:259`) — re-runs on every filter/text change. OK-ish but combined with above = O(n) per keystroke × 2.
- ⚠️ `AppRow`'s `remember(app.packageName) { icon.toBitmap(56,56) }` is good, but the bitmap survives only while the row is composed. Scrolling a long list re-allocates on scroll-back. Consider a `ViewModel`-level `iconCache: Map<String, ImageBitmap>` (roadmap Phase 4, Commit 8).

**Fix**:
```kotlin
val filteredList by remember(appList, filterMode) {
    derivedStateOf { applyFilter(appList, filterMode, protectedPackages) }
}
// search applied as a second derivedStateOf on (filteredList, searchQuery)
```
Plus a debounced search (120ms) so fast typing doesn't re-filter per char.

### 5.3 SettingsScreen
- ❌ **Color editor lag** (reported bug). `LazyRow` of swatches: each swatch's `isSelected` depends on `overlayColor` (`mutableStateOf`). Picking a color writes `overlayColor`, recomposing **the entire row** (all swatches re-measure because their `size` modifier depends on `isSelected`). See BUG report for full fix.

### 5.4 AuditLogScreen
- ⚠️ `DateFormat.format` inside `remember(log.timestamp)` — OK per-row. But `LazyColumn` `items` lack `key` for some... actually it has `key = { it.id }` (`:130`) — good.

---

## 6. Coroutine & Concurrency

### 6.1 The race (documented, unfixed)
`checkCurrentApp()` is `fun` launching its own `serviceScope.launch` (`GhostScreenAccessibilityService.kt:113-114`). The outer `pendingCheckJob` debounces the *trigger* but not the *DB query + decision*. Rapid app switches → overlapping queries → out-of-order decisions → mask flicker/desync.

**Fix** (from `docs/10_AI_CONTEXT.md §6.2`, already specced): make `checkCurrentApp` `suspend`, call it inside `pendingCheckJob`. Estimated 45 LOC. This also removes one coroutine launch per event.

### 6.2 Audit-log write on main?
`GhostScreenAccessibilityService.kt:151` — `serviceScope.launch(Dispatchers.IO)` for the audit insert — correct. ✅

### 6.3 Prefs init scope leak risk
`PreferencesManager.kt:59` uses `CoroutineScope(Dispatchers.IO + SupervisorJob()).launch` — this scope is **not cancelled** when the manager is GC'd (it's a fire-and-forget). Low risk (one-shot, completes in seconds) but pattern is flagged in `AGENTS.md` ("No GlobalScope"). Acceptable here but should be tied to a managed scope long-term.

### 6.4 Hide job
`hideJob = serviceScope.launch { delay(350); ... }` (`:167`) — correct, cancelled properly on new SHOW/STAY.

---

## 7. Startup Latency

| Step | Cost | Blocking? |
|---|---|---|
| `AppDatabase.getDatabase()` | Room init (~50-200ms first time) | No (lazy) |
| `PreferencesManager` plain prefs | <10ms | No |
| `PreferencesManager` encrypted prefs (Tink) | **3-9s** first launch | No (async on IO) |
| `computeStartDestination()` | <5ms (Settings.canDrawOverlays + accessibility scan) | Yes (main thread, but fast) |
| Compose first frame | ~100-300ms | Yes (inevitable) |

**Bottleneck**: Tink encrypted-prefs init (3-9s). It is already off the main thread (correct). The *user-visible* cost is the **prefs desync window** (see BUG report), not a frame drop.

**Tile/Shortcut latency** — see BUG report §"Quick Settings Tile". Root cause is likely Tink init creating a new `PreferencesManager` on the Tile side that blocks on first prefs read.

---

## 8. Overlay Redraw Inefficiencies (beyond drag)

- **`MaskView` vs `MaskWindowView` overlap**: In BOX mode, `MaskView` (fullscreen, passive) draws the border + corner dots, while 4 `MaskWindowView`s draw the actual mask fill. During idle (not dragging), `MaskView.onDraw`'s `if (isDraggingMasksHidden)` block is skipped — only border/dots drawn. ✅ correct.
- **`invalidateMasks()` invalidates all 5 mask views** (`:100-106`) even when only one changed. Minor; invalidation is cheap, but on opacity change all 5 redraw. Acceptable.
- **Noise shader**: created once, cached (`getNoiseShader()`). ✅ But `noiseP.shader = getNoiseShader()` is set every `onDraw` in blur mode (`:627`, `:679`) — cheap assignment, but could be set once in init.

---

## 9. Memory & GC

| Source | Risk | Fix |
|---|---|---|
| Handler runnables in `StripView`/`OpacityDropView`/`CornerHandleView` | Leak if not cleared on detach (documented bug) | `onDetachedFromWindow()` cleanup (`docs/10_AI_CONTEXT.md §6.3`) |
| Per-`onDraw` allocations (§3) | GC stalls during long drags | Pre-allocate |
| Icon bitmaps in SmartMask | Survive only while composed | ViewModel cache |
| `noiseShader` bitmap | One 64×64 bitmap, cached | ✅ fine |
| Audit log unbounded growth | DB grows forever | Retention policy (Settings) |

---

## 10. Battery & CPU

- ✅ Service reacts to `TYPE_WINDOW_STATE_CHANGED` only — no polling.
- ✅ Overlay is GPU-composited; idle overlay draws ~6 small windows.
- ⚠️ Pulse animation runs at 2000ms cadence whenever armed — acceptable, but could pause when app is backgrounded (it's in Compose, which pauses with lifecycle — ✅).
- ⚠️ Tink init runs on **every** `PreferencesManager` construction — and there are **multiple** constructions (MainActivity, Service, TileService, BootReceiver each `new` one). Each pays the Tink cost (cached by AndroidKeystore after first, but still). Consider a **singleton** (`PreferencesManager.instance(context)`).

---

## 11. Device Profiling Action Items (when a device is attached)

```bash
# 1. Frame timing during BOX corner drag
adb shell systrace -a com.etaegord.ghostscreen gfx -t 10 -o trace.html
# Look for: frames > 16ms (yellow/red), "updateViewLayout" IPC stalls

# 2. Allocation tracking during long opacity drag
# Android Studio Profiler > Memory > Record allocations during a 10s drag

# 3. Recomposition counts
# Layout Inspector (Compose) > enable "Recomposition counts", drag slider

# 4. Service cold-start → first overlay
adb shell am force-stop com.etaegord.ghostscreen
adb shell am start -n com.etaegord.ghostscreen/.ui.MainActivity
adb logcat -s GhostScreenService PreferencesManager -T 100 | ts

# 5. Tile tap latency
adb shell dumpsys activity service com.etaegord.ghostscreen/.service.GhostScreenTileService
# Tap tile, measure wall-clock to "Showing overlay for manual"
```

---

## 12. Prioritized Optimization Backlog

| ID | Item | Impact | Effort | Risk |
|---|---|---|---|---|
| PERF-1 | Fix `applyBoxDelta` double-relayout (BOX drag) | **High** | S | L |
| PERF-2 | Make `checkCurrentApp` suspend (race fix) | **High** | M | L |
| PERF-3 | Pre-allocate `onDraw` Paints/Paths/RectFs | **High** | M | L |
| PERF-4 | Debounce + `derivedStateOf` for app search/filter | Med | S | L |
| PERF-5 | Fix color-editor `LazyRow` recomposition | Med | S | L |
| PERF-6 | PreferencesManager singleton (cut Tink re-inits) | Med | S | L |
| PERF-7 | Coalesce MOVE events (Choreographer) | Med | M | M |
| PERF-8 | Handler cleanup on detach (leak) | Med | S | L |
| PERF-9 | Audit-log retention policy | Low | S | L |
| PERF-10 | Icon bitmap cache in ViewModel | Low | M | L |

**Sequence**: PERF-1, PERF-2, PERF-3 first (biggest user-visible wins on low-end devices). Then PERF-4/5 (reported bugs). Then the rest.

---

## 13. Realistic FPS Expectations

- **Compose UI (in-app)**: 60 FPS achievable on mid-range; 90/120 on high-refresh (Compose auto-adapts).
- **Overlay drag**: 60 FPS achievable **after** PERF-1 + PERF-3. Currently may drop to 30-45 FPS on low-end during BOX drag due to the double-relayout bug.
- **WindowManager IPC**: hard floor ~16ms per `updateViewLayout` under load. This is why batching (PERF-7) matters at high refresh rates.
- **System blur (`FLAG_BLUR_BEHIND`)**: hardware-accelerated on Android 12+, ~free. The software noise-shader fallback is cheap (one 64×64 tiled bitmap).

*End of report. Awaits approval before implementation.*
