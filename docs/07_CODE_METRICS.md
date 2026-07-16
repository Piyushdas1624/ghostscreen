# Code Metrics & Quality Analysis: GhostScreen

This document presents a static analysis and metrics audit of the [GhostScreen](file:///d:/ghostscreen) codebase, evaluating size, complexity, duplication, and modular stability.

---

## 1. File Size Metrics

The codebase consists of 24 main source files (Kotlin). Below is a distribution of code volume, ordered by line count:

| File Name | Location | Line Count | Size (Bytes) | Category |
|---|---|---|---|---|
| [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) | `service/` | **1090** | 57,758 | Core Logic (God Class) |
| [HomeScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/HomeScreen.kt) | `ui/screens/` | 583 | 25,181 | UI Composition |
| [SmartMaskSettingsScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/SmartMaskSettingsScreen.kt) | `ui/screens/` | 523 | 22,706 | UI Composition |
| [OnboardingScreens.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/OnboardingScreens.kt) | `ui/screens/` | 478 | 22,736 | UI Composition |
| [SettingsScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/SettingsScreen.kt) | `ui/screens/` | 444 | 28,768 | UI Composition |
| [AuditLogScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/AuditLogScreen.kt) | `ui/screens/` | 232 | 8,932 | UI Composition |
| [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) | `service/` | 215 | 9,025 | Service Controller |
| [AboutScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/AboutScreen.kt) | `ui/screens/` | 197 | 8,366 | UI Composition |
| [MainActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt) | `ui/` | 177 | 6,409 | Main Activity Host |
| [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt) | `data/` | 164 | 6,998 | Data Wrapper |

---

## 2. Function & Method Metrics

### 2.1 Largest Methods (Lines of Code)

The table below lists the longest methods in the codebase. Functions exceeding 40 lines are candidates for modular refactoring:

| Class | Method | Estimated LOC | Complexity | Primary Reason |
|---|---|---|---|---|
| `GhostScreenOverlay` | `onTouchEvent` (in `StripView`) | 58 | **High** | Multi-branch gesture checking (X and Y coordinate calculations, bounds, opacity adjustments) |
| `GhostScreenOverlay` | `updateCornerPositions` | 53 | Medium | Cross-process layout calls for four separate mask views |
| `GhostScreenOverlay` | `drawEye` (in `StripView`) | 45 | Medium | Direct drawing of paths, circles, and labels on Canvas |
| `GhostScreenOverlay` | `onDraw` (in `OpacityDropView`) | 40 | Low | Canvas shader drawing and haptic indicators |
| `GhostScreenOverlay` | `onTouchEvent` (in `CornerHandleView`) | 40 | Medium | Multi-pointer touch gestures |

### 2.2 Cyclomatic Complexity & Deep Nesting hotspots

- **Hotspot 1: `GhostScreenOverlay.StripView.onTouchEvent()`**
  - **Nesting Depth**: 5 levels deep.
  - **Complexity Value**: 14 (due to nested `when` (actions), `if` (isDragging), `if` (dy > dx), and `if` (isTop)).
  - **Deficiency**: High risk of logic errors during code modification. Gesture math is intermingled with window manager state updates.
- **Hotspot 2: `SmartMaskSettingsScreen.kt (resolveInfos loading block)`**
  - **Nesting Depth**: 4 levels deep (looping resolving activities, checking suggested keywords, executing package managers).
  - **Deficiency**: Poor separation of concerns; app loading logic is hardcoded inside a Compose `LaunchedEffect` block rather than executed in a repository layer.

---

## 3. Duplicate Code Detection

We identified several blocks of duplicate logic across the codebase:

### 3.1 Accessibility Service Status Checks
* **Duplicate A**: `isAccessibilityServiceEnabled` helper in [OnboardingScreens.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/OnboardingScreens.kt).
* **Duplicate B**: `isAccessibilityEnabled` helper in [ShortcutActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/ShortcutActivity.kt).
* **Duplicate C**: `isGhostScreenAccessibilityEnabled` helper in [AccessibilityUtils.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/AccessibilityUtils.kt).
* **Deficiency**: The same accessibility detection routine is declared three separate times. The helper in `ShortcutActivity` uses hardcoded generic settings and is duplicate logic. It should be refactored to use `isGhostScreenAccessibilityEnabled` uniformly.

### 3.2 Drag Threshold Constants
* **Duplicate**: The drag limit calculation (`5 * density` and `7 * density`) is repeated across multiple view touch event handlers:
  - `StripView`: `if (!isDragging && (dx > 5 * density || dy > 5 * density))`
  - `CornerHandleView`: `if (!dragging && (abs(dx) > 5 * density || abs(dy) > 5 * density))`
  - `OpacityDropView`: `if (!dragging && abs(dx) > 7 * density)`
* **Deficiency**: Inconsistent touch slop values. The app should use Android's system `ViewConfiguration.get(context).scaledTouchSlop` instead of hardcoded density multiples.

---

## 4. Technical Debt & Codebase Health Summary

### 4.1 Debt Classification & Code Metrics
- **God Classes**: 1 class ([GhostScreenOverlay](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)). Contains both the WindowManager lifecycle logic and drawing code for 6 different custom views.
- **Dead Code Percentage**: ~4% of Java/Kotlin source code (due to unused `setupRandomIconIfNeeded` methods, unreferenced `rewrite.kt` in the root, and dead `com.etaegord.ghostscreen.EVALUATE_STATE` broadcasts).
- **Unused Resources**: Several launcher icons (e.g. `ic_ghost_icon_blue`, `ic_ghost_icon_red`, etc.) are declared in the manifest but disabled by default, accumulating size in the compiled APK.
- **Maintainability Index**: **68 / 100** (Needs attention, heavily penalized by the size and complexity of `GhostScreenOverlay.kt`).

### 4.2 Package Coupling Metrics
- **Afferent Coupling ($C_a$)**: Number of external packages depending on this package.
- **Efferent Coupling ($C_e$)**: Number of packages this package depends on.
- **Package Abstractness ($A$)**: Ratio of abstract classes/interfaces to concrete classes.
- **Distance from Main Sequence ($D$)**: $D = |A + I - 1|$. An ideal package sits close to $0.0$ (representing balanced abstractness and stability).

| Package | Afferent ($C_a$) | Efferent ($C_e$) | Instability ($I$) | Abstractness ($A$) | Distance ($D$) |
|---|---|---|---|---|---|
| `ghostscreen.data` | 2 | 0 | 0.0 | 0.20 | **0.80** |
| `ghostscreen.util` | 2 | 0 | 0.0 | 0.00 | **1.00** |
| `ghostscreen.service` | 0 | 2 | 1.0 | 0.00 | **0.00** |
| `ghostscreen.ui` | 0 | 3 | 1.0 | 0.00 | **0.00** |

> [!TIP]
> **Metric Interpretation**: The `util` package has $D = 1.0$, indicating it is highly stable ($I = 0$) but completely concrete ($A = 0$). This is acceptable for simple calculation functions, but indicates a lack of abstraction where interfaces could be introduced for extensibility.
