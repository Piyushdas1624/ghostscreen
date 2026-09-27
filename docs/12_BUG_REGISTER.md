# GhostScreen — Bug Register

> **Source of truth:** the implementation. Verified against code at `cb7d6e3` · branch `fable-5`
>
> **Legend:** 🔴 High · 🟠 Medium · 🟡 Low · ✅ Fixed · ⬜ Open
> **Verification levels:** `device` (reproduced/fixed on a real phone) · `unit` (JVM test) · `static` (code-reading root cause, not yet run on device)

---

## A. Fixed — with root cause and commit

These were real bugs. Each entry records the **actual** root cause, which in several cases differed from the reported symptom.

### B-01 · "Opacity flicker — overlay hides then re-shows" ✅ 🔴
- **Reported as:** a hide/show cycle triggered by the opacity slider.
- **Actual root cause:** there is **no** hide/show cycle in the opacity path. `setOpacity` writes the in-memory `liveOpacity` and calls `invalidate()` — no `addView`, no `removeView`, no visibility toggle. The real flicker sources were:
  1. `animAlpha` built a fresh anonymous self-reposting `Runnable` per call and never tracked or cancelled it. A `hide()` landing mid-fade left it ticking ~182 ms, calling `invalidateCorners()` on corner views that had just been removed from the WindowManager.
  2. `Color.parseColor(preferencesManager.color)` ran every frame, so during the prefs init-desync window the mask could transiently draw the default colour.
- **Fix:** tracked cancellable `animAlpha` ticker + `onDetachedFromWindow()` cleanup in `StripView` / `CornerHandleView` / `OpacityDropView`.
- **Commit:** `d56dd83` · **Verification:** device — overlay cycle OFF→ON→swipe→OFF yields exactly `1 → 7 → 1` windows in `dumpsys window windows`, zero `AndroidRuntime` exceptions.

### B-02 · "Armed behaves like Manual Mask / masks everything" ✅ 🔴
- **Reported as:** a logic bug where turning Arm on enabled global masking.
- **Actual root cause:** **not** in the decision engine. `OverlayTransition.decide` was always correct — Smart mode masks only packages present in `protected_apps`. The defect was that the Home UI set `isArmed` without ever reconciling `isManualOverrideActive`, so a stale Manual session (set via Tile/Shortcut) made Smart *look* global.
- **Fix:** `util/ProtectionModes.kt` — arming ON clears Manual; disarming preserves Manual; turning Manual ON never disarms Smart. Home toggle routed through it. 6 unit tests.
- **Commit:** `d7ddc7a` · **Verification:** unit + device (pref listener path confirmed in logcat)

### B-03 · Searching "GhostScreen" crashes ✅ 🔴
- **Actual root cause:** the app's own launcher entry is returned by the `queryIntentActivities(MAIN/LAUNCHER)` query. Its icon is an `AdaptiveIconDrawable` whose intrinsic size is −1 until bounded, and `Drawable.toBitmap` throws on some API levels. The decode ran inside `remember`, so the exception was **re-thrown on every recomposition that re-keyed it** — typing in the search box — making the crash deterministic for that one entry.
- **Fix:** `runCatching` around the decode → falls back to the existing letter tile (also unifies the null-icon path).
- **Commit:** `9375456` · **Verification:** device — `connectedAndroidTest` 2/2 on Infinix API 33

### B-04 · Enabled/Disabled filter chip crashes ✅ 🔴
- **Actual root cause:** the inline filter re-derived the visible list from `protectedPackages` (a Room Flow emission) on every toggle. Under the "Enabled" filter, toggling a row off re-emitted the Flow and yanked the row out of the LazyColumn while its `onToggle` coroutine was still in flight — the row key dropped mid-composition.
- **Fix:** extracted `util/AppListFilter.kt` (pure, generic, Android-free) and derived the list **once** via `derivedStateOf`. 9 unit tests.
- **Commit:** `13b187b` · **Verification:** unit (9/9)

### B-05 · Duplicate package keys across activity aliases ✅ 🔴
- **Actual root cause:** the manifest declares four `MainAlias*` and four `ShortcutAlias*` activity aliases all targeting the same activity. The launcher-intent query returned duplicate `packageName`s, colliding as LazyColumn keys → fatal crash.
- **Fix:** de-duplication in `SmartMaskSettingsScreen`.
- **Commit:** `453c490` · **Verification:** static

