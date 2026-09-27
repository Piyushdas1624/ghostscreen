# GhostScreen — Master Roadmap & Bug Register (M0 → M10)

> **Status legend:** ✅ Done (device-verified) · 🟡 Partial · ⬜ Not started
>
> **Source of truth:** the implementation. Where any doc disagrees with code, **the code wins** and the stale doc gets corrected.
> **Repo:** `E:\01_Projects_and_Code\ghostscreen` · branch `fable-5`
> **Status verified against code at:** `cb7d6e3`

---

## 0. Locked Invariants — never break these

Non-negotiable. Every milestone must preserve them.

| # | Invariant | Why | Enforced in |
|---|---|---|---|
| **I1** | **BOX viewport center stays empty** — 4 corner handles + 4 edge masks, never a fullscreen touch window | Only way to get true touch passthrough to the app underneath | `GhostScreenOverlay.addBoxWindows()` |
| **I2** | **Windows use `TYPE_ACCESSIBILITY_OVERLAY`**, not `TYPE_APPLICATION_OVERLAY` | Only type drawing over status bar, Gboard, and notification shade | `passiveParams()` / `maskParams()` / `activeParams()` |
| **I3** | **Manual ticker animations for the overlay**, never Compose `animate*AsState` | Compose skips frames when users disable system animations (Developer Options); the manual ticker always runs | `animAlpha()` / `ManualAnimUtils.kt` |
| **I4** | **Overlay fade never delays privacy activation** — window attaches fully functional immediately; only alpha animates | Privacy must be instant; animation is cosmetic | `show()` / `hide()` |
| **I5** | **No screen capture / MediaProjection for blur** | Violates the offline-privacy non-goal | blur strategy |
| **I6** | M3 tokens · no `GlobalScope` · Canvas-only overlay drawing · single-activity `NavHost` | Project coding standard | `PROJECT_MANIFEST.md §8` |

---

## 1. Evidence-Based Corrections to the Reported Symptoms

Verified by reading the code and testing on a real device. Fixing the *real* cause, not the described symptom.

| Reported symptom | Actual root cause | Status |
|---|---|---|
| "Opacity flicker = overlay hides then re-shows" | **False.** `setOpacity` writes in-memory + `invalidate()` only — no add/remove/visibility toggle in the opacity path. Real causes: (a) uncancellable `animAlpha` Runnable firing on torn-down views, (b) per-frame `Color.parseColor` of a pref that can read stale during the init-desync window | ✅ fixed (`d56dd83`) |
| "Armed masks globally / behaves like Manual" | **Not a logic bug.** `OverlayTransition.decide` was always correct — Smart masks only protected apps. The defect was that the Home UI never reconciled `isArmed` vs `isManualOverrideActive` | ✅ fixed (`d7ddc7a`) |
| "Quick Settings tile ~5s latency" | **Largely platform-bound** — cold AccessibilityService rebind is system-controlled (1–3 s). App-fixable portion: 5 separate `PreferencesManager` instances each paying Tink init, plus no immediate tile feedback | ✅ largely fixed (`453c490`, `cb7d6e3`) |
| "Blur leaves text readable" | **Platform ceiling is real.** `RenderEffect` blurs a View's *own* content, not what is behind it; blur-behind is OEM-dependent. Without MediaProjection (forbidden by I5) there is no true blur — so blur is demoted to a bonus and **masking quality is prioritized** | ⬜ Phase 5 |
| "Search GhostScreen crashes" | App's own launcher entry returned by `queryIntentActivities`; its `AdaptiveIconDrawable` has intrinsic size −1 so `Drawable.toBitmap` throws; `remember` re-threw on every re-keying recomposition | ✅ fixed (`9375456`) |
| "Filter chip crashes" | Inline filter re-derived the list from a Room Flow emission mid-composition; under "Enabled", a toggle yanked the row out while its `onToggle` coroutine was in flight | ✅ fixed (`13b187b`) |
| "Duplicate-package crash in Smart Mask" | Activity aliases produced duplicate `packageName` LazyColumn keys | ✅ fixed (`453c490`) |
| "Onboarding looks stuck / can't leave" | `isGhostScreenAccessibilityEnabled` returned false on cold start (wrong feedback mask) | ✅ fixed (`453c490`) |

