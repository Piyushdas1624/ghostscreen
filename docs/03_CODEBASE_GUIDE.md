# Codebase Onboarding Guide: GhostScreen

This guide is designed to quickly onboard a senior developer to the [GhostScreen](file:///d:/ghostscreen) codebase. It details package responsibilities, design patterns, naming conventions, architectural trade-offs, and how to safely extend the application.

---

## 1. Major Package Breakdown

The codebase is organized into four main packages under [app/src/main/java/com/etaegord/ghostscreen](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen):

```
ghostscreen/
├── data/           # Data access, persistence, and preferences
├── service/        # Android services and broadcast receivers
├── ui/             # Jetpack Compose interface and theme configurations
└── util/           # Mathematical curves and state transition engines
```

### 1.1 `data` Package
- **Purpose**: Manages the persistence of user configurations, protected apps, and security log entries.
- **Key Files**:
  - [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt): Wraps both plain and encrypted SharedPreferences. Implements lazy initialization on a background coroutine to prevent Tink crypto verification from blocking the main thread.
  - [AppDatabase.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDatabase.kt) & [AppDao.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDao.kt): Standard Room Database interface. Exposes `Flow` objects for UI observation and `suspend` methods for write operations.
  - [ProtectedApp.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/ProtectedApp.kt) & [AuditLog.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AuditLog.kt): Room entities representing the list of protected apps and snoop logs, respectively.

### 1.2 `service` Package
- **Purpose**: Controls overlay rendering and app monitoring.
- **Key Files**:
  - [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt): An `AccessibilityService` that monitors `TYPE_WINDOW_STATE_CHANGED` events to track foreground packages. It updates the overlay's state when matching apps are opened.
  - [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt): Manages custom views and window parameters. Uses the Android `WindowManager` to draw overlays directly.
  - [GhostScreenTileService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenTileService.kt): Implements the Quick Settings Tile to toggle manual override mode.
  - [BootReceiver.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/BootReceiver.kt): Registers boot completion events to enable the overlay automatically if "Launch on boot" is active.

### 1.3 `ui` Package
- **Purpose**: Renders the Compose-based UI.
- **Key Files**:
  - [MainActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt): The app's entry point. Initializes the navigation graph and handles start routing based on permission states.
  - [ShortcutActivity.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/ShortcutActivity.kt): A transparent helper activity that toggles manual override and closes immediately.
  - [screens/](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens): Contains modular screen components like [HomeScreen](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/HomeScreen.kt), [SettingsScreen](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/SettingsScreen.kt), and [SmartMaskSettingsScreen](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens/SmartMaskSettingsScreen.kt).
  - [utils/ManualAnimUtils.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/utils/ManualAnimUtils.kt): Houses custom animation tickers designed to bypass system-level animation disablers.

### 1.4 `util` Package
- **Purpose**: Pure business logic helper modules.
- **Key Files**:
  - [OverlayTransition.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt): Contains the state machine logic that decides whether to show, hide, or maintain the overlay state.
  - [OpacityCurve.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OpacityCurve.kt): Maps user-facing opacity percentages (0–100) to actual hardware alpha values (0–255) using custom interpolation curves.

---

## 2. Core Communication Patterns

The components of GhostScreen interact through specific channels:
- **UI → Service**: The UI does not bind to the accessibility service directly. Instead, it writes configuration changes to [PreferencesManager](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt). The service listens for these changes and updates the overlay state accordingly.
- **Service → Overlay**: [GhostScreenAccessibilityService](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) holds a direct reference to [GhostScreenOverlay](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt). It invokes `show()` or `hide()` based on window transitions.
- **Service → Data**: The service interacts directly with the database using [AppDao](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDao.kt) to check if the current foreground app is protected.

---

## 3. How to Add a New Feature

If you are tasked with adding a new feature, follow this checklist:

### Step 1: Database Schema (If persistent)
- Define new fields or entities in [data/](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data).
- Update the version in [AppDatabase.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDatabase.kt) and record the schema change in [structuredatabase.md](file:///d:/ghostscreen/structuredatabase.md).

### Step 2: Preference Keys (If config-related)
- Add preference keys to the `companion object` of [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt).
- Implement getters and setters, ensuring writes update both `plainPrefs` and `encryptedPrefs`.
- Add migration mapping inside the async initialization block if the setting has a default value.

### Step 3: UI Integration
- Add the corresponding Compose components under [ui/screens/](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/screens).
- Ensure all styling uses M3 semantic tokens from [Theme.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/theme/Theme.kt) and [Color.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/theme/Color.kt) instead of raw hex values.

### Step 4: Logic Integration (If background-related)
- If the feature affects overlay visibility, add state checks to [OverlayTransition.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt) and write corresponding unit tests in [OverlayTransitionTest.kt](file:///d:/ghostscreen/app/src/test/java/com/etaegord/ghostscreen/OverlayTransitionTest.kt).
- If it affects overlay appearance, update the drawing logic in [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt).

---

## 4. Coding Conventions & Code Smells

### 4.1 Patterns Being Used
- **Single-Activity Architecture**: Renders all screens within [MainActivity](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt) using Jetpack Compose Navigation.
- **Passive Background Coordination**: Separates background event monitoring from overlay window rendering, keeping logic modular.
- **M3 Tokens & Styling**: Resolves colors dynamically using Material 3 color schemes (e.g., `MaterialTheme.colorScheme.primaryContainer`), facilitating seamless light/dark mode transitions.

### 4.2 Patterns Being Violated
- **No Repository Abstraction**: The service accesses the Room database and DAO interfaces directly. While acceptable under the `ponytail` (lazy developer) guidelines, it introduces tight coupling between database logic and the background service.
- **Asynchronous Preference Race Condition**: Writes to preferences are not queued during the 3-9 second initialization window of `EncryptedSharedPreferences`. This can lead to configuration state desyncs if settings are changed immediately after launch.

### 4.3 Dead Code & Project Clutter
- **Dead Broadcasts**: The overlay close button ([GhostScreenOverlay.kt:L1078](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt#L1078)) and shortcut triggers send `com.etaegord.ghostscreen.EVALUATE_STATE` broadcasts. However, no broadcast receivers are registered for this action.
- **Commented-Out Randomizer**: The launcher icon randomizer `setupRandomIconIfNeeded` in [MainActivity.kt:L61](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt#L61) is disabled, rendering it dead code.
- **Project Root Clutter**:
  - `rewrite.kt` (in the project root): A copy of Compose UI screen code. It is not referenced by the compilation pipeline.
  - `generate_icons.py` (in the project root): A helper script for icon asset generation.
  - `temp.txt` and `logcat.txt` (in the project root): Temporary debug logs and dump files.
