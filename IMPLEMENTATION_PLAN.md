# IMPLEMENTATION_PLAN.md — GhostScreen

> **Status**: Approved roadmap. Implementation proceeds milestone-by-milestone, one logical commit each, built + tested after every commit.
> **Authority**: This document converts `reports/*.md` into a milestone-driven, evidence-first execution plan. It supersedes the earlier generic bug-list that previously occupied this file (that list referenced already-fixed behavior and stale line numbers).
> **Core principle (governs all milestones)**: *No guessing.*
> - Every crash fix begins with **reproduction + captured stacktrace**, then fix, then regression test.
> - Every performance fix begins with **measured baseline**, then fix, then **measured after**, recorded in `PERFORMANCE_BENCHMARK.md`.
> - A claim without a number or a stacktrace is a hypothesis, not a finding.

---

## 0. Milestone Overview

| # | Milestone | Type | Gate to next |
|---|---|---|---|
| **M0** | Pre-flight: build green, benchmark harness defined, clutter removed | Hygiene | builds + tests pass |
| **M1** | Critical crashes (search, filter) — reproduced with stacktraces | Stability | crashes gone, regression tests added |
| **M2** | Overlay synchronization (the documented race) | Correctness | race test added, flicker gone |
| **M3** | Overlay rendering performance (BOX drag "dead optimization") | Performance | measured FPS/IPC improvement |
| **M4** | Quick Settings responsiveness | Performance | measured tile latency |
| **M5** | Home screen redesign (Smart Protection primary) | UX | Home shipped |
| **M6** | Smart Mask redesign | UX | — |
| **M7** | Audit Log → Privacy Dashboard | UX | dashboard shipped |
| **M8** | Performance optimization (recomposition, allocations) | Performance | benchmark gains recorded |
| **M9** | Accessibility & M3 polish | Polish | a11y sweep done |
| **M10** | Release preparation (signing, R8, F-Droid) | Release | reproducible build |

**Global invariant (never broken by any milestone)**: BOX-mode multi-window touch passthrough · `TYPE_ACCESSIBILITY_OVERLAY` · manual coroutine tickers for overlay animation.

---

## M0 — Pre-flight

**Goal**: Establish a clean, green baseline and a measurement harness before touching behavior.

**User-visible improvements**: None (internal).

**Files expected to change**:
- `.gitignore` (expand), repo-root clutter (`rewrite.kt`, `temp.txt`, `logcat.txt`, `nul`, `detailed_list.txt`, `repo_tree.txt`, `prompt.md`, `run_claude.bat`, `structuredatabase.md`, `mine stuffs/`, stray icons) — remove after `grep` confirms unreferenced.
- `PERFORMANCE_BENCHMARK.md` (new — schema only, all cells `pending`).
- `.agents/rules/colour-rule.md` — delete (wrong-project template).

**Estimated LOC**: +120 / −800 (mostly deletion).

**Risk**: Low (deletions verified unreferenced first).

**Regression probability**: Very low.

**Rollback strategy**: `git revert` the hygiene commit. Clutter is non-functional.

**Validation checklist**:
- [ ] `./gradlew assembleDebug` succeeds with zero new warnings.
- [ ] `./gradlew testDebugUnitTest` — all existing tests pass.
- [ ] `grep -r` confirms no source file references any deleted artifact.
- [ ] `.gitignore` expanded; `git status` clean of regenerated junk.

**Performance metrics**: N/A (no behavior change).

**Acceptance criteria**: Clean working tree after `git status`; green build + green tests.

---

## M1 — Critical crashes (reproduced with stacktraces)

**Goal**: Eliminate the two deterministic crashes the user reported, **each backed by a captured stacktrace before any fix is written**.

**User-visible improvements**:
- Searching "GhostScreen" no longer crashes.
- Tapping Enabled/Disabled filter no longer crashes.

