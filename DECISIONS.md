# GhostScreen Architectural Invariants & "Do Not Touch" Registry

> **CRITICAL DIRECTIVE FOR ALL AI AGENTS & CONTRIBUTORS**  
> Every entry in this document represents a hard-won, empirical workaround for Android system limitations, OEM firmware variations (Xiaomi HyperOS, Samsung OneUI, Realme/Oppo ColorOS), or touch-event dispatch physics.  
> **DO NOT refactor, simplify, or remove these patterns during "cleanups" or optimizations.** Doing so will silently break screen masking, block user touches to underlying apps, or cause background service terminations.

---

## 1. Box Mode Touch-Passthrough Architecture

* **Location:** [`GhostScreenOverlay.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)
* **The Naive Temptation:** Replace the multi-window layout with a single fullscreen translucent `ComposeView` or `ViewGroup` and use canvas clipping (`canvas.clipRect(..., Region.Op.DIFFERENCE)`) to punch a transparent viewport hole in the center.
* **Why This Breaks the App:**  
  On Android, if an overlay window covers a screen coordinate, the window consumes or blocks touch events at the WindowManager level *even if the canvas at that coordinate is completely transparent (`Color.TRANSPARENT`)*, unless `FLAG_NOT_TOUCHABLE` is set on the entire window. If `FLAG_NOT_TOUCHABLE` is set on a fullscreen window, the user cannot touch or drag the resize handles or dropdown controls.
* **The Permanent Invariant:**  
  In Box mode, GhostScreen MUST use a decoupled multi-window composite:
  1. **`MaskView`**: A fullscreen non-touchable window (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`) that draws the dark surrounding scrim.
  2. **4 × `CornerHandleView`**: Four distinct, miniature (56 dp) touch-enabled windows positioned precisely at the four viewport corners.
  3. **1 × `OpacityDropView`**: A small touchable pill window docked below the viewport.
  4. **1 × `ExitButtonView`**: A compact touchable window in the top corner.
* **Result:** The center rectangular reading viewport contains **zero overlay windows**. Touches fall straight through to the underlying application with native 120Hz responsiveness.

---

## 2. WindowManager Layout Flag: `TYPE_ACCESSIBILITY_OVERLAY`

* **Location:** [`GhostScreenOverlay.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)
* **The Naive Temptation:** Change window type to standard `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
* **Why This Breaks the App:**  
  `TYPE_APPLICATION_OVERLAY` is strictly constrained by Android's window z-ordering policy:
  - It cannot draw above the System Status Bar or Navigation Bar.
  - It cannot draw above the Virtual Software Keyboard (e.g., Gboard, Samsung Keyboard).
  - It cannot obscure the Notification Shade / Heads-up banners.
* **The Permanent Invariant:**  
  GhostScreen runs as an `AccessibilityService`. All overlay windows MUST be registered with:
  ```kotlin
  params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
  ```
  This is the only window type permitted by Android to render continuously across the status bar, navigation bar, and active input method editors (IME).

---

## 3. Manual Coroutine Ticker-Loops vs. Jetpack Compose Animations

* **Location:** [`ManualAnimUtils.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/utils/ManualAnimUtils.kt)
* **The Naive Temptation:** Replace manual coroutine ticker loops (`while (isActive) { delay(16); ... }`) with standard Jetpack Compose animation APIs (`animateFloatAsState`, `updateTransition`) or Android `ValueAnimator`.
* **Why This Breaks the App:**  
  1. Users who adjust Android Developer Options often set "Animator duration scale" or "Transition animation scale" to **Animation Off (0x)**. When system animators are disabled, standard Android `ValueAnimator` and Compose transitions immediately jump to their end values with 0 duration, breaking smooth mask fades.
  2. Window overlays are direct `android.view.View` trees attached to `WindowManager`, running outside the Compose Activity tree.
* **The Permanent Invariant:**  
  Overlay transitions and viewport morphing must rely on standalone Kotlin coroutines with explicit frame deltas and non-linear interpolation curves ([`OpacityCurve.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OpacityCurve.kt)).

---

## 4. Dual-Layer Frosted Glass Fallback for Low-End GPUs & MediaTek SoCs