---

## 2. Device Verification Protocol (mandatory per milestone)

Agreed workflow. Every claim is labelled device-verified, unit-verified, or static-only.

```
1. ./gradlew assembleDebug testDebugUnitTest         # build + unit gate
2. adb install -r app/build/outputs/apk/debug/*.apk  # install the FRESH build
3. adb shell am start -n com.etaegord.ghostscreen/.ui.MainActivity
4. Confirm installed build == current source
5. Exercise:
     adb shell input tap|swipe|text                  # scripted UI driving
     adb exec-out screencap -p > shot.png           # screenshot
     scrcpy --no-window --record clip.mp4            # screen recording
     adb shell dumpsys window windows | grep ...     # objective engine proof
     adb shell dumpsys gfxinfo <pkg>                 # frame timing
     adb shell dumpsys meminfo <pkg>                 # memory
     adb logcat -s GhostScreenService:V GhostScreenOverlay:V AndroidRuntime:E
6. Record verdict: device-verified / unit-verified / static-only
```

**Test device:** Infinix X6815D, Android 13 (API 33), MediaTek — a genuine low-end target whose launcher also crops adaptive icons aggressively. Ideal for this work.

> **Shell note:** under Git Bash the repo is at `/e/01_Projects_and_Code/ghostscreen`, and MSYS rewrites absolute POSIX paths passed to `adb`. Before any `adb shell run-as … /data/data/...` command run `export MSYS_NO_PATHCONV=1; export MSYS2_ARG_CONV_EXCL='*'`.

---

# Phase 0 — Baseline & Tooling

### M0.1 — Commit existing work as a clean baseline ✅
- [x] Gitignore dev scratch (`gs_home.png`, `ui_dump.xml`)
- [x] Docs/meta commit `748db11`
- [x] Source baseline: `util/` extraction, dead-broadcast removal, debug `DeveloperActivity` — `7081857`

### M0.2-ICON — Adaptive icon safe-zone fix ✅ **DONE** — `453c490`
- **Evidence:** the 108×108 foreground had **no `<group>` transform** (all absolute coordinates). The ghost body path reached **Y=16**, 5 dp above the Y=21 top edge of the centered 66×66 safe zone. Total vertical span 66.6 dp → zero breathing room, so every OEM mask cropped it.
- [x] Wrapped artwork in `<group android:scaleX="0.82" …>` so geometry lands inside x:21–87, y:21–87
- [x] `ic_launcher.xml` carries a `<monochrome>` reference
- **Outstanding:** `<monochrome>` still points at the **full-colour foreground**, so Android 13+ themed icons render the whole scene as one silhouette (muddy). A dedicated single-colour silhouette drawable is the correct follow-up → **M5.4**
- **Files:** `res/drawable/ic_launcher_foreground.xml`, `res/mipmap-anydpi/ic_launcher.xml`

---

# Phase 1 — Correctness, Crashes & Leaks

### M1.1 — Smart/Manual mutual-exclusion guard ✅ **DONE** — `d7ddc7a`
- [x] `util/ProtectionModes.kt` — pure, JVM-testable rule: arming ON clears Manual; disarming preserves Manual; turning Manual ON never disarms Smart
- [x] Home Arm toggle routed through it (still wired at `HomeScreen.kt:349`)
- [x] `ProtectionModesTest` — 6/6 pass
- **Verified on device:** pref listener fires correctly (`State changed - Armed/Manual`)

### M1.2 — "GhostScreen" search crash ✅ **DONE** — `9375456`
- [x] `runCatching` around the icon decode → falls back to the letter tile (also unifies the null-icon path)
- [x] `AppIconDecodeTest` (instrumentation)
- **Verified on device:** `connectedAndroidTest` 2/2 on Infinix API 33

### M1.3 — Filter-chip crash ✅ **DONE** — `13b187b`
- [x] `util/AppListFilter.kt` — pure, generic, Android-free `apply(scope, query, protectedSet)`
- [x] List derived once via `derivedStateOf`
- [x] `AppListFilterTest` — 9/9 pass
- [x] Follow-up: filter-chip reactivity via remembered state deps (`453c490`)

