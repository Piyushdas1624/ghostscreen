# AI Onboarding & Context Guide: GhostScreen

This document provides future AI coding assistants (such as Claude Fable 5 or Gemini) with the architectural context, design constraints, debugging tools, and exact code modification specifications needed to maintain and enhance [GhostScreen](file:///d:/ghostscreen).

---

## 1. Project Intent & Vision

**GhostScreen** is a native Android privacy utility designed to prevent "shoulder surfing" in public places. By running as a background accessibility service, it draws a customizable semi-transparent overlay over the screen, leaving a clear, movable viewport for reading.

---

## 2. Core Architecture & Philosophy

```
  Android Accessibility Events
              │
              ▼
    GhostScreenAccessibilityService (Monitors window focus changes)
              │
              ├─► OverlayTransition (Decides visibility state)
              │
              └─► GhostScreenOverlay (Manages layout & updates Windows)
                            │
                            ├─► StripView (Split Mode overlay controllers)
                            ├─► CornerHandleView (Box Mode resize handles)
                            └─► MaskWindowView (Mask overlays)
```

- **Non-Intrusive Overlays**: The app avoids drawing a fullscreen transparent container over the screen, as this would block touch events. Instead, it creates modular windows for handles and masks, leaving the viewport empty to allow touch passthrough.
- **Asymmetric Preferences**: Configurations are written instantly to plain SharedPreferences for immediate read access on startup, and asynchronously migrated to `EncryptedSharedPreferences` to prevent startup lag.
- **Decoupled Business Logic**: Decision logic ([OverlayTransition](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt)) and curves ([OpacityCurve](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OpacityCurve.kt)) are written in pure Kotlin without Android SDK dependencies, enabling fast unit testing.

---

## 3. What Must NEVER Be Changed

> [!CAUTION]
> **Box Mode Passthrough Layout**: In Box mode, the fullscreen `MaskView` must remain non-focusable and non-touchable (`FLAG_NOT_TOUCHABLE`). Do not wrap the center cutout window in a fullscreen touch-intercepting layer, as this will prevent touch events from reaching underlying apps. Touch passthrough must be maintained by keeping the viewport zone completely free of overlay windows.
>
> **Manual Ticker Animations**: Do not replace the coroutine-based ticker loops in [ManualAnimUtils.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/utils/ManualAnimUtils.kt) with Compose's default animation APIs (e.g. `animate*AsState` loops). The manual loop is designed to ensure animations run smoothly even if the user has disabled animations system-wide (e.g., in developer options).
>
> **Window Type Flags**: Overlay views must be added with layout parameter `type = TYPE_ACCESSIBILITY_OVERLAY`. Do not change this to `TYPE_APPLICATION_OVERLAY`, as it will prevent the overlay from drawing over the system navigation bars, keyboard, and notification shade.

---

## 4. Coding Conventions

- **Material Theme Tokens**: Do not hardcode hex color strings in UI components. Always resolve colors dynamically using Material 3 color schemes (e.g., `MaterialTheme.colorScheme.primaryContainer`).
- **Pill-Shaped Geometry**: Every interactive card, button, and input field must use pill-shaped or rounded geometry from the shape definitions in [Theme.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/theme/Theme.kt#L102-L108).
- **Canvas Rendering**: Viewport indicators, eye shapes, and border dots must be drawn using low-level canvas operations inside [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) views rather than Compose elements to keep GPU footprint low.

---

## 5. Location of Important Logic

- **Window Interception**: [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt#L79) (`onAccessibilityEvent`).
- **State Decision Machine**: [OverlayTransition.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt#L16) (`decide`).
- **WindowManager Operations**: [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt#L275-L368) (`addMask`, `addMaskWindows`, `addBoxWindows`, `removeSplitWindows`).
- **Visual Scale Interpolation**: [OpacityCurve.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OpacityCurve.kt#L32) (`displayToRealPct`).

---

## 6. Implementation Specifications for Core Refactoring

To guide future AI agents in implementing architectural cleanups and fixing bugs, use the following code patterns:

### 6.1 Fixing preferences startup desync
In [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt), sync plain settings to the encrypted store at the end of background initialization:
```kotlin
// In PreferencesManager init block:
CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
    try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val ePrefs = EncryptedSharedPreferences.create(
            appContext, PREFS_NAME_ENCRYPTED, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Always sync plainPrefs to ePrefs upon load to capture startup writes
        val editor = ePrefs.edit()
        plainPrefs.all.forEach { (key, value) ->
            if (key != KEY_MIGRATED) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
        }
        editor.putBoolean(KEY_MIGRATED, true)
        editor.apply()

        encryptedPrefs = ePrefs
    } catch (e: Exception) {
        Log.e(TAG, "Failed to init encrypted prefs, staying with plain prefs", e)
    }
}
```

### 6.2 Fixing accessibility service coroutine races
In [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt), convert `checkCurrentApp` to a suspending function called directly in the debounced launch block:
```kotlin
// In onAccessibilityEvent:
pendingCheckJob?.cancel()
pendingCheckJob = serviceScope.launch {
    delay(150)
    checkCurrentApp(packageName) // Suspending call
}

// Suspending function implementation:
private suspend fun checkCurrentApp(packageName: String) {
    val isTransient = OverlayTransition.isTransient(packageName)
    val effectivePackage = if (isTransient) lastActiveAppPackage else packageName

    val shouldProtect = if (effectivePackage != null) {
        withContext(Dispatchers.IO) {
            db.appDao().isAppProtected(effectivePackage)
        }
    } else false

    val decision = OverlayTransition.decide(
        currentPackage = packageName,
        isTransient = isTransient,
        isProtected = shouldProtect,
        lastEffectiveProtected = lastProtectedPackage,
        armed = preferencesManager.isArmed,
        manualOverride = preferencesManager.isManualOverrideActive
    )

    when (decision) {
        OverlayTransition.Decision.SHOW -> {
            hideJob?.cancel()
            val tag = if (preferencesManager.isManualOverrideActive) "manual" else effectivePackage
            if (tag != null && overlayShownForPackage != tag) {
                overlayShownForPackage = tag
                if (!preferencesManager.isManualOverrideActive && effectivePackage != null) {
                    lastProtectedPackage = effectivePackage
                }
                overlay?.show()
                // Log access asynchronously
                effectivePackage?.let { pkg ->
                    serviceScope.launch(Dispatchers.IO) {
                        val appName = getAppName(pkg)
                        db.appDao().insertAuditLog(AuditLog(packageName = pkg, appName = appName))
                    }
                }
            }
        }
        OverlayTransition.Decision.HIDE -> {
            if (overlayShownForPackage != null) {
                hideJob?.cancel()
                hideJob = serviceScope.launch {
                    delay(350)
                    overlayShownForPackage = null
                    overlay?.hide()
                }
            }
            lastProtectedPackage = null
        }
        OverlayTransition.Decision.STAY -> {
            hideJob?.cancel()
        }
    }
}
```

### 6.3 Fixing View Handler Leaks
In [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt), override `onDetachedFromWindow()` inside `StripView` and `OpacityDropView` to clean up callbacks:
```kotlin
// Inside inner class StripView:
override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    mainHandler.removeCallbacks(idleRun)
    mainHandler.removeCallbacks(longRun)
}

// Inside inner class OpacityDropView:
override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    mainHandler.removeCallbacks(idleRun)
    mainHandler.removeCallbacks(hideLbl)
    mainHandler.removeCallbacks(longRun)
}
```

### 6.4 Orientation Change Adaptation
In [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt):
```kotlin
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    overlay?.onScreenConfigurationChanged()
}
```
In [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt):
```kotlin
fun onScreenConfigurationChanged() {
    if (!isShowing) return
    updateScreenDimensions()
    mainHandler.post {
        if (overlayMode == Mode.SPLIT) {
            updateStripLayouts()
        } else {
            updateCornerPositions()
        }
    }
}
```

---

## 7. ADB Debugging & Testing Guide

To inspect and debug service transitions and rendering from the command line:

- **Filter logcat output**:
  ```bash
  adb logcat -s GhostScreenService -s GhostScreenOverlay -s PreferencesManager
  ```
- **Inspect active windows in WindowManager**:
  ```bash
  adb shell dumpsys window | grep -E "com.etaegord.ghostscreen"
  ```
- **Trigger manual overlay state changes**:
  Modify preferences directly via SharedPreferences or trigger shortcut activity intents:
  ```bash
  adb shell am start -n com.etaegord.ghostscreen/.ui.ShortcutActivity
  ```