**Reproduction protocol (runs BEFORE coding)**:
```
1. Attach device / start emulator (API 26..35).
2. adb logcat -c
3. Open Smart Mask, type "GhostScreen" → capture full AndroidRuntime stacktrace.
4. Repeat for Enabled/Disabled filter toggle.
5. Paste both stacktraces into BUG_INVESTIGATION_REPORT.md as evidence.
6. Only then: identify exact exception class + exact line + exact trigger condition.
```

**Current hypotheses (NOT findings — confirmed or replaced by the stacktrace)**:
- *Search*: a decode/`remember`-rethrow path triggered specifically by the app's own adaptive launcher icon. **Line under investigation**: `SmartMaskSettingsScreen.kt:462`. **Why only "GhostScreen"**: only the app's own entry uses the adaptive launcher icon. **To confirm by stacktrace.**
- *Filter*: concurrent list mutation during composition when a toggle write re-emits the protected-apps Flow mid-recomposition under a non-"All" filter. **Line under investigation**: `SmartMaskSettingsScreen.kt:240-261`. **To confirm by stacktrace.**

> ⚠️ These are hypotheses. If the stacktrace says otherwise, the fix follows the stacktrace, not the hypothesis.

**Files expected to change**:
- `app/src/main/java/com/etaegord/ghostscreen/ui/screens/SmartMaskSettingsScreen.kt`
- New: `app/src/test/java/.../AppListFilterTest.kt` (pure-function test of extracted filter logic).

**Estimated LOC**: ~40 production + ~50 test.

**Risk**: Low.

**Regression probability**: Low (guarded by new tests).

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] Both stacktraces captured and attached to the bug report.
- [ ] Exact exception + line identified from the trace.
- [ ] Fix applied at that exact line.
- [ ] Reproduction steps re-run → no crash (10/10 attempts).
- [ ] New JVM test fails on the old code, passes on the new code.

**Performance metrics**: N/A.

**Acceptance criteria**: Both crashes non-reproducible on device; two regression tests added and green.

---

## M2 — Overlay synchronization (documented race)

**Goal**: Fix the overlay desync/flicker caused by the untracked nested coroutine in the accessibility service.

**User-visible improvements**:
- No more mask-shows-for-wrong-app on rapid switching.
- No more mask flicker when pulling the shade over a protected app.

**Evidence-first step**:
```
Before coding, add temporary instrumentation:
  Log.d(TAG, "checkLaunch job=${System.identityHashCode(...)} pkg=$packageName")
Rapid-switch repro (5 switches in 2s), confirm out-of-order resolution in logcat.
Capture the log as evidence. Then remove instrumentation before the fix commit.
```

**Files expected to change**:
- `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt` — convert `checkCurrentApp` to `suspend`, call inside `pendingCheckJob`. Pattern: `docs/10_AI_CONTEXT.md §6.2`.

**Estimated LOC**: ~45.

**Risk**: Low-Medium (touches the service's main event path).

**Regression probability**: Medium (concurrency) — mitigated by a deterministic test of `OverlayTransition` + an instrumentation log diff.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] Pre-fix logcat shows out-of-order resolutions.
- [ ] Post-fix logcat shows strict cancel-before-next-start ordering.
- [ ] Existing `OverlayTransitionTest` still green.
- [ ] Rapid-switch device test (5 switches/2s) → overlay always matches the final foreground app.

**Performance metrics**: One coroutine launch removed per event (recorded in benchmark).

**Acceptance criteria**: Desync non-reproducible in the rapid-switch device test across 20 trials.

---

## M3 — Overlay rendering performance (BOX drag)

**Goal**: Make BOX-mode corner dragging hit a stable 60 FPS by removing the redundant cross-process relayout.

**User-visible improvements**: Smooth, lag-free box resizing — the single most-noticeable win.

**Evidence-first step (measurement before coding)**:
```
Add a temporary counter in GhostScreenOverlay:
  private var ipcCount = 0   // bumped in safeUpdate()
  private var invalidateCount = 0
On ACTION_UP, Log.d("PERF","move ipc=$ipcCount inv=$invalidateCount")
Drag a corner for 2s. Capture the per-move counts.
This is the BEFORE number for PERFORMANCE_BENCHMARK.md.
```