### M1.4 — animAlpha leak + onDetachedFromWindow ✅ **DONE** — `d56dd83`
- [x] `animAlpha` is now a single tracked, cancellable `Runnable`; cancelled before each new fade, in `hide()`, and self-stops when `!isShowing`
- [x] `onDetachedFromWindow()` in `StripView`, `CornerHandleView`, `OpacityDropView`
- **Verified on device:** overlay cycle OFF→ON→swipe→OFF gives exactly `1 → 7 → 1` windows in `dumpsys window windows`, zero `AndroidRuntime` exceptions, scrcpy recording captured

### M1.5 — EncryptedSharedPreferences corruption ✅ **DONE** — `453c490`
**Found live on device**, not hypothetical.
- **Evidence:** `AEADBadTagException` at `PreferencesManager.kt:65` — the Keystore master key no longer decrypts the stored ciphertext (reinstalls / key rotation / clock-wrong first boot). The app caught it and fell back to plain prefs, so nothing crashed, but **every launch re-failed encrypted init and all encrypted settings were silently lost**.
- **Recovery proven on device:** delete the store + Tink `profileInstalled` sentinel → next launch rebuilds cleanly
- [x] On encrypted-init failure: delete the orphaned store + sentinel, rebuild from plain prefs (one retry); plain-prefs fallback retained as last resort
- [x] Always sync `plainPrefs` → `encryptedPrefs` on every init — this is also the documented **desync fix** (`docs/08` Commit 1 / `10_AI_CONTEXT §6.1`)
- **Device-verified:** corrupted the store → relaunch → self-healed, no manual cleanup
- [x] Further hardened in `cb7d6e3`

### M1.6 — Accessibility detection cold-start false negatives ✅ **DONE** — `453c490`
- [x] `isGhostScreenAccessibilityEnabled` uses `FEEDBACK_ALL_MASK` + a `Settings.Secure` fallback

### M1.7 — Duplicate package keys across activity aliases ✅ **DONE** — `453c490`
- [x] Fatal crash in `SmartMaskSettingsScreen` from duplicate `packageName` LazyColumn keys

---

# Phase 2 — Overlay Performance

### M2.1 — BOX drag IPC reduction 🟡 **PARTIALLY DONE** — `453c490`
- **Original evidence:** 13 `updateViewLayout` IPCs per MOVE — `applyBoxDelta` called the full `updateCornerPositions()` (9 IPCs, including 4 mask windows already set to `GONE`), then `updateCornerHandlesOnly()` re-touched the same 4 handles.
- [x] `dragBoxCorner(cornerIdx, dx, dy)` added (`GhostScreenOverlay.kt:710`) — lightweight handle-only path
- [x] Full `updateCornerPositions()` deferred to `ACTION_UP`
- **Still open:** `applyBoxDelta` (line 693) **still calls `updateCornerPositions()` at line 706**. If any remaining call site routes a per-frame MOVE through `applyBoxDelta`, the old cost persists. Sweep every drag path to confirm it uses `dragBoxCorner`.
- **Acceptance:** ≥50% IPC reduction, no frame >16 ms in a 5 s drag
- **Verify with:** `adb shell dumpsys gfxinfo com.etaegord.ghostscreen` during a BOX drag

### M2.2 — `onDraw` per-frame allocations 🟡 **PARTIALLY DONE** — `cb7d6e3`
- **Original evidence:** per-frame `new Paint` (`StripView.drawEye`, `OpacityDropView.onDraw`), `new Path`, a `listOf(RectF(...))` in the `MaskView` drag branch, and `"${opacity()}%"` String allocations each frame.
- [x] Much of the overlay redraw path rewritten (314 lines changed in `GhostScreenOverlay.kt`)
- **Still open:** confirm no `new Paint/Path` remains inside `onDraw`; measure allocations/frame
- **Acceptance:** ≥80% fewer per-move allocations

### M2.3 — Color cache / opacity flicker ⬜ **NOT CONFIRMED**
- **Original evidence:** `Color.parseColor(preferencesManager.color)` ran every frame in `MaskWindowView.onDraw`.
- [ ] Confirm the parse is now cached and invalidated only on actual pref change
- Note: M1.5 already closed the desync window that made a stale-colour read possible