### B-06 · Accessibility detection false negatives on cold start ✅ 🔴
- **Actual root cause:** `isGhostScreenAccessibilityEnabled` queried `getEnabledAccessibilityServiceList(FEEDBACK_GENERIC)`. Services that don't declare `feedbackGeneric` (or haven't published their info yet at cold start) were missed, so the UI showed "accessibility disabled" even when it was enabled — which reads to the user as "onboarding is stuck."
- **Fix:** `FEEDBACK_ALL_MASK` + a `Settings.Secure` fallback.
- **Commit:** `453c490` · **Verification:** static

### B-07 · EncryptedSharedPreferences corruption — silent settings loss ✅ 🔴
- **Found live on device**, not from static reading.
- **Actual root cause:** `AEADBadTagException` at `PreferencesManager.kt:65` — the Android Keystore master key no longer decrypts the stored Tink ciphertext (reinstalls, key rotation, or a first boot with a wrong clock). The code caught the exception and fell back to plain prefs, so **the app never crashed**, but every launch re-failed encrypted init and all encrypted settings (armed, transparency, colour, blur) were silently discarded.
- **Device evidence:** `ghostscreen_secure_prefs.xml` full of Tink ciphertext; Tink's `profileInstalled` sentinel present but the keyset missing; both files timestamped `1970-01-21` (clock-never-set marker).
- **Fix:** on encrypted-init failure, delete the orphaned store + sentinel and rebuild from plain prefs (one retry); plain-prefs fallback retained as the last resort. Plus always-sync plain→encrypted on every init, which also closes the documented startup-desync window.
- **Commit:** `453c490` (+ hardening in `cb7d6e3`) · **Verification:** device — corrupted the store, relaunched, it self-healed with no manual cleanup.

### B-08 · Handler leaks + teardown flicker ✅ 🟠
- **Actual root cause:** `postDelayed` runnables (`idleRun`, `longRun`, `hintRun`, `hideLbl`) in `StripView` / `CornerHandleView` / `OpacityDropView` were never cleared on detach. These are `inner`-class Runnables holding the outer `GhostScreenOverlay` and the service `Context`.
- **Fix:** `onDetachedFromWindow()` in all three views + the cancellable ticker from B-01.
- **Commit:** `d56dd83` · **Verification:** device

### B-09 · Overlay lags one event behind on rapid app switching ✅ 🔴
- **Actual root cause:** `checkCurrentApp` was a plain `fun` that launched its own untracked `serviceScope.launch`. The outer `pendingCheckJob` cancelled only the 150 ms debounce, not the DB query it spawned. Concurrent `isAppProtected()` queries on `Dispatchers.IO` could resolve out of order, so the last to finish won regardless of actual order.
- **Fix:** made it `suspend`, called directly inside the debounced job.
- **Commit:** `453c490` · **Verification:** static

### B-10 · Adaptive icon cropped/zoomed on OEM launchers ✅ 🟠
- **Actual root cause:** the 108×108 foreground had **no `<group>` transform** — all absolute coordinates. The ghost body path reached **Y=16**, which is 5 dp above the Y=21 top edge of the centered 66×66 safe zone. Total vertical span was 66.6 dp, so there was zero breathing room and every circle/squircle mask clipped the ghost's head. `ic_launcher_round.xml` was byte-identical to `ic_launcher.xml`, so both had the defect.
- **Fix:** wrapped the artwork in `<group android:scaleX="0.82" …>`.
- **Commit:** `453c490` · **Verification:** static — **still needs a screenshot on the Infinix/Transsion launcher to confirm**
- **Residual:** `<monochrome>` still references the full-colour foreground, so Android 13+ themed icons render the entire scene as one muddy silhouette → tracked as **B-21**.

---

## B. Open — not yet fixed

### B-11 · BOX corner drag does 13 `updateViewLayout` IPCs per MOVE 🟠
- **Root cause:** `applyBoxDelta()` internally called the full `updateCornerPositions()` (9 IPCs, including the 4 mask windows already set to `GONE` — a `GONE` window still costs the IPC), and `CornerHandleView.onTouchEvent` then called `updateCornerHandlesOnly()` (4 more IPCs) on the same handles.
- **Partial fix:** `dragBoxCorner()` was added and the full relayout deferred to `ACTION_UP` (`453c490`).
- **Still open:** `applyBoxDelta` (line 693) **still calls `updateCornerPositions()` at line 706**. Any call site still routing a per-frame MOVE through it keeps the old cost.
- **Acceptance:** ≥50% IPC reduction, no frame >16 ms in a 5 s drag.
- **How to verify:** `adb shell dumpsys gfxinfo com.etaegord.ghostscreen` during a BOX drag.