**Precise static count (to be verified by the counter above)**:
Per `ACTION_MOVE` in BOX drag (`CornerHandleView.onTouchEvent`):
- `applyBoxDelta()` → `updateCornerPositions()`: 4 corner + 4 mask + 1 opacity = 9 `safeUpdate` IPCs, plus 9 `invalidate()` calls.
- `updateCornerHandlesOnly()` (called next): 4 more `safeUpdate` + 1 `invalidate`.
- **Total per move ≈ 13 `updateViewLayout` IPCs** (corrected from an earlier "12" — counted precisely: 9 + 4).

**Fix**: `applyBoxDelta` must not call `updateCornerPositions` during a drag. Introduce `dragBoxCorner(cornerIdx, dx, dy)` that mutates geometry + calls only `updateCornerHandlesOnly()` + `maskView?.invalidate()`. `updateCornerPositions()` runs only on `ACTION_UP`.

**Expected AFTER**: ~5 IPCs/move (4 handle updates + 1 passive-layer invalidate). The 4 mask windows relayout **once** on UP.

**Files expected to change**: `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt` (BOX drag path).

**Estimated LOC**: ~25.

**Risk**: Low (localized to drag; UP path unchanged; window *count* unchanged → passthrough invariant intact).

**Regression probability**: Low.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] BEFORE counter recorded in benchmark.
- [ ] AFTER counter recorded in benchmark.
- [ ] Systrace frame-time: BEFORE vs AFTER (target: no frames > 16ms during drag on the test device).
- [ ] BOX passthrough manual test: touches inside the box still reach the underlying app.
- [ ] Border + corner dots remain visible during drag.

**Performance metrics (recorded)**: `updateViewLayout` calls/move, `invalidate()` calls/move, avg frame time (ms), jank %.

**Acceptance criteria**: ≥50% reduction in per-move IPCs AND no frame > 16ms in a 5-second drag on the test device.

---

## M4 — Quick Settings responsiveness

**Goal**: Minimize perceived tile-tap latency; document the irreducible cold-start floor honestly.

**User-visible improvements**: Tile tap feels instant (visual feedback), mask appears as fast as the system allows.

**Evidence-first step**:
```
adb shell am force-stop com.etaegord.ghostscreen
Tap tile. adb logcat -s GhostScreenService | measure wall-clock
  from tap → "Showing overlay for manual".
Record BEFORE latency (multiple trials, cold + warm).
```

**Root cause (to confirm by measurement)**: (a) duplicate `PreferencesManager` constructions each paying Tink init; (b) cold AccessibilityService rebind (system-bound); (c) `addView` IPC burst on first show.

**Fix**: `PreferencesManager` singleton (one Tink init, shared); `qsTile.updateTile()` before pref write (instant feedback); document the cold-start floor in About.

**Files expected to change**:
- `data/PreferencesManager.kt` (singleton), `service/GhostScreenTileService.kt`, callers → `PreferencesManager.get(context)`.

**Estimated LOC**: ~30.

**Risk**: Low-Medium.

**Regression probability**: Low.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] BEFORE/WARM and BEFORE/COLD tile latency recorded.
- [ ] AFTER/WARM and AFTER/COLD recorded.
- [ ] Tile shows active state within 1 frame of tap.
- [ ] Manual mask still toggles correctly from Tile and Shortcut.

**Performance metrics**: cold + warm tile-to-overlay latency (ms).

**Acceptance criteria**: Warm latency < 500 ms. Cold latency measured + documented.

---

## M5 — Home screen redesign

**Goal**: Make **Smart Protection** the primary, unambiguous workflow; remove the duplicate Smart Mask section; surface Manual Mask as a distinct secondary action.

**User-visible improvements**: Users instantly understand "am I protected, and against what?"