* **Location:** [`GhostScreenOverlay.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)
* **The Naive Temptation:** Rely solely on `WindowManager.LayoutParams.FLAG_BLUR_BEHIND` and `setBlurRadius()` introduced in Android 12 (API 31).
* **Why This Breaks the App:**  
  1. Android 12+ devices with low-end GPUs (e.g., MediaTek Helio, Unisoc, entry-level Snapdragon) or battery-saver modes report `isCrossWindowBlurEnabled = false`.
  2. On OEM ROMs that disable window blur to save GPU power, setting only `FLAG_BLUR_BEHIND` results in a completely clear, transparent window that completely fails to mask sensitive information.
* **The Permanent Invariant:**  
  GhostScreen MUST render a high-opacity frosted fallback layer (translucent dark tint + procedural noise/scrim shader) as the baseline mask, and *additionally* apply `FLAG_BLUR_BEHIND` where hardware support is verified at runtime. If hardware blur is disabled, the solid tint opacity is automatically boosted by +15%.

---

## 5. Asymmetric Dual-Storage: Plain vs. Encrypted SharedPreferences

* **Location:** [`PreferencesManager.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt)
* **The Naive Temptation:** Initialize `EncryptedSharedPreferences` synchronously on the UI thread during Application startup, or remove the plain `SharedPreferences` cache.
* **Why This Breaks the App:**  
  `EncryptedSharedPreferences` relies on Google Tink cryptographic key-stores. On low-end or cold-booted devices, decrypting master keys from Android KeyStore can block the main thread for 300ms to 1200ms, triggering Application Not Responding (ANR) warnings or noticeable UI freeze.
* **The Permanent Invariant:**  
  1. Fast, read-critical settings (e.g., `isArmed`, `color`, `overlayMode`) are written immediately to standard `SharedPreferences` for zero-millisecond cold starts.
  2. `EncryptedSharedPreferences` is initialized asynchronously on a background `Dispatchers.IO` coroutine.
  3. Writes occurring during the initialization window are buffered in memory and flushed to encrypted storage once Tink initializes.

---

## 6. Transient Package Inheritance & Black-Flicker Prevention

* **Location:** [`GhostScreenAccessibilityService.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) & [`OverlayTransition.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt)
* **The Naive Temptation:** Whenever `onAccessibilityEvent` receives a window event from an unmasked package (such as the system keyboard `com.google.android.inputmethod.latin` or notification shade `com.android.systemui`), immediately call `overlay.hide()`.
* **Why This Breaks the App:**  
  When a user opens WhatsApp and taps a chat to type, window focus briefly transfers from `com.whatsapp` to `com.google.android.inputmethod.latin` or `com.android.systemui`. Hiding the overlay on keyboard launch causes horrific strobe/flicker and exposes sensitive chat history while typing.
* **The Permanent Invariant:**  
  Transient system surfaces (status bar, navigation bar, software keyboards, launcher panels) are registered in `OverlayTransition.isTransient()`. When entering a transient package, the service MUST inherit the mask state of the last real foreground application (`lastProtectedPackage`).

---

## 7. Phone/VoIP Call Audio Mode Exemptions

* **Location:** [`GhostScreenAccessibilityService.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt)
* **The Naive Temptation:** Treat incoming calls or heads-up dialer notifications as standard notifications and obscure them with the Notification Privacy Shield.
* **Why This Breaks the App:**  
  If the screen blacks out or obscures an incoming telephone or emergency call, the user cannot see the answer button or caller ID, causing missed emergency communications.
* **The Permanent Invariant:**  
  The service explicitly inspects `AudioManager.mode`. If `audioManager.mode != AudioManager.MODE_NORMAL` (signaling in-call, ringtone, or VoIP communication) or if the event originates from known dialer/telecom packages (`com.google.android.dialer`, `com.android.dialer`, `com.android.server.telecom`), Notification Shield MUST abort execution immediately.

---

## 8. Chinese & Custom OEM Background Restrictions (HyperOS, OneUI, ColorOS)

* **Location:** System-Level & [`AboutScreen.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/AboutScreen.kt)
* **The Naive Temptation:** Assume standard Android foreground service and accessibility service lifecycles operate identically across all manufacturers.
* **Why This Breaks the App:**  
  Aggressive battery optimization in Xiaomi HyperOS / MIUI, Samsung OneUI, and Oppo/Realme ColorOS silently kills `AccessibilityService` bindings after 15–30 minutes of screen-off time or drops overlay touch dispatch.
* **The Permanent Invariant:**  
  1. The app must guide users to exempt GhostScreen from battery optimization (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).
  2. On MIUI/HyperOS, the app documents the mandatory "Display pop-up windows while running in the background" permission toggle.
  3. GhostScreen maintains `BootReceiver` with `RECEIVE_BOOT_COMPLETED` to re-bind accessibility and restore armed state after device reboot.