### B-12 · Horizontal (opacity) slider feels heavy 🟠
- **Root cause:** every horizontal MOVE calls `setOpacity()` → `invalidateMasks()` → 5 `invalidate()`s, plus 2 strip invalidates = **7 invalidates per pixel of slider movement**, with the mask windows left `VISIBLE`. The vertical branch does 1 IPC + 1 invalidate.
- **Partial fix:** debounced opacity (`453c490`).
- **Still open:** confirm the strip/eye redraws alone during drag, with mask invalidation deferred or throttled.
- **Goal:** should feel like the system brightness/volume slider.

### B-13 · Per-frame allocations in `onDraw` 🟡
- **Root cause:** `new Paint` in `StripView.drawEye` and `OpacityDropView.onDraw`, `new Path` in two places, a `listOf(RectF(...))` in the `MaskView` drag branch, and a `"${opacity()}%"` String each frame.
- **Partial fix:** the redraw path was substantially rewritten in `cb7d6e3` (314 lines changed).
- **Still open:** confirm no `new Paint/Path` remains inside `onDraw`; measure allocations/frame.
- **Acceptance:** ≥80% fewer per-move allocations.

### B-14 · Touch slop is a hardcoded density multiple 🟡
- **Root cause:** drag thresholds are `5 * density` (`GhostScreenOverlay.kt:1029`) and `7 * density` (`:958`). `ViewConfiguration.scaledTouchSlop` is used nowhere in the tree. On a 3× device these resolve to 15–21 px — well above the platform norm — and the two constants are inconsistent between similar gestures.
- **Impact:** the drag feels unresponsive / "sticky" before it starts, on exactly the low-end devices targeted by the 60 fps goal.

### B-15 · "Slider moves, mask doesn't" position desync ⬜ 🔴
- **Reported as:** changing opacity suddenly "fixes" the position.
- **Status:** **root cause not yet established.** Do not assume — B-09 (the coroutine race) was a candidate and is now fixed, so re-test the symptom on the current build before touching the drag path.
- **Method:** instrument `safeUpdate` / `invalidate` during a drag and compare against mask geometry.

### B-16 · Home screen has two Smart Mask representations ⬜ 🟠
- **Root cause:** the Smart Mask **full card** (`HomeScreen.kt:425`) and the Smart Mask **stat card** (`HomeScreen.kt:494–500`) both navigate to Smart Mask and both show the protected-app count.
- **Also missing:** no Manual/Instant Mask entry point on Home at all; no manual-mode status; no active-mode badge; no "armed but 0 apps selected" warning; no overlay-active indicator; no positive "permissions OK" state.
- **Fix:** see roadmap M4.1 — single Smart Protection card + separate Instant Mask card + 3-second status header.

### B-17 · Onboarding never explains the two-mode model ⬜ 🟠
- **Root cause:** 4 pages (Welcome, Permission, Manual Mode, All Set), but the **Smart vs Manual mental model is never taught**. A fake interactive toggle (line 277) flips local state and does nothing. The "Pro Tip" (line 460) is Samsung-only with no OEM fallback.
- **Impact:** this is the root of user confusion about what Arm actually does — a documentation problem surfacing as a UX bug.

### B-18 · Blur mode leaves text readable 🟠
- **Root cause:** **a genuine platform ceiling, not a coding bug.** `RenderEffect`/`RenderNode` blur applies to a View's *own* rendered content, not the content behind it. To blur what is behind, you must either capture the screen (MediaProjection — shows a system dialog each session and violates the project's offline/privacy non-goal) or use `FLAG_BLUR_BEHIND`, which is OEM-dependent, radius-capped, and is frequently declined outright for non-touchable accessibility overlays. The current fallback (tint + 64×64 tiled noise shader) is mosaic, not blur — at low alpha text stays legible.
- **Correct response:** prioritise masking quality over blur. See roadmap M5.1 for the research memo (opaque tint / pixelation / louver pattern / dithered noise) before writing any code.