**Target layout** (approved):
```
┌─────────────────────────────────────────┐
│ GhostScreen            🟢 Protected      │
├─────────────────────────────────────────┤
│  🛡 Smart Protection                      │
│  Protect only selected apps              │
│  [ ON ]                                   │
│  Protected Apps (12)        Manage →     │
├─────────────────────────────────────────┤
│  🎭 Manual Mask                           │
│  Overlay the screen manually.   Launch → │
├─────────────────────────────────────────┤
│  Recent Activity                          │
│  3 protections today       View Details →│
└─────────────────────────────────────────┘
```
Gone: duplicate Smart Mask card, duplicate Smart Mask stat, ambiguous "ARMED" toggle.

**Files expected to change**:
- `ui/screens/HomeScreen.kt` (major body rewrite).
- New: `HomeViewModel` (extracts DB flows + counts — also a perf win).
- Smart/Manual mutual-exclusion guard (BUG #6) lives here.

**Estimated LOC**: ~+250 / −200.

**Risk**: Medium (largest UI change).

**Regression probability**: Medium — mitigated by screenshot before/after + manual nav test.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] Only one Smart Mask entry point remains.
- [ ] Smart Protection toggle arms/disarms correctly.
- [ ] Toggling Smart clears Manual (mutual-exclusion guard).
- [ ] Status chip reflects: 0 apps / N apps / Manual on.
- [ ] All nav destinations reachable.
- [ ] Manual device test on phone (portrait) + visual QA.

**Performance metrics**: Home recomposition count idle (Layout Inspector) — target: zero while idle.

**Acceptance criteria**: Home shows exactly the approved layout; Smart/Manual never silently coexist; status chip always accurate.

---

## M6 — Smart Mask redesign

**Goal**: Recommended-for-full-protection pinned card; conditional-masking explainer; bulk actions; empty-state CTA.

**Evidence-first step**: Extract the inline filter into a pure function `applyFilter(...)` (also enables M8 perf + a JVM test).

**Files expected to change**: `SmartMaskSettingsScreen.kt`, new `AppListFilter.kt` (pure), `AppListFilterTest.kt`.

**Estimated LOC**: ~+120 / −40.

**Risk**: Low-Medium.

**Regression probability**: Low.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] Pure `applyFilter` covered by JVM tests (including the M1 crash cases).
- [ ] Recommended card shows detected SystemUI + keyboard.
- [ ] "Protect all suggested" works.
- [ ] Empty state CTA renders when 0 apps protected.

**Performance metrics**: Search typing recompositions reduced (Layout Inspector).

**Acceptance criteria**: Filter crashes gone (regression test); recommended card populated; bulk action works.

---

## M7 — Audit Log → Privacy Dashboard

**Goal**: Turn the flat event list into an insightful offline privacy dashboard.

**Data enrichment (schema-free)**: `AuditLog.action` gains typed values (`smart` / `instant` / `transient`) at insert time. Old rows read as "other". No migration needed (String column); retention prune added.

**Files expected to change**:
- `data/AppDao.kt` (aggregation queries: count-by-day, top-apps, count-by-action).
- `service/GhostScreenAccessibilityService.kt` (typed `action` at insert).
- `ui/screens/AuditLogScreen.kt` (dashboard UI).
- New: `PrivacyDashboardViewModel.kt`, `AuditExport.kt` (JSON/CSV to local file).

**Estimated LOC**: ~+350 / −120.

**Risk**: Medium.

**Regression probability**: Low (additive queries; existing list kept as "Recent").

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] Aggregation queries correct (JVM test with in-memory Room).
- [ ] Typed `action` written on every insert path.
- [ ] Export writes a local JSON/CSV; no network call.
- [ ] Retention prune runs correctly.
- [ ] Dashboard renders on empty + populated DB.

**Performance metrics**: Dashboard first-frame time; query time for 1k logs.

**Acceptance criteria**: Dashboard shows today/week counts, 7-day bars, top apps; export works offline.

---

## M8 — Performance optimization (recomposition + allocations)

**Goal**: Hit smooth 60 FPS in-app; eliminate per-frame allocations in overlay `onDraw`.

**Evidence-first step**: Layout Inspector recomposition counts + Studio Profiler allocation recording during drags — BEFORE numbers into benchmark.

