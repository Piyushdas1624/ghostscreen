# OPEN_SOURCE_PREPARATION_PLAN.md — GhostScreen

> **Goal**: Make GhostScreen safe and welcoming for external contributors while protecting the locked architecture.
> **Principle**: A contributor should be able to clone, build, run, test, and make a first change within 15 minutes — and every guardrail that prevents them from breaking the app should be enforced by tooling, not by hoping they read the docs.

---

## 0. Contributor Journey (target state)

```
clone → ./gradlew assembleDebug  ✓ builds
      → ./gradlew testDebugUnitTest  ✓ 15+ tests pass
      → README "Getting Started" (5 min read)
      → AGENTS.md / CONTRIBUTING.md (guardrails)
      → pick a "good first issue"
      → make change, run tests + lint
      → PR auto-checked by CI (build + tests + detekt)
      → review against the locked invariants
      → merge
```

Today, steps 3, 6, and 7 are missing or manual. This plan closes those gaps.

---

## 1. Repository Hygiene (immediate)

### 1.1 Remove / gitignore committed clutter
These are tracked in git today and should not be:

| File | Disposition |
|---|---|
| `rewrite.kt` | Delete (unreferenced copy of UI code; `03_CODEBASE_GUIDE.md` flags it) |
| `temp.txt`, `logcat.txt` | Delete + gitignore |
| `nul`, `d__ghostscreen_phoneicon.xml` | Delete (Windows artifact + stray) |
| `detailed_list.txt`, `repo_tree.txt`, `prompt.md`, `run_claude.bat` | Delete or move to `.misc/` + gitignore |
| `mine stuffs/` (Stitch design sources) | Move to `docs/design-sources/` or `.misc/`; gitignore the raw HTML/PNG if not needed for build |
| `phoneicon.svg`, `d__ghostscreen_phoneicon.xml` | Keep one source of truth (SVG) under `docs/design-sources/` |
| `structuredatabase.md` | Delete — it's a generic template artifact from `.agents/rules/colour-rule.md`'s instructions, not GhostScreen's schema. The real schema is in `ProtectedApp.kt`/`AuditLog.kt`. |
| `implementation_plan.md` | Move into `docs/` if still relevant, else delete |
| `.kotlin/errors/*.log` | Delete + gitignore |
| `.idea/` (partially tracked) | Expand `.gitignore` to drop all of `.idea/` except shared run configs |

### 1.2 New `.gitignore` entries
```
# logs & dumps
*.log
logcat.txt
temp.txt
nul

# editor / OS
.idea/
*.iml
.DS_Store
Thumbs.db

# misc dev artifacts
/misc/
prompt.md
run_claude.bat
```

### 1.3 Fix the wrong-project rule file
`.agents/rules/colour-rule.md` is a **generic multi-mood mental-health app template** (Cherry Blossom, AMOLED, Pastel Dream, journal-entries schema). It does not belong to GhostScreen. **Delete it** or replace it with a GhostScreen-specific theming note. Keep `AGENTS.md` and the ponytail rule.

### 1.4 Remove the wrong-feature doc claims
- `AboutScreen.kt:175-176` claims "PorterDuff.Mode.CLEAR" and "ViewTreeObserver" — neither exists. Fix to match reality (multi-window passthrough).
- `01_PROJECT_OVERVIEW.md §7` claims "8.5/10" — contradicts the audit's 61.5/100. Correct or remove the score.

---

## 2. Documentation Reconciliation (docs vs code)

### 2.1 Stale-doc audit results (from the cross-validation pass)