### M2.4 — Horizontal/opacity slider responsiveness 🟡 **PARTIALLY DONE** — `453c490`
- **Original evidence:** 7 `invalidate()`s per pixel of slider movement (5 masks + 2 strips) with masks left `VISIBLE`.
- [x] Debounced opacity
- [ ] Confirm strip/eye redraw only during drag, with mask invalidation deferred or throttled
- **Goal:** should feel like the system brightness/volume slider

### M2.5 — Touch slop consistency ⬜ **NOT STARTED**
- **Evidence (still present):** hardcoded density multiples at `GhostScreenOverlay.kt:958` (`7 * density`) and `:1029` (`5 * density`). `ViewConfiguration.scaledTouchSlop` is used nowhere in the tree. On a 3× device these resolve to 15–21 px, well above the platform norm, and the two constants are inconsistent between similar gestures.
- [ ] Use `ViewConfiguration.get(context).scaledTouchSlop` uniformly

### M2.6 — Position desync ⬜ **NEEDS ROOT-CAUSE WORK**
Reported as "slider moves, mask doesn't, then an opacity change fixes it."
- [ ] Instrument `safeUpdate` / `invalidate` during a drag; compare against mask geometry
- **Do not assume the cause.** M3.1 (the coroutine race) was one candidate and is now fixed — re-test the symptom first before touching the drag path.

---

# Phase 3 — Concurrency & State Integrity

### M3.1 — `checkCurrentApp` → `suspend` ✅ **DONE** — `453c490`
- **Original evidence:** a plain `fun` launching its own untracked `serviceScope.launch`; `pendingCheckJob` cancelled only the 150 ms debounce, not the DB query. Concurrent `isAppProtected()` queries could resolve out of order.
- [x] Now `private suspend fun checkCurrentApp` (`GhostScreenAccessibilityService.kt:149`), called directly inside the debounced job
- [x] Follow-up lifecycle sync in `cb7d6e3`

### M3.2 — `PreferencesManager` singleton ✅ **DONE** — `453c490`
- **Original evidence:** 5 construction sites, each paying its own Tink init (`MainActivity`, `GhostScreenAccessibilityService`, `GhostScreenTileService`, `BootReceiver`, `ShortcutActivity`).
- [x] Standardized across service, tile, activity, receiver
- **Directly addresses the tile-latency complaint**

### M3.3 — Startup desync ✅ **DONE** — rolled into M1.5

### M3.4 — Tile feedback + permission guards ✅ **DONE** — `cb7d6e3`
- [x] Tile permission guards added
- [ ] Confirm `qsTile.updateTile()` fires **before** the pref write so the tap registers instantly
- [ ] Document the cold-start floor honestly in About/Settings — Android controls AccessibilityService rebind and we cannot force it faster

### M3.5 — Rotation handling ✅ **DONE** — `453c490`
- **Original evidence:** `screenW`/`screenH` cached once in `show()`, no `onConfigurationChanged` anywhere — rotating misaligned the mask, a **privacy** failure not a cosmetic one.
- [x] `onConfigurationChanged` in the service (`GhostScreenAccessibilityService.kt:99`) → `overlay?.onConfigurationChanged()`

### M3.6 — Handler leaks ✅ **DONE** — rolled into M1.4

---

# Phase 4 — Home IA, Smart/Manual Clarity, Onboarding

### M4.1 — Home screen redesign ⬜ **NOT STARTED**
- **Evidence (still present):** the Smart Mask **full card** (`HomeScreen.kt:425`) and a **Smart Mask stat card** (`HomeScreen.kt:494–500`) both navigate to Smart Mask — the duplication is still there.
- **Also still missing from Home:** no Manual/Instant Mask entry point at all; no manual-mode status; no active-mode badge; no "armed but 0 apps selected" warning; no overlay-active indicator; no positive "permissions OK" state.
- [ ] Status header readable in 3 s: armed/disarmed · Smart vs Instant · overlay active · accessibility + permission badges
- [ ] One primary **Smart Protection** card (arm switch + "Protecting N apps" + Apps link)
- [ ] One clearly-labelled **Instant Mask** card
- [ ] **Remove the duplicate Smart Mask stat card**
- [ ] Audit Log entry
- **Mockup:** `reports/UX_REDESIGN_PROPOSAL.md §1`
- **Acceptance:** a user can state the current protection mode within 3 s of opening Home

