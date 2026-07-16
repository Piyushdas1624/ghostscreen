# PROJECT_MANIFEST.md — GhostScreen

> **This is the canonical source of truth for the GhostScreen project.**
> Every AI session, code review, and feature discussion starts here.
> Read this file, then `docs/10_AI_CONTEXT.md`, then the relevant source files.
> Do NOT modify source code before reading both.

---

## 1. Project Vision

GhostScreen is a **100% offline, native Android privacy utility** that prevents shoulder surfing in public spaces. It draws a customizable semi-transparent overlay over the screen — with a clear, movable viewport — using the Android Accessibility API and `WindowManager`.

The app is **open-source and sideloaded only** (F-Droid, GitHub Releases, direct APK). It will never be submitted to the Google Play Store.

---

## 2. Non-Goals

These are things GhostScreen explicitly does **not** do and must never do:

| Non-Goal | Reason |
|---|---|
| Cloud sync or remote configuration | 100% local privacy is a core promise |
| Analytics, crash reporting SDKs (Firebase, Sentry) | Violates privacy-first contract |
| Google Play Store distribution | Play Store policies conflict with `QUERY_ALL_PACKAGES` and Accessibility use |
| Flutter, React Native, or cross-platform frameworks | Pure native Kotlin only |
| Root or ADB access requirements | Must work for regular users with no special setup |
| VPN, network interception, or screen capture | Out of scope; GhostScreen is a visual UI layer only |

---

## 3. Core Feature Set

| Feature | Status | Notes |
|---|---|---|
| SPLIT mode overlay (horizontal masking bars) | ✅ Stable | Movable viewport eye, opacity slider |
| BOX mode overlay (rectangular cutout) | ✅ Stable | 4-corner drag handles, true touch passthrough |
| Smart Mask (auto-detect protected apps) | ✅ Stable | Accessibility service, debounced 150ms |
| Transient surface protection (Gboard, notifications) | ✅ Stable | `OverlayTransition.isTransient()` pattern |
| Manual override (tile + shortcut) | ✅ Stable | `GhostScreenTileService`, `ShortcutActivity` |
| Audit log (intruder logging) | ✅ Stable | Room DB, `AuditLog` entity |
| Frosted glass / blur mode | ✅ Stable | `FLAG_BLUR_BEHIND`, Android 12+ only |
| Dynamic launcher icons | ✅ Stable | Package aliases in manifest |
| Encrypted preferences | ✅ Stable | `EncryptedSharedPreferences` + Tink |
| Screen rotation adaptation | ❌ Missing | `onScreenConfigurationChanged()` not implemented |
| Settings sync guard (startup desync fix) | ❌ Missing | Preferences race condition unresolved |
| Instrumentation / UI tests | ❌ Missing | Only 2 unit test files exist |

---

## 4. Architecture Decisions

These decisions are **locked**. Do not propose alternatives without a documented reason.