| Doc claim | Reality | Action |
|---|---|---|
| Dead `EVALUATE_STATE` broadcasts (04_BUGS #6, audit #6, MANIFEST §7.5) | **Already removed** (grep: 0 refs) | Strike from docs |
| `checkCurrentApp` race (04_BUGS #2) | **Still present** | Keep doc, fix code (roadmap Commit 2) |
| Rotation misalignment (04_BUGS #3) | **Still present** | Keep doc |
| Handler leaks (04_BUGS #4) | **Still present** | Keep doc |
| 3 duplicated accessibility helpers (07_METRICS §3.1) | 2 consolidated into `AccessibilityUtils`, 1 remains in `ShortcutActivity` | Update doc |
| Repository pattern exists (CLAUDE.md architecture list) | **Does not exist** | Remove from "current architecture"; list as roadmap |

### 2.2 Single source of truth
Today there are **three overlapping guidance files**: `CLAUDE.md`, `AGENTS.md`, `PROJECT_MANIFEST.md`, plus `.agents/rules/AGENTS.md` (duplicate of root `AGENTS.md`). Consolidate:
- **`PROJECT_MANIFEST.md`** = canonical (vision, locked decisions, status). Keep.
- **`AGENTS.md`** = build/commit/conventions (operational). Keep, dedupe with `.agents/rules/AGENTS.md` (delete the `.agents` copy — it's identical).
- **`CLAUDE.md`** = thin pointer → `PROJECT_MANIFEST.md` + `AGENTS.md` + `docs/10_AI_CONTEXT.md`. Stop duplicating content.
- **`docs/10_AI_CONTEXT.md`** = code patterns & "what not to touch". Keep.

Add a **`docs/00_INDEX.md`** that lists every doc with one-line purpose + last-verified date, so contributors know what's current.

---

## 3. Build & Tooling Hardening

### 3.1 Build variants
Today there is only a `release` block; debug uses defaults. For contributor clarity:
```kotlin
buildTypes {
    debug { isMinifyEnabled = false; applicationIdSuffix = ".debug" }
    release {
        isMinifyEnabled = true   // roadmap Commit 9 — enable with keep rules
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // signingConfig = releaseKeystore  // configured via local.properties, not committed
    }
}
```
- `.debug` suffix lets contributors run a debug build alongside a production install.
- Signing config read from `local.properties` (never commit keystores).

### 3.2 Static analysis in CI
Add a GitHub Actions workflow (`.github/workflows/ci.yml`) running on every PR:
- `./gradlew assembleDebug`
- `./gradlew testDebugUnitTest`
- `detekt` (Kotlin static analysis) + `ktlint` (format) — fail PR on violation
- `./gradlew lint` (Android Lint)

Add a `detekt.yml` config tuned to the codebase (allow `SuppressLint("ClickableViewAccessibility")` where the overlay needs it; flag `GlobalScope`).

### 3.3 ProGuard/R8 keep rules (enabling minification)
```proguard
-keep class com.etaegord.ghostscreen.data.** { *; }            # Room entities/DAOs
-keep class com.etaegord.ghostscreen.service.GhostScreenAccessibilityService { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { public <init>(...); }
-keep class * extends android.service.quicksettings.TileService { <init>(); }
```
Roadmap Commit 9 — medium risk, needs verification build still works on a clean install.

### 3.4 Reproducible builds (F-Droid requirement)
- Pin all versions (already done via `libs.versions.toml` ✅).
- Set `org.gradle.caching=true`, `org.gradle.configuration-cache=true` in `gradle.properties`.
- Document the reproducible-build verification step (build twice, compare hashes) — required for F-Droid inclusion.

### 3.5 A `Makefile`-style gradle task alias
Contributors shouldn't memorize gradle commands. Add to `app/build.gradle.kts`:
```kotlin
tasks.register("checkAll") { dependsOn("detekt", "ktlintCheck", "lintDebug", "testDebugUnitTest") }
tasks.register("buildDebug") { dependsOn("assembleDebug") }
```

---

## 4. Testing Strategy (the biggest gap — audit score 20/100)

### 4.1 Current state
- 2 real unit-test files (`OpacityCurveTest`, `OverlayTransitionTest`) — 15 tests, all on the pure domain engines. Good.
- Zero instrumentation tests (only `ExampleInstrumentedTest` stub).

### 4.2 Unit-test expansion targets (JVM, fast, no device)
| Target | What to test | Why |
|---|---|---|
| `OpacityCurve` | ✅ done | — |
| `OverlayTransition` | ✅ done | — |
| `PreferencesManager` | read-after-write, default values, coercion (`transparency.coerceIn(0,100)`, `cutoutHeightPct.coerceIn(0.05,0.9)`) | Catches the desync class |
| `AppDao` | insert/query/delete/isAppProtected with an in-memory Room DB | Catches schema/query regressions |
| Filter/search logic (Smart Mask) | extracted pure function `applyFilter(list, mode, query, protected)` | Catches Bug #10, #11 logic without UI |

Extract the Smart Mask filter into a **pure function** (currently inline in the Composable) so it's testable. This also fixes the recomposition perf issue (PERF-4) — same refactor, two benefits.

### 4.3 Instrumentation tests (device/Robolectric)
| Target | Test |
|---|---|
| Overlay passthrough (BOX) | `windowManager` has exactly 4 mask + 4 corner windows after `show()`; viewport center window is absent |
| Overlay window type | every added window has `type == TYPE_ACCESSIBILITY_OVERLAY` |
| Overlay show/hide lifecycle | `hide()` removes every view (no leaked windows) |
| Accessibility decision end-to-end | simulate `TYPE_WINDOW_STATE_CHANGED` → assert overlay shown/hidden |

Use Robolectric for the WindowManager/lifecycle tests (no device needed) + a smaller set of on-device Espresso/UI-Automator tests for real passthrough verification.

### 4.4 Testing the locked invariants as executable guards
The three locked invariants are currently enforced by docs only. Make them **tests** so a contributor who breaks them sees red:
```kotlin
@Test fun box_mode_never_has_fullscreen_touchable_window() { ... }
@Test fun all_overlay_windows_use_accessibility_type() { ... }
@Test fun manual_ticker_is_used_for_mask_animations() { /* static check */ }
```

---

## 5. Architecture Improvements (incremental, ponytail-respecting)

### 5.1 Repository layer (roadmap Commit 6)
Introduce `AppsRepository` + `PrefsRepository` to get DB/DAO/prefs access out of the service and UI. Keep it minimal (YAGNI):
```kotlin
interface AppsRepository {
    fun protectedApps(): Flow<List<ProtectedApp>>
    suspend fun isProtected(pkg: String): Boolean
    suspend fun setProtected(pkg: String, name: String)
    suspend fun removeProtected(pkg: String)
}
```
This single change makes the service testable (inject a fake repository) and removes the direct-DAO layer violation flagged in the audit.

### 5.2 Singleton PreferencesManager (PERF-6)
Multiple components each `new PreferencesManager(context)`, each paying Tink init. Make it a singleton:
```kotlin
companion object {
    @Volatile private var instance: PreferencesManager? = null
    fun get(context: Context): PreferencesManager =
        instance ?: synchronized(this) { instance ?: PreferencesManager(context.applicationContext).also { instance = it } }
}
```
Cuts duplicate Tink inits and the tile-latency cost (Bug #5).

### 5.3 Splitting the god class
`GhostScreenOverlay.kt` (1090 lines, 6 inner views) is the maintainability bottleneck. **Do not** break it up hastily — the inner views share private state (`boxLeft`, `screenW`, `mainHandler`) and splitting risks the passthrough invariant. Instead:
- Extract **pure helpers** first: `BoxGeometry.kt` (clamping math), `OverlayPaints.kt` (pre-allocated Paints). Tested on JVM.
- Leave view classes as inner classes but move each into its own file via a `package`-private `OverlayViews.kt` once a clean seam exists.
- Track via a follow-up issue, not a forced refactor.

### 5.4 DI: evaluate Hilt vs. manual
The audit recommends Hilt (roadmap Commit 7). Counterpoint (ponytail): the app has ~4 injection sites; Hilt adds kapt/ksp + boilerplate. **Recommendation**: adopt Hilt **only if** testability requires it (§5.1 fakes). Otherwise, manual singletons (§5.2) are enough. Decision deferred to after the repository layer lands.

---

## 6. Debugging & Observability

### 6.1 Logging hygiene
- `Log.d` calls in the service log package names (`GhostScreenAccessibilityService.kt:141`). For a privacy app, release builds should **not** log sensitive data. Wrap logging behind `BuildConfig.DEBUG`:
  ```kotlin
  if (BuildConfig.DEBUG) Log.d(TAG, "Showing overlay for $tag")
  ```
- Add a `Log.TAG` constant convention.

### 6.2 Debug build overlay
Consider a debug-only on-screen debug chip (drag the overlay → show current `boxLeft/Right`, `screenW/H`, active mode) to help contributors reproduce the drag bugs without systrace. Low effort, high contributor value.

### 6.3 Documented ADB recipes
`docs/10_AI_CONTEXT.md §7` already has good ADB recipes. Expand with:
- Reproducing the race (Bug #3) via rapid `am start`.
- Dumping active overlay windows: `adb shell dumpsys window | grep ghostscreen`.
- Forcing service restart: `adb shell am force-stop` + relaunch.

---

## 7. Contributor-Friendly Structure

### 7.1 `CONTRIBUTING.md` (new)
- Build/test commands.
- Branch + commit conventions (small, single-focus; reference `docs/08_REFACTORING_PLAN.md`).
- The three locked invariants, in bold, at the top.
- "Good first issue" label policy.
- Code style: M3 tokens, no raw hex in UI, `ponytail:` for shortcuts.

### 7.2 Issue & PR templates (`.github/`)
- **Bug report**: repro steps, device/OEM/Android version, logcat excerpt, whether the overlay or the in-app UI.
- **Feature request**: which of Smart/Instant/both it affects.
- **PR template**: "Which invariant does this touch? Which report/bug ID does this address?"

### 7.3 `CODE_OF_CONDUCT.md` + `SECURITY.md`
- `SECURITY.md`: how to report a privacy/security issue (privately, not via public issue). Critical for a privacy app.
- License file: `LICENSE` (Apache 2.0, per `AboutScreen` claim — but **verify a LICENSE file actually exists**; if not, add one).

### 7.4 `README.md` rewrite
Current README is minimal. Target:
- One-paragraph what-it-is.
- Screenshot/short clip of the overlay.
- Install (F-Droid / APK / build from source).
- Permissions, explained honestly (why accessibility, why overlay, why QUERY_ALL_PACKAGES).
- "Is it private?" section — offline, no analytics, encrypted prefs.
- Contributing link.

---

## 8. Release & Distribution Readiness

### 8.1 Versioning
`version.properties` drives `versionCode`/`versionName` (good). Add:
- A `CHANGELOG.md` (keep-a-changelog format).
- Tag releases `git tag v1.0.0`.

### 8.2 F-Droid
- Create `com.etaegord.ghostscreen.yml` metadata (build recipe).
- Reproducible build verification (§3.4).
- License audit (all deps Apache-2.0/MIT — Room, Compose, Security-Crypto all OK).
- `UPDATE_MODE` recommendation.

### 8.3 OEM battery-killer docs
In-app (About) + README, link to dontkillmyapp.com with OEM-specific steps (already in `docs/09_PRODUCTION_RELEASE_CHECKLIST.md §5` — surface it to users).

---

## 9. Prioritized Open-Source Backlog

| ID | Item | Impact | Effort |
|---|---|---|---|
| OS-1 | Remove clutter + expand `.gitignore` | High | S |
| OS-2 | Delete wrong-project `colour-rule.md`; fix `AboutScreen` claims | High | S |
| OS-3 | Consolidate docs (00_INDEX, dedupe AGENTS, thin CLAUDE.md) | High | M |
| OS-4 | CONTRIBUTING + ISSUE/PR templates + SECURITY + LICENSE | High | M |
| OS-5 | CI workflow (build + tests + detekt + ktlint + lint) | High | M |
| OS-6 | Expand unit tests (prefs, DAO, filter fn) | High | M |
| OS-7 | Invariant guard tests (passthrough, window type) | High | M |
| OS-8 | Repository layer | Medium | M |
| OS-9 | PreferencesManager singleton | Medium | S |
| OS-10 | Debug-only logging + debug overlay chip | Medium | S |
| OS-11 | debug/release variants + signing via local.properties | Medium | S |
| OS-12 | Enable R8 + keep rules | Medium | M |
| OS-13 | README rewrite + screenshots | Medium | M |
| OS-14 | F-Droid metadata + reproducible builds | Medium | M |
| OS-15 | Hilt (conditional on repository layer) | Low | M |

---

## 10. Sequencing Across All Five Reports

To keep each change reviewable and safe, the recommended global order (each = one commit, built + tested):

1. **Hygiene**: OS-1, OS-2 (no behavior change; unblock clean diffs).
2. **Crashes & critical logic**: BUG #6 (Smart/Instant guard), #11 (search crash), #10 (filter crash).
3. **Docs**: OS-3, OS-4 (so the next steps are guided).
4. **Performance cluster**: BUG #1/#2/#3/#4 (PERF-1/2/3) — overlay drag/race/allocations.
5. **Tests + CI**: OS-5, OS-6, OS-7 (lock the gains).
6. **UX-1 → UX-6** (Home, onboarding, dashboard, motion, coachmark, settings).
7. **Polish**: BUG #5/9/12, PERF-4/5/6, OS-8/9.
8. **Release**: OS-11/12/14, signing, R8, F-Droid.

---

## 11. What will NOT change (locked)

Regardless of open-source polish, the three architecture locks remain:
1. BOX-mode multi-window touch passthrough (no fullscreen touchable center window).
2. `TYPE_ACCESSIBILITY_OVERLAY` for all overlay windows.
3. Manual coroutine tickers for overlay animations.

And the product non-goals remain: no cloud, no analytics, no Play Store, no Flutter, no screen capture.

*End of report. Awaits approval before implementation.*