### M4.2 — "Manual Override" → "Instant Mask" ⬜
- [ ] Rename in UI strings (keep the `manual_override_active` pref key)
- [ ] Make the Tile label mode-explicit

### M4.3 — Empty-list guard ⬜
- [ ] If armed but 0 protected apps → show "Select apps to protect" rather than a silent no-op while the chip claims "Protection Active"

### M4.4 — Onboarding explains the product ⬜
- **Evidence:** 4 pages (Welcome, Permission, Manual Mode, All Set). The **two-mode mental model is never taught** — the user finishes not knowing what Smart Mask is or that Manual is global. A fake interactive toggle (L277) flips state but does nothing, and a Samsung-only "Pro Tip" (L460) has no OEM fallback.
- [ ] New "How GhostScreen protects you" page: Smart vs Instant side-by-side
- [ ] Replace the fake toggle with real permission state
- [ ] Generalize the OEM tip
- [ ] Keep the `hadMissingOnEntry` auto-advance guard (already correct)

### M4.5 — State badges everywhere ⬜
- [ ] Consistent active-mode badge on Home, Tile, and onboarding

---

# Phase 5 — Masking Quality & Privacy Modes

### M5.1 — Research memo (no code) ⬜
Per steering: **do not chase blur as the primary privacy feature.** Write the comparison first.
- [ ] Compare candidates on privacy strength (can text still be read?), GPU cost, OEM compatibility, battery:
  - Tuned opaque tint · pixelation/mosaic (downscale-tile shader) · diagonal privacy-louver pattern · dithered noise (current fallback) · `FLAG_BLUR_BEHIND` (platform-gated, bonus only)
- [ ] **Recommend the best 1–2** on the privacy/perf/compat balance
- **Constraint:** must render through the existing overlay `Canvas`; no screen capture (I5)

### M5.2 — Implement chosen mask styles ⬜
- [ ] Selectable `MaskMode` preference; blur retained as the platform-gated bonus

### M5.3 — Honest blur UX ⬜
- [ ] Auto-boost tint where blur is unsupported
- [ ] "Blur strength depends on your device" note; wire the existing disclaimer pref

### M5.4 — Themed-icon silhouette ⬜
- [ ] Dedicated single-colour `<monochrome>` drawable (see M0.2-ICON outstanding item)

---

# Phase 6 — UI / Motion Polish & M3 Compliance

### M6.1 — Motion system ⬜
- [ ] `ui/theme/Motion.kt`: duration tokens (150 / 300 / 500 ms) + `Emphasized` / `EmphasizedDecelerate` easing
- [ ] Nav enter/exit specs; `AnimatedContent` for state transitions
- **Note:** in-app Compose motion is fine — the no-Compose rule (I3) applies to the **overlay**, not the app UI

### M6.2 — M3 tokenization 🟡 **PARTIALLY DONE** — `cb7d6e3`
- **Still outstanding:** inline `RoundedCornerShape(N.dp)` is pervasive across every screen; raw `fontSize`/`fontWeight` in Home; ~11 hardcoded hex in `SettingsScreen`.
- [ ] Inline shapes → `MaterialTheme.shapes.*`
- [ ] Raw typography → `MaterialTheme.typography.*`
- [ ] `SettingsScreen.kt:421-423` RGB slider tracks → M3 tokens. **Keep** the mask-colour preset swatches (L49–56) as literals — they are user-selectable *mask colours*, semantically data, not chrome.

### M6.3 — Overlay fade in/out ⬜
Per decision: manual ticker (I3), 80–150 ms, **privacy activates instantly** (I4), only alpha animates, prioritise responsiveness under load.
- [ ] Alpha-only fade on `show()`/`hide()` reusing the now-cancellable M1.4 ticker
- **Note:** `cb7d6e3` added "fluid spring physics" and "overlay transitions" — check what exists before designing this