### 4.1 `TYPE_ACCESSIBILITY_OVERLAY` window type
All overlay windows use `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
**Why**: This is the only window type that draws over the system status bar, keyboard (Gboard), and notification shade simultaneously. `TYPE_APPLICATION_OVERLAY` cannot do this.

### 4.2 Multi-window approach for BOX mode passthrough
BOX mode composes 4 corner `CornerHandleView` windows + 4 edge `MaskWindowView` windows. The viewport center is left completely empty.
**Why**: A single fullscreen transparent window would intercept all touch events in the viewport, breaking the underlying app's interactivity. The only way to achieve true touch passthrough is to have no window in the viewport zone at all.

### 4.3 Manual coroutine ticker loops for animations
Compose animations (`animate*AsState`, `AnimatedVisibility`) are **not used** for overlay mask transitions. Manual coroutine loops in `ManualAnimUtils.kt` are used instead.
**Why**: When users disable system animations in Developer Options, Android skips Compose animation frames. The manual ticker ignores system animation scale and always runs.

### 4.4 Asymmetric dual-write preferences
Settings are written to `plainPrefs` (instant) and optionally to `encryptedPrefs` (async). Reads prefer encrypted when available.
**Why**: Tink key derivation can take 3–9 seconds on first launch. Blocking the main thread would cause an ANR. The tradeoff is a known startup desync window (see Known Limitations).

### 4.5 Direct DAO access in the service (accepted ponytail shortcut)
`GhostScreenAccessibilityService` queries `AppDao` directly without a repository layer.
**Why**: The ponytail rule (YAGNI) applies. A repository abstraction is documented in `docs/08_REFACTORING_PLAN.md` Commit 6 as a future cleanup — not an immediate requirement.

### 4.6 Pure Kotlin engines for state decisions
`OverlayTransition.kt` and `OpacityCurve.kt` have zero Android SDK imports.
**Why**: Enables fast JVM unit testing without instrumentation, emulators, or Robolectric.

---

## 5. Current Status

| Dimension | State |
|---|---|
| Core overlay engine | ✅ Production-grade |
| Smart masking logic | ✅ Production-grade |
| UI (Compose + M3) | ✅ Stable, light/dark themed |
| Preferences storage | ⚠️ Startup desync bug present |
| Concurrency | ⚠️ Coroutine race condition in accessibility service |
| Screen rotation | ❌ Not handled |
| Handler/memory leaks | ⚠️ View runnables not cleaned up on detach |
| Test coverage | ❌ Near-zero instrumentation coverage |
| Release signing | ⚠️ Not configured for release builds |

**Current audit score**: 61.5 / 100 (see `docs/ARCHITECTURE_AUDIT_REPORT.md`)

---

## 6. Release Target

- **Distribution**: F-Droid + GitHub Releases (sideload APK)
- **Min SDK**: API 26 (Android 8.0)
- **Target SDK**: API 35 (Android 15)
- **Version**: v5.0 Release Candidate (blocked by concurrency and rotation bugs)
- **Release Gate**: All items in `docs/09_PRODUCTION_RELEASE_CHECKLIST.md` must be checked

---

## 7. Known Limitations

> [!WARNING]
> Do not attempt to fix these by changing the architecture. Fix them using the patterns documented in `docs/08_REFACTORING_PLAN.md` and `docs/10_AI_CONTEXT.md`.

1. **Preferences startup desync**: Settings changed within the first ~5s of app launch may revert silently. Fix: sync `plainPrefs` to `encryptedPrefs` at the end of the init coroutine. Code pattern in `docs/10_AI_CONTEXT.md §6.1`.
2. **Accessibility service coroutine race**: Rapid app switching can cause older DB query callbacks to overwrite newer decisions. Fix: make `checkCurrentApp` a `suspend fun` inside the debounced job. Code pattern in `docs/10_AI_CONTEXT.md §6.2`.
3. **Handler leaks**: `postDelayed` runnables in `StripView` and `OpacityDropView` are not cleared on `onDetachedFromWindow`. Fix pattern in `docs/10_AI_CONTEXT.md §6.3`.
4. **Rotation misalignment**: `screenW`/`screenH` are cached once. Rotating the device misaligns the mask. Fix pattern in `docs/10_AI_CONTEXT.md §6.4`.
5. **Dead broadcasts**: `com.etaegord.ghostscreen.EVALUATE_STATE` is sent but never received. Safe to delete.

---

## 8. Coding Standards

| Rule | Detail |
|---|---|
| **No hardcoded colors** | Use `MaterialTheme.colorScheme.*` tokens only |
| **Matching on/color pairs** | `primaryContainer` → content must use `onPrimaryContainer` |
| **Pill geometry** | Use shapes from `Theme.kt`, never raw `RoundedCornerShape(Xdp)` inline |
| **GPU animations** | Use `graphicsLayer { }` modifier, not `offset()` or `padding()` for animation |
| **No new dependencies** | Prefer stdlib and existing AndroidX. Add a `ponytail:` comment if you must shortcut |
| **No `GlobalScope`** | Use `serviceScope` in the service; lifecycle-aware scopes in Compose |
| **Canvas for overlay drawing** | All overlay view drawing goes in `onDraw()` via Canvas, never Compose |
| **Single-activity** | All Compose screens live inside `MainActivity` via `NavHost` |

---

## 9. AI Workflow

When an AI assistant (Claude, Gemini, GPT, Codex) starts a new session:

```
Step 1 → Read PROJECT_MANIFEST.md          (this file)
Step 2 → Read docs/10_AI_CONTEXT.md        (code patterns, ADB debugging, what not to touch)
Step 3 → Read AGENTS.md                    (build commands, commit workflow, conventions)
Step 4 → Read the relevant source file(s)  (never read all 1090 lines of GhostScreenOverlay.kt upfront)
Step 5 → Propose a plan before touching code
Step 6 → Run ./gradlew testDebugUnitTest after every change
```

**Do not** read all files before understanding the task. **Do not** modify `GhostScreenOverlay.kt` without reading Section 4 of this file first.

### AI File Reading Priority Map

| Task type | Files to read first |
|---|---|
| Fix a preference/settings bug | `PreferencesManager.kt`, `docs/10_AI_CONTEXT.md §6.1` |
| Fix overlay show/hide logic | `GhostScreenAccessibilityService.kt`, `OverlayTransition.kt` |
| Fix memory or ANR | `GhostScreenOverlay.kt` (relevant inner class only), `docs/10_AI_CONTEXT.md §6.3` |
| Add a new UI screen | `ui/screens/HomeScreen.kt` (as template), `Theme.kt`, `Color.kt` |
| Add a new protected-app filter | `SmartMaskSettingsScreen.kt`, `AppDao.kt`, `AppDatabase.kt` |
| Change overlay appearance | `GhostScreenOverlay.kt` (inner view's `onDraw`), `docs/10_AI_CONTEXT.md §3` |

---

## 10. Definition of Done

A feature or fix is **done** when:

- [ ] Compiles with zero warnings: `./gradlew assembleDebug`
- [ ] All unit tests pass: `./gradlew testDebugUnitTest`
- [ ] No hardcoded colors, raw hex strings, or `GlobalScope` introduced
- [ ] No new external library added without discussion
- [ ] Overlay touch passthrough still works in BOX mode (manual device test)
- [ ] Mask still shows over keyboard (Gboard) and notification shade (manual device test)
- [ ] Manual animations still work with "Animator duration scale = 0" in Developer Options
- [ ] Change is documented with a `ponytail:` comment if it is an intentional shortcut