### B-19 · Audit Log provides little value ⬜ 🟡
- **Root cause:** `AuditLog.action` is a single hardcoded default string (`"Blocked snoop attempt"`), and `insertAuditLog` is called from exactly one place. There are no aggregation queries — no count-by-day, no top-apps, no durations. The screen is a flat log.
- **Fix:** see roadmap M7.* — typed actions, DB v1→v2 migration, timeline/statistics/export.

### B-20 · `QUERY_ALL_PACKAGES` permission is no longer needed ⬜ 🟡
- **Root cause:** the manifest still declares it, but the only app query in the codebase is `queryIntentActivities(MAIN/LAUNCHER)`, which requires no such permission. The declaration is dead weight *and* it is the documented Play-policy rejection risk.
- **Fix:** remove the `<uses-permission>`; the Smart Mask screen already works without it.

### B-21 · Themed icon renders the full-colour foreground as a silhouette ⬜ 🟡
- **Root cause:** `ic_launcher.xml` sets `<monochrome android:drawable="@drawable/ic_launcher_foreground" />` — the same full-colour artwork. Android 13+ themed icons therefore tint the entire scene (screen, cheeks, mouth) into one muddy shape.
- **Fix:** a dedicated single-colour silhouette drawable (see roadmap M5.4).

---

## C. Release & compliance gaps (not user-visible bugs)

| # | Gap | Evidence |
|---|---|---|
| C-01 | **No `LICENSE`** | F-Droid requires one; file absent |
| C-02 | **No CI** | `.github/` absent — no build/test/lint gate on PRs |
| C-03 | **No `CONTRIBUTING.md` / `SECURITY.md` / issue templates** | All absent |
| C-04 | **R8 disabled for release** | `app/build.gradle.kts:44` `isMinifyEnabled = false` |
| C-05 | **Release signing unconfigured** | No `signingConfigs` block |
| C-06 | **Version mismatch** | `version.properties` = `1.0.0-b2`; `AboutScreen.kt:101` hardcodes `"v5.0 - The Master Blueprint"` |
| C-07 | **Destructive DB migration** | `AppDatabase.kt:26` `fallbackToDestructiveMigration()` — a future schema bump silently wipes protected apps and audit history. Must add `Migration(1,2)` in M7.1 |
| C-08 | **Unguarded release logging** | `GhostScreenAccessibilityService.kt:141` logs package names with no `BuildConfig.DEBUG` gate |
| C-09 | **False technical claims in About** | `AboutScreen.kt:175-176` cites "PorterDuff.Mode.CLEAR" and "ViewTreeObserver" — neither is used in the overlay |
| C-10 | **Locked invariant I1 untested** | No instrumentation test asserts BOX touch-passthrough. A regression would make the app unusable (it would swallow all touches). |
| C-11 | **Benchmark never measured** | `PERFORMANCE_BENCHMARK.md` has every cell `pending` — no before/after evidence exists for any perf claim |
| C-12 | **Stale docs** | `docs/04_BUGS_AND_DEFICIENCIES.md` and `ARCHITECTURE_AUDIT_REPORT.md` still list B-02…B-10 as outstanding; `docs/05` still lists the already-removed `EVALUATE_STATE` cleanup as a TODO; `docs/01` claims 8.5/10 vs the audit's 61.5/100 |

---

## D. How to reproduce on the test device

```bash
# Baseline: build → install → launch
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n com.etaegord.ghostscreen/.ui.MainActivity

# Objective overlay-engine proof (B-01, B-11)
adb shell dumpsys window windows | grep -c ghostscreen      # 1 = off, 7 = SPLIT overlay up

# Frame timing during a drag (B-11, B-12)
adb shell dumpsys gfxinfo com.etaegord.ghostscreen reset
# ...drag a corner / opacity slider for ~5s...
adb shell dumpsys gfxinfo com.etaegord.ghostscreen

# Memory + crash watch (B-08, B-13)
adb logcat -c
adb shell dumpsys meminfo com.etaegord.ghostscreen
adb logcat -s GhostScreenService:V GhostScreenOverlay:V AndroidRuntime:E

# Manual overlay on/off without the tile
adb shell am start -n com.etaegord.ghostscreen/.ui.ShortcutActivity

# Screen recording
scrcpy --no-window --record clip.mp4 --max-fps 30
```

> Under Git Bash, `export MSYS_NO_PATHCONV=1; export MSYS2_ARG_CONV_EXCL='*'` before any `adb shell run-as … /data/data/...` command, or MSYS rewrites the path.