### M6.4 — Micro-interactions ⬜
- [ ] State-layer ripples on custom `clickable` surfaces; empty/loading states; haptics; touch feedback

---

# Phase 7 — Audit Log → Privacy Dashboard
> Currently low-value. Redesign from a real user's perspective.

### M7.1 — Typed actions + schema migration ⬜
- [ ] Insert `action` values `smart` / `instant` / `transient` (currently one hardcoded string)
- [ ] **DB v1 → v2** migration — the moment to replace `fallbackToDestructiveMigration()` with a real `Migration(1,2)`

### M7.2 — Aggregation queries ⬜
- [ ] Count-by-day, top-apps, count-by-action, durations

### M7.3 — Dashboard UI ⬜
- [ ] Timeline, today/week summary, 7-day bar, search, filter, local export, clear
- [ ] Group by session rather than a flat log

---

# Phase 8 — Smart Mask UX

### M8.1 — Organisation ⬜
- [ ] Categories (banking / messaging / social / work / games), favourites, recents
- [ ] Bulk enable / select all / recommended defaults

### M8.2 — Keyboard / IME handling 🟡 **PARTIALLY DONE** — `453c490`
- [x] IME transient-package detection tests added
- **Remaining:** `isTransient` detection is broad and correct; the real weakness is **user guidance** — the app never suggests protecting a keyboard alongside a protected messaging app.
- [ ] Inline suggestion: "Also protect your keyboard?"
- [ ] A few more IME tokens (`gokeyboard`, `ai.type`) — cheap, future-proof
- **Keep:** conditional-mask inheritance via `lastProtectedPackage` is already correct

### M8.3 — Search & empty states ⬜

---

# Phase 9 — Low-End Device Performance
> Target: Android 8+, 2 GB RAM, MediaTek entry-level, smooth 60 fps.

### M9.1 — Baseline measurement ⬜
- [ ] Fill `PERFORMANCE_BENCHMARK.md` (**every cell is still `pending`**) with real Infinix numbers: cold/warm start, box-drag fps, tile latency, IPCs/move, allocations/frame, memory MB, APK size
- **Recipes already documented** in `BUG_INVESTIGATION_REPORT.md` (systrace, `dumpsys gfxinfo`)

### M9.2 — Optimise the measured hotspots ⬜
- [ ] Minimise GC, IPC, GPU overdraw, layout passes, recomposition, wakeups, battery
- [ ] `derivedStateOf` + debounced search in the Smart Mask filter
- [ ] Move icon decode off the row render path

### M9.3 — Regression policy ⬜
- [ ] >10% regression on a closed metric must be explained in the PR

---

# Phase 10 — Open-Source Readiness

