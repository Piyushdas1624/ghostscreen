# Improvement Roadmap: GhostScreen

This roadmap details the planned architectural, performance, security, and developer experience improvements for the [GhostScreen](file:///d:/ghostscreen) codebase.

---

## 1. Critical Improvements

These tasks resolve issues that can lead to state corruption, race conditions, or layout failures.

### 1.1 Preferences Initialization Queue
* **Category**: Security / Architecture / Refactoring
* **Details**: Implement a write-buffering mechanism inside [PreferencesManager](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt). Any settings changes made before `EncryptedSharedPreferences` completes initialization should be queued. Once initialized, flush the queue to the encrypted store before redirecting reads.
* **Benefit**: Eliminates the startup state desync race condition.

### 1.2 Structured Coroutine Monitoring in Accessibility Service
* **Category**: Architecture / Performance
* **Details**: Refactor the foreground package evaluation logic in [GhostScreenAccessibilityService](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt#L113). Convert `checkCurrentApp` into a suspending function. Execute it directly within `pendingCheckJob` instead of launching nested, untracked coroutines.
* **Benefit**: Ensures active checks are cancelled when a new window state event is received, preventing out-of-order execution.

### 1.3 Orientation-Adaptive Layout Handler
* **Category**: UI / UX / Architecture
* **Details**: Register a `ComponentCallbacks` listener in [GhostScreenAccessibilityService](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) to monitor screen rotations. On rotation, update the screen bounds in [GhostScreenOverlay](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) and recalculate window layout parameters.
* **Benefit**: Prevents the overlay from becoming misaligned when the screen rotates.

---

## 2. High Improvements

These tasks improve code maintainability, testing, and performance.

### 2.1 View Detach Resource Cleanup
* **Category**: Performance / Technical Debt
* **Details**: Implement resource cleanup in `StripView` and `OpacityDropView` in [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt). Cancel pending handler runnables (`idleRun`, `hideLbl`, `longRun`) when the views are detached from the window.
* **Benefit**: Prevents memory leaks and unnecessary background execution.

### 2.2 Startup State Restore
* **Category**: UX / Technical Debt
* **Details**: Modify the service startup sequence to perform an initial state check. If the service is restarted, read preferences to restore the overlay if armed or if manual override is active.
* **Benefit**: Restores protection automatically after service restarts or system boot.

### 2.3 Repository Design Abstraction
* **Category**: Architecture / Refactoring
* **Details**: Abstract database operations behind a Repository layer. Introduce an `AppsRepository` interface that handles communication with [AppDao](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDao.kt) and manages background thread dispatching.
* **Benefit**: Decouples data logic from the accessibility service, improving testability.

### 2.4 Test Suite Expansion
* **Category**: Testing / Developer Experience
* **Details**: Expand unit tests under [app/src/test](file:///d:/ghostscreen/app/src/test). Add tests for [PreferencesManager](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt) and verification flows in [MainActivity](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt).
* **Benefit**: Reduces regression risks during future updates.

---

## 3. Medium Improvements

These tasks enhance security compliance and polish the user experience.

### 3.1 Dead Code Cleanup
* **Category**: Refactoring / Developer Experience
* **Details**: Remove redundant `com.etaegord.ghostscreen.EVALUATE_STATE` broadcast actions in [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt), [GhostScreenTileService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenTileService.kt), and [ShortcutActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/ShortcutActivity.kt). Remove the commented-out `setupRandomIconIfNeeded` method in [MainActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt).
* **Benefit**: Simplifies the codebase.

### 3.2 Dynamic Play Store Queries Migration
* **Category**: Security / Play Store Compliance
* **Details**: For Play Store publishing, replace `QUERY_ALL_PACKAGES` in [AndroidManifest.xml](file:///d:/ghostscreen/app/src/main/AndroidManifest.xml) with targeted `<queries>` elements. Specify common messaging and privacy-sensitive apps in the queries list.
* **Benefit**: Complies with Play Store policies.

### 3.3 OEM Keyboard Compatibility Mapping
* **Category**: UX / Compatibility
* **Details**: Expand package detection rules in [OverlayTransition.kt:isTransient](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt#L41) to include package names for third-party and region-specific virtual keyboards.
* **Benefit**: Ensures reliable passthrough and masking across a wider range of devices.

---

## 4. Low & Nice to Have Improvements

These tasks focus on advanced customization and future scalability.

### 4.1 Custom Quick Settings Tile Customization
* **Category**: UI / UX
* **Details**: Allow users to customize the Quick Settings Tile label, color, and icon from the Settings screen.
* **Benefit**: Improves personalization.

### 4.2 Dynamic App Icon Customization Panel
* **Category**: UI / UX
* **Details**: Revive the dynamic launcher icon randomizer feature by adding a customization screen. This allows users to manually select their preferred app icon color (e.g., Red, Blue, Green, Purple) from the settings screen.
* **Benefit**: Enhances discretion and customization.

### 4.3 Automated CI/CD Pipeline
* **Category**: Developer Experience / CI/CD
* **Details**: Add a GitHub Actions workflow to run static analysis (Lint/Ktlint) and execute unit tests on every pull request.
* **Benefit**: Automates code quality checks.
