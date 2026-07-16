# Code Audit: Bugs & Deficiencies

This document details the findings of a comprehensive code audit of the [GhostScreen](file:///d:/ghostscreen) codebase. Each identified issue is evaluated for severity, root cause, impact, and solution path.

---

## Summary of Audit Findings

| # | Issue Description | Severity | Location | Estimated Effort |
|---|-------------------|----------|----------|------------------|
| 1 | `PreferencesManager` State Desync Race Condition | **High** | [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt) | Medium (3–4h) |
| 2 | `checkCurrentApp` Nested Coroutine Race Condition | **High** | [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt#L113) | Medium (2–3h) |
| 3 | Screen Orientation Rotation Mask Misalignment | **Medium** | [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) | Medium (3–5h) |
| 4 | Memory & Handler Leak via View Runnables | **Medium** | [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) | Low (1–2h) |
| 5 | Accessibility Service Initial State Ignored | **Low** | [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) | Low (1h) |
| 6 | Redundant `EVALUATE_STATE` Broadcasts (Dead Code) | **Low** | Multiple files | Low (0.5h) |
| 7 | Play Store Rejection Risk via `QUERY_ALL_PACKAGES` | **Low** | [AndroidManifest.xml](file:///d:/ghostscreen/app/src/main/AndroidManifest.xml#L6) | Low (1h) |

---

## Detailed Audit Logs

### 1. `PreferencesManager` State Desync Race Condition
* **Severity**: **High**
* **Location**: [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt)
* **Root Cause**: Lazy asynchronous initialization of `EncryptedSharedPreferences` on a background thread.
* **Explanation**: To prevent Tink crypto verification from blocking the main thread (which causes 3–9 seconds of startup jank), preferences are loaded in a coroutine. During this initialization window, any writes (e.g., arming the system) update `plainPrefs` but do not touch `encryptedPrefs` because it is still null. Once initialized, the `prefs` getter redirects all reads to `encryptedPrefs`, which now holds stale values from the previous launch.
* **Impact**: Configuration updates made immediately after application start are silently discarded or reverted.
* **Recommended Solution**: Maintain a memory cache of writes made during the initialization window. Once `EncryptedSharedPreferences` becomes available, apply the cached writes to the encrypted store before exposing it.
* **Estimated Effort**: 3–4 hours.

---

### 2. `checkCurrentApp` Nested Coroutine Race Condition
* **Severity**: **High**
* **Location**: [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt#L113-L184)
* **Root Cause**: Unmanaged database queries launched inside nested coroutines.
* **Explanation**: When a window state changes, the service schedules a debounced check using `pendingCheckJob`. While this debounces the initial trigger, the database query inside `checkCurrentApp` is launched inside a nested coroutine on `serviceScope`:
  ```kotlin
  private fun checkCurrentApp(packageName: String) {
      serviceScope.launch { // Untracked coroutine
          val shouldProtect = withContext(Dispatchers.IO) {
              db.appDao().isAppProtected(effectivePackage)
          }
          // ... apply decision ...
      }
  }
  ```
  If the user switches applications rapidly, multiple database query coroutines run concurrently. Because their completion order is not guaranteed, a slower query for an older app can resolve after a faster query for the newer app, overwriting the mask state.
* **Impact**: The privacy mask may hide itself when entering a protected app, or display itself when entering an unprotected app.
* **Recommended Solution**: Make `checkCurrentApp` a suspending function and execute it directly within `pendingCheckJob`. This ensures that any active checks are cancelled when a new window state event is received.
* **Estimated Effort**: 2–3 hours.

---

### 3. Screen Orientation Rotation Mask Misalignment
* **Severity**: **Medium**
* **Location**: [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)
* **Root Cause**: Screen dimensions are cached once during `show()` and are not updated when orientation changes.
* **Explanation**: The overlay uses physical pixels (`screenW` and `screenH`) to calculate window sizes and positions. When the screen rotates, these values become outdated. Because there is no listener for orientation changes, the layout is not recalculated.
* **Impact**: The privacy mask becomes misaligned, partially drawn, or misplaced when rotating the device, exposing sensitive content.
* **Recommended Solution**: Register a `ComponentCallbacks` listener inside the accessibility service to detect configuration changes. When a rotation occurs, update the cached dimensions and trigger a layout update on [GhostScreenOverlay](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt).
* **Estimated Effort**: 3–5 hours.

---

### 4. Memory & Handler Leak via View Runnables
* **Severity**: **Medium**
* **Location**: [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) (inside `StripView` and `OpacityDropView`)
* **Root Cause**: Posted runnables (`idleRun`, `hideLbl`, `longRun`) are not cleared from `mainHandler` when views are removed.
* **Explanation**: When the overlay is hidden, the views are removed from the `WindowManager`. However, active `postDelayed` messages remain in the queue. These runnables hold implicit references to the views, preventing them from being garbage-collected until the delays expire. They also attempt to run animation loops on detached views.
* **Impact**: Temporary memory leaks and waste of CPU cycles on detached views.
* **Recommended Solution**: Implement an explicit cleanup method in both views (or override `onDetachedFromWindow()`) to cancel all pending handler messages.
* **Estimated Effort**: 1–2 hours.

---

### 5. Accessibility Service Initial State Ignored
* **Severity**: **Low**
* **Location**: [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt)
* **Root Cause**: Lack of state checks during service initialization.
* **Explanation**: In `onCreate()`, the service sets up preferences listeners but does not check the current values of `isManualOverrideActive` or `isArmed`. If the service is killed and restarted by the OS while manual override is active, the overlay will not reappear until the next window switch event.
* **Impact**: Temporary loss of protection after service crash or system boot until the user switches windows.
* **Recommended Solution**: Run a startup state check inside `onServiceConnected()` or `onCreate()` to display the overlay if armed or if manual override is active.
* **Estimated Effort**: 1 hour.

---

### 6. Redundant `EVALUATE_STATE` Broadcasts (Dead Code)
* **Severity**: **Low**
* **Location**: [GhostScreenTileService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenTileService.kt), [ShortcutActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/ShortcutActivity.kt), and [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)
* **Root Cause**: Leftover code from a previous architectural design.
* **Explanation**: Components broadcast `com.etaegord.ghostscreen.EVALUATE_STATE` to trigger overlay evaluations. However, no broadcast receivers are registered for this action. State changes are instead handled by preference change listeners in the service.
* **Impact**: Unnecessary intent creation and broadcast overhead.
* **Recommended Solution**: Remove the unused broadcast creation and dispatch logic.
* **Estimated Effort**: 0.5 hours.

---

### 7. Play Store Rejection Risk via `QUERY_ALL_PACKAGES`
* **Severity**: **Low**
* **Location**: [AndroidManifest.xml](file:///d:/ghostscreen/app/src/main/AndroidManifest.xml#L6)
* **Root Cause**: Declaring the broad `QUERY_ALL_PACKAGES` permission.
* **Explanation**: Google Play Store restricts the use of `QUERY_ALL_PACKAGES` to specific app types (e.g., file managers, antivirus software). Unless the app is distributed exclusively via sideloading (APK), Google Play will reject it.
* **Impact**: The app cannot be published to the Google Play Store.
* **Recommended Solution**: If publishing to Google Play is planned, remove the permission and declare specific `<queries>` blocks in the manifest for common target packages, or use Android's query intent mechanism.
* **Estimated Effort**: 1 hour.