### M10.1 — Project meta ⬜
- [ ] `LICENSE` (required for F-Droid), `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, issue/PR templates

### M10.2 — CI ⬜
- [ ] `.github/workflows/`: build + test + detekt + ktlint + lint
- [ ] F-Droid reproducible-build note (build twice → identical hash)

### M10.3 — Test expansion 🟡 **PARTIALLY DONE**
- [x] 5 test classes, **31/31 unit tests passing** (`453c490`): `AppListFilterTest`, `OpacityCurveTest`, `OverlayTransitionTest`, `ProtectionModesTest`, `ExampleUnitTest`
- [x] `AppIconDecodeTest` (instrumentation)
- [ ] `PreferencesManager` sync + recovery logic
- [ ] `AppDao` via in-memory Room
- [ ] **Overlay touch-passthrough instrumentation test — locked invariant I1 still has no automated guard**

### M10.4 — Documentation sync ⬜
Known drift to correct:
- [ ] `docs/05_IMPROVEMENT_ROADMAP.md` §3.1 still lists the `EVALUATE_STATE` cleanup as a TODO — it is already done
- [ ] `AboutScreen.kt:175-176` makes false technical claims ("PorterDuff.Mode.CLEAR", "ViewTreeObserver")
- [ ] `AboutScreen.kt:101` hardcodes `"v5.0 - The Master Blueprint"`; `version.properties` says `1.0.0-b2`. Bind to `BuildConfig.VERSION_NAME`
- [ ] `docs/01_PROJECT_OVERVIEW.md` claims 8.5/10; the audit says 61.5/100 — reconcile
- [ ] `docs/03_CODEBASE_GUIDE.md` §4.3 lists `rewrite.kt` / `generate_icons.py` / `temp.txt` as present clutter — all already removed
- [ ] `docs/04_BUGS_AND_DEFICIENCIES.md` and `ARCHITECTURE_AUDIT_REPORT.md` describe M1.1–M1.5, M2.1, M3.1, M3.2, M3.5 as outstanding — all now fixed

### M10.5 — Release hygiene ⬜
- [ ] Gate release logging behind `BuildConfig.DEBUG` (package names are logged unguarded)
- [ ] **Remove `QUERY_ALL_PACKAGES`** — now unnecessary: the only query is `queryIntentActivities(MAIN/LAUNCHER)`, which needs no permission. Removing it also removes the Play-policy risk.
- [ ] Enable R8 for release (currently `isMinifyEnabled = false`)
- [ ] Configure release signing

### M10.6 — Modularity ⬜
- [ ] Only if it produces measurable value — `GhostScreenOverlay` remains a ~1200-line god-class. **Not a priority**; YAGNI applies.

---

## 3. Commit Log (this effort)

| Commit | Scope |
|---|---|
| `748db11` | docs: manifest, AI context, agent rules, audit reports |
| `7081857` | refactor: `util/` extraction, dead-broadcast removal, debug `DeveloperActivity` |
| `d7ddc7a` | **M1.1** Smart/Manual mutual-exclusion guard + test |
| `9375456` | **M1.2** adaptive-icon decode guard + instrumentation test |
| `13b187b` | **M1.3** `AppListFilter` extraction + `derivedStateOf` + 9 tests |
| `d56dd83` | **M1.4** cancellable `animAlpha` ticker + `onDetachedFromWindow` |
| `453c490` | **M1.5/M1.6/M1.7, M2.1, M3.1/M3.2/M3.5, M0.2-ICON** — a11y detection fallback, alias dup keys, prefs singleton, drag IPC, cancellable check, icon scale, 31 tests |
| `cb7d6e3` | overlay spring physics, transitions, tile guards, lifecycle sync, overlay/UI perf |

---

## 4. Mapping: the 19 reported items → milestones

| # | Item | Milestone(s) | Status |
|---|---|---|---|
| 1 | Adaptive app icon | M0.2-ICON | ✅ (themed-icon follow-up M5.4) |
| 2 | Home screen IA | M4.1 | ⬜ |
| 3 | Armed toggle behaviour | M1.1 + M4.1 | ✅ logic · ⬜ UI |
| 4 | Manual vs Smart clarity | M4.1–M4.5 | ⬜ |
| 5 | Overlay performance | M2.1, M2.2, M9.2 | 🟡 |
| 6 | Overlay flicker | M1.4 | ✅ |
| 7 | Horizontal slider | M2.4, M2.5 | 🟡 |
| 8 | Position desync | M2.6 | ⬜ |
| 9 | Quick Settings tile | M3.2, M3.4 | ✅ largely |
| 10 | Gboard | M8.2 | 🟡 |
| 11 | Blur mode | M5.1–M5.3 | ⬜ |
| 12 | Settings performance | M6.2, M9.2 | 🟡 |
| 13 | Filter crash | M1.3 | ✅ |
| 14 | GhostScreen search crash | M1.2 | ✅ |
| 15 | Audit log | M7.* | ⬜ |
| 16 | UI & motion polish | M6.* | 🟡 |
| 17 | Open-source readiness | M10.* | ⬜ |
| 18 | Low-end performance | M9.* | ⬜ |
| 19 | Documentation sync | M10.4 | ⬜ |
| + | Encrypted-prefs corruption | M1.5 | ✅ |

---

## 5. Commit Discipline

- One logical change per commit, built and unit-tested before the next
- `ponytail:` comment on any intentional shortcut, naming the ceiling and upgrade path
- Every claim labelled device-verified / unit-verified / static-only — never blur the line
- Documentation updated in the same commit as any behaviour change
