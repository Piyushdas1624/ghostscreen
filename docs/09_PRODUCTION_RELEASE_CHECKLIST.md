# Production Release Checklist (Open-Source / Sideloaded)

This checklist outlines the steps required to verify [GhostScreen](file:///d:/ghostscreen) for an open-source, sideloaded release (such as GitHub Releases, F-Droid, or private distribution) without publishing to the Google Play Store.

---

## 1. Stability & Lifecycle Verification

- [ ] **Accessibility Service Crash Recovery**: Test what happens when the accessibility service is terminated by the OS under low-memory conditions. Verify that when the system restarts the service, the overlay state is correctly restored based on the active application.
- [ ] **Overlay Lifecycle Verification**: Verify that calling `hide()` in [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt#L177) removes all active overlay views from the WindowManager. Ensure no child windows (such as corner handles or opacity dropdowns) remain detached in the background.
- [ ] **ANR Risk Mitigation**: Verify that database operations ([AppDao](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDao.kt)) are executed on background threads (`Dispatchers.IO`) and never block the main thread.
- [ ] **Boot Receiver verification**: Verify that when "Launch on Boot" is enabled, restarting the device sets the armed state to true and does not cause a crash or slow down the system boot broadcast queue.

---

## 2. Security & Code Review

- [ ] **Android Component Visibility (Exported)**: Verify that all exported components in [AndroidManifest.xml](file:///d:/ghostscreen/app/src/main/AndroidManifest.xml) are required to be public:
  - `MainActivity` (Exported: True - Entrypoint)
  - `BootReceiver` (Exported: True - Receives system boot broadcast)
  - `GhostScreenAccessibilityService` (Exported: True - Required for system binding)
  - `ShortcutActivity` (Exported: True - Launches from launcher shortcut)
  - `GhostScreenTileService` (Exported: True - Bound by system quick settings)
- [ ] **Sensitive Data Sanitization**: Verify that logging statements (`Log.d`, `Log.w`, `Log.e`) do not output sensitive user data, package names, or details of blocked apps.
- [ ] **Encrypted Storage Audit**: Verify that security-sensitive settings (such as encryption metadata or custom bypass configurations) are written to `EncryptedSharedPreferences`.
- [ ] **APK Release Signing**: Configure build signing settings in `app/build.gradle.kts` using a secure release keystore. Keep signing credentials out of public git repositories.

---

## 3. Performance & Resource Optimization

- [ ] **Cold Start Verification**: Profile the app launch time. Ensure that plain preferences ([PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt#L25)) are loaded instantly and do not block the main thread while Tink encryption initializes.
- [ ] **Memory Usage Analysis**: Profile heap allocations during scrolling in the app selection list ([SmartMaskSettingsScreen.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/SmartMaskSettingsScreen.kt)). Ensure that drawable-to-bitmap conversions are cached and do not trigger garbage collection jank.
- [ ] **Overlay Redraw Performance**: In BOX mode, verify that the central viewing area remains touch-passthrough and does not trigger graphics redraws.
- [ ] **Battery Impact Check**: Monitor battery usage in the background. Ensure that the accessibility service does not perform active polling and only reacts to `TYPE_WINDOW_STATE_CHANGED` events.

---

## 4. F-Droid & Sideloading Compliance

- [ ] **F-Droid Metadata File**: Create an F-Droid metadata recipe (`com.etaegord.ghostscreen.yml`) to automate builds from source.
- [ ] **Reproducible Build Setup**: Verify that building the APK twice from the same commit yields matching cryptographic hashes.
- [ ] **Dependency License Compliance**: Ensure all third-party libraries (e.g. AndroidX, Jetpack Security, Room) are compatible with open-source licensing (e.g. Apache 2.0, MIT).
- [ ] **Architecture splits vs. Universal APK**: Configure split APKs by ABI (ARMv7, ARM64, x86) in Gradle to reduce download sizes, while providing a fallback "Universal" APK for side-loaders.

---

## 5. OEM Compatibility & Background Killers

- [ ] **Battery Optimizer Bypass Guide**: Users on custom OEM skins (Xiaomi HyperOS, Samsung One UI, OnePlus OxygenOS, Oppo ColorOS) must manually configure settings to prevent the OS from killing the background service. Document these steps for the user:
  - Disable battery optimization for GhostScreen.
  - Enable "Auto-Start" / "Allow Background Activity".
  - Lock the app in the recent apps tray.
  - Review OEM guidelines at [dontkillmyapp.com](https://dontkillmyapp.com).
- [ ] **Android Go Compatibility**: Ensure memory allocations are kept within limits for low-RAM Android Go devices.

---

## 6. Testing & Quality Gates

- [ ] **Multi-Window & Split Screen Support**: Verify overlay behavior when the app is placed in split-screen mode or floating window mode.
- [ ] **System Configuration Testing (Rotation)**: Rotate the screen $180^\circ$ and verify overlay alignment.
- [ ] **API Version Matrix**: Test compatibility across target versions:
  - Android 8.0 (API 26 - Min SDK)
  - Android 12 (API 31 - System blur introduction)
  - Android 15 (API 35 - Target SDK)
  - Android 16 (API 36 - Future compatibility checks)