**Work items**:
- Pre-allocate `Paint`/`Path`/`RectF` fields in `GhostScreenOverlay` inner views.
- `derivedStateOf` for app-list filter + color-editor `isSelected`.
- Icon bitmap cache in ViewModel.
- Handler cleanup on `onDetachedFromWindow` (StripView/OpacityDropView) — leak fix.

**Files expected to change**: `GhostScreenOverlay.kt`, `SmartMaskSettingsScreen.kt`, `SettingsScreen.kt`, new `IconCache`.

**Estimated LOC**: ~+150 / −60.

**Risk**: Medium (overlay draw code).

**Regression probability**: Medium — mitigated by invariant tests + visual QA.

**Rollback strategy**: `git revert` per sub-fix (commit granular).

**Validation checklist**:
- [ ] Allocation count/frame BEFORE vs AFTER recorded.
- [ ] No visual regression in overlay appearance.
- [ ] BOX passthrough test still green.
- [ ] Handler-leak test (weak-reference assertion after detach) green.

**Performance metrics**: allocations/frame, recompositions on search-typing, GC events during 30s drag.

**Acceptance criteria**: ≥80% reduction in per-move allocations; zero idle recompositions on Home/Smart Mask.

---

## M9 — Accessibility & M3 polish

**Goal**: Tokenize shapes/typography; add state layers; nav transitions; a11y sweep.

**Files expected to change**: `Theme.kt`, new `Motion.kt`, all screens (mechanical token swaps).

**Estimated LOC**: ~+200 / −180.

**Risk**: Low (cosmetic).

**Regression probability**: Low.

**Rollback strategy**: `git revert`.

**Validation checklist**:
- [ ] No raw `RoundedCornerShape(N.dp)` outside `Theme.kt`.
- [ ] No raw `fontSize` outside `Type.kt`.
- [ ] State-layer ripple on every custom clickable.
- [ ] Nav enter/exit specs applied.
- [ ] a11y scanner: zero high-severity issues on each screen.
- [ ] Touch targets ≥ 48dp.

**Acceptance criteria**: M3 tokenization complete; a11y scanner clean.

---

## M10 — Release preparation

**Goal**: Reproducible signed release; F-Droid-ready.

**Work items**: debug/release variants, signing via `local.properties`, R8 + keep rules, CI workflow, `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE`, `README` rewrite, F-Droid metadata, `CHANGELOG.md`.

**Files expected to change**: `app/build.gradle.kts`, `proguard-rules.pro`, `.github/workflows/ci.yml`, new docs.

**Estimated LOC**: ~+400 / −50.

**Risk**: Medium (R8 + signing).

**Regression probability**: Medium — R8 needs careful keep-rule verification.

**Rollback strategy**: `git revert` build config; keep R8 off if it breaks.

**Validation checklist**:
- [ ] Release APK installs + runs on API 26 and 35.
- [ ] Build twice from same commit → identical hash (reproducible).
- [ ] CI green on a throwaway PR.
- [ ] All open-source docs present.

**Performance metrics**: APK size before/after R8.

**Acceptance criteria**: Reproducible signed release; F-Droid metadata valid; CI enforced.

---

## Execution Rules (binding)

1. **Evidence before code.** Crashes: stacktrace first. Performance: measurement first. No exceptions.
2. **One logical change per commit.** Each milestone = multiple commits; each commit = one concern.
3. **Build + test after every commit**: `./gradlew assembleDebug` then `./gradlew testDebugUnitTest`.
4. **Record before/after in `PERFORMANCE_BENCHMARK.md`** for M2/M3/M4/M8 — `pending` cells are *not* acceptable for closed milestones.
5. **Never break the three locked invariants.** A test guards each.
6. **Hypotheses are labeled as such.** A hypothesis becomes a finding only with a stacktrace or a measurement.
7. **Rollback is always `git revert <commit>`** — every commit is independently revertible.

---

*This plan supersedes the "recommended sequence" sections of the individual reports where they conflict.*
