# GhostScreen

An open-source Android screen-masking and privacy overlay utility built with **Kotlin** and **Jetpack Compose** to keep shoulder-surfers out of your business in public spaces, transit, or crowded classrooms.

Most "privacy screen" apps on the Play Store are bloated with ads, sketchy network permissions, or clunky legacy XML overlays. GhostScreen does one job cleanly: it renders a customizable, hardware-accelerated dimming and masking overlay over your screen with zero network tracking and zero telemetry.

---

## Why GhostScreen?

Physical privacy screen protectors permanently cut display brightness and ruin viewing angles even when you're alone at home. GhostScreen gives you an on-demand software privacy filter you can toggle, resize, and fine-tune in seconds whenever you're reading messages, checking sensitive accounts, or typing credentials in public.

---

## Key Features

- **Customizable Privacy Shroud:** Real-time opacity, tint color, and dimming controls to block off-axis viewing from prying eyes.
- **Split Shroud Mode:** Dual horizontal masking bars with an adjustable, draggable clear viewport eye.
- **Box Cutout Mode:** 4-corner resizable viewport cutout with true hardware touch pass-through to underlying apps.
- **Smart Masking (Auto-Protect):** Automatically engages the privacy shield the moment sensitive or user-selected apps are launched, powered by an optimized Accessibility Service.
- **Frosted Glass Blur:** Native hardware-accelerated background blur (`FLAG_BLUR_BEHIND`) on supported Android 12+ devices.
- **Touch Pass-Through:** Full interactivity with underlying content inside the clear viewport while the mask remains locked in place.
- **Quick Settings Tile & Shortcuts:** One-tap toggle directly from the Android notification shade or home screen shortcuts.
- **Panic Shake & Motion Sensors:** Rapid tilt or shake gestures to instantly dismiss or trigger the overlay.
- **Zero-Telemetry Privacy:** Offline by design. GhostScreen does not request the `INTERNET` permission—what happens on your screen stays strictly on your device.
- **In-App Updater:** Direct GitHub Releases update checker with automatic SHA-256 integrity verification.

---

## Tech Stack & Architecture

| Layer | Technology |
| :--- | :--- |
| **Language** | Kotlin 2.0+ |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Overlay Engine** | Android `WindowManager` + `TYPE_ACCESSIBILITY_OVERLAY` |
| **Service Core** | `AccessibilityService` + `ForegroundService` |
| **State Management** | Kotlin Coroutines & `StateFlow` |
| **Persistence** | Room DB (`AuditLog`) + EncryptedSharedPreferences (Google Tink) |
| **Build System** | Gradle 8.13 + AGP 8.8 (Target SDK 35, Min SDK 26) |

---

## Permissions Explained

GhostScreen only requests what is strictly necessary to function:

- **Accessibility Service (`TYPE_ACCESSIBILITY_OVERLAY`):** Required to render privacy masks over status bars, software keyboards (Gboard), and notification shades, as well as detecting active foreground apps for Smart Masking.
- **`SYSTEM_ALERT_WINDOW` (Display over other apps):** Fallback overlay permission for standalone window rendering.
- **`FOREGROUND_SERVICE`:** Prevents the Android system from killing the active screen mask while multitasking between apps.
- **`POST_NOTIFICATIONS`:** Provides quick controls in the notification tray to adjust, pause, or kill the mask instantly.

*(Note: On Android 12 and above, Android platform security policies prevent third-party overlays from covering system permission dialogs.)*

---

## Getting Started

### Prerequisites

- **Android Studio** (Ladybug | 2024.2.1 or newer recommended)
- **JDK 17 or 21**
- **Android SDK 26+** (Android 8.0 Oreo minimum, Android 15 SDK 35 recommended)

### Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/Piyushdas1624/ghostscreen.git
   cd ghostscreen
   ```

2. Open the project in **Android Studio** and let Gradle sync dependencies.
3. Build and assemble the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install directly to a connected test device via ADB:
   ```bash
   ./gradlew installDebug
   ```

---

## Usage

1. Launch **GhostScreen** and grant the required **Accessibility** and **Display over other apps** permissions.
2. Select your preferred masking mode (**Split Shroud**, **Box Cutout**, or **Full Dim**).
3. Adjust opacity, tint color, and viewport dimensions to your liking.
4. Tap **Start Mask** or activate the **GhostScreen Quick Settings Tile** from your notification tray.
5. Drag the floating handles to reposition or resize the clear zone on the fly.

---

## Roadmap

- [ ] Custom pattern/noise shaders to break up high-contrast text at sharp angles
- [ ] Dynamic screen rotation re-anchoring
- [ ] OLED true-black battery-saving presets
- [ ] Per-app custom opacity rules

---

## Contributing

Found a bug or have an optimization for the overlay service? Pull requests and issues are welcome. Keep changes focused, follow Conventional Commits, and verify tests pass cleanly before submitting:

```bash
./gradlew testDebugUnitTest
```

---

## License

Distributed under the MIT License.
