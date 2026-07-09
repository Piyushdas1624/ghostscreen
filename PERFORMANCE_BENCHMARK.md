# PERFORMANCE_BENCHMARK.md — GhostScreen

> **Purpose**: Every performance change must be **measured before and after**. Numbers live here. A milestone is not "done" while its row still says `pending`.
> **Methodology**: Each row names the exact measurement procedure so a contributor can reproduce it. No number is ever invented.
> **Convention**: `pending` = not yet measured on a real device/emulator. Replace `pending` with the measured value + the device/API/test-date when recorded.

---

## 0. Test Environment (fill in at first measurement)

| Field | Value |
|---|---|
| Device model | `pending` |
| Android API level | `pending` |
| Refresh rate (Hz) | `pending` |
| Build type | `debug` |
| Measurement tool | `pending` (systrace / Perfetto / Studio Profiler / Layout Inspector) |
| Date | `pending` |

> Until this table is filled, all numeric cells below remain `pending`.

---

## 1. Headline Metrics (target summary)

| Operation | Before | After | Milestone | Status |
|---|---:|---:|---|---|
| Box drag FPS | `pending` | `pending` | M3 | pending |
| Overlay show latency (warm) | `pending` | `pending` | M2/M4 | pending |
| Overlay show latency (cold) | `pending` | `pending` | M4 | pending |
| Tile toggle → overlay (warm) | `pending` | `pending` | M4 | pending |
| Tile toggle → overlay (cold) | `pending` | `pending` | M4 | pending |
| `updateViewLayout` IPCs per box-drag move | `pending` (static count: 13) | `pending` (target: ~5) | M3 | pending |
| `invalidate()` calls per box-drag move | `pending` | `pending` | M3 | pending |
| `updateViewLayout` IPCs per split-drag move | `pending` | `pending` | M8 | pending |
| Allocations / frame (box drag) | `pending` | `pending` | M8 | pending |
| Jank frames (>16ms) during 5s box drag | `pending` | `pending` | M3 | pending |
| Jank frames during 5s opacity drag | `pending` | `pending` | M8 | pending |
| Home idle recompositions (30s) | `pending` (static: expected 0) | `pending` | M5/M8 | pending |
| Smart Mask search: recompositions / keystroke | `pending` | `pending` | M6/M8 | pending |
| Color editor: recompositions / pick | `pending` | `pending` | M8 | pending |
| Cold app start (ms) | `pending` | `pending` | M8 | pending |
| Memory (steady state, MB) | `pending` | `pending` | M8 | pending |
| CPU during overlay idle (%) | `pending` | `pending` | M8 | pending |
| APK size (debug / release-R8) | `pending` | `pending` | M10 | pending |

> Every `After` cell must be paired with its `Before` from the **same device/session** so the comparison is valid.

---

## 2. Measurement Procedures (reproducible recipes)

### 2.1 Box-drag IPC count (M3)
```
Temporary instrumentation in GhostScreenOverlay:
  private var ipcCount = 0; private var invalidateCount = 0
  // bump in safeUpdate() and in each invalidate() path
  // on ACTION_UP: Log.d("PERF", "boxMove ipc=$ipcCount inv=$invalidateCount")
Enter BOX mode, drag one corner for 2 seconds, release.
Read the logged totals, divide by number of MOVE events.
Record per-move averages above.
Remove instrumentation before the fix commit.
```

### 2.2 Frame time / jank (M3, M8)
```
adb shell perfetto -o /sdcard/trace.pb -t 10 sched freq idle am wm view gfx
# or legacy: adb shell systrace -a com.etaegord.ghostscreen gfx view -t 10 -o trace.html
Drag during the 10s capture. Count frames > 16ms in the gfx/view track.
```

### 2.3 Allocations per frame (M8)
```
Android Studio Profiler > Memory > "Record allocations".
Start recording, perform a 10s box drag, stop.
Read "allocations/frame" from the timeline.
```

### 2.4 Tile toggle latency (M4)
```
adb shell am force-stop com.etaegord.ghostscreen     # for cold
# (warm: skip the force-stop)
adb shell am start -n com.etaegord.ghostscreen/.ui.MainActivity  # ensures process up for warm
Tap the QS tile.
adb logcat -s GhostScreenService | measure wall-clock between tap receipt and "Showing overlay for manual"
Take median of 5 trials.
```

### 2.5 Recomposition counts (M5, M6, M8)
```
Layout Inspector (Compose) > enable "Recomposition Counts".
Interact for 30s (idle / type / pick color). Read counts.
```

### 2.6 Cold start (M8)
```
adb shell am force-stop com.etaegord.ghostscreen
adb shell am start -W -n com.etaegord.ghostscreen/.ui.MainActivity
# read "TotalTime" from the -W output
Median of 5 trials.
```

### 2.7 APK size (M10)
```
./gradlew assembleDebug ; ls -la app/build/outputs/apk/debug/*.apk
./gradlew assembleRelease ; ls -la app/build/outputs/apk/release/*.apk
```

---

## 3. Static Analysis (code-level, no device needed)

These are structural facts from reading the code — they motivate the measured metrics above but are **not** substitutes for measurement.

| Claim | Evidence | Verified by |
|---|---|---|
| BOX drag triggers ~13 `updateViewLayout` IPCs per MOVE | `CornerHandleView.onTouchEvent` → `applyBoxDelta`→`updateCornerPositions` (9) + `updateCornerHandlesOnly` (4) | M3 counter (§2.1) |
| `checkCurrentApp` launches an untracked nested coroutine | `GhostScreenAccessibilityService.kt:113-114` | M2 instrumentation |
| `Paint`/`Path`/`RectF` allocated per `onDraw` in several overlay views | `GhostScreenOverlay.kt` (MaskView/StripView/OpacityDropView `onDraw`) | M8 allocation recording |
| Color-editor swatches re-measure on every pick | `SettingsScreen.kt` swatch `size` depends on `isSelected` derived from `overlayColor` | M8 recomposition count |

---

## 4. Regression Policy

- Any later change that regresses a closed (non-`pending`) `After` value by more than **10%** must explain the regression in its PR description and either (a) revert, or (b) update this file with justification.
- The CI workflow (M10) should re-run the JVM-level static checks on every PR. On-device benchmarks run manually before each release.

---

## 5. Change Log

| Date | Milestone | Row(s) updated | Author |
|---|---|---|---|
| _initial_ | — | schema created, all `pending` | Lead Maintainer |

*Append a row here every time a `pending` cell is filled with a real measurement.*
