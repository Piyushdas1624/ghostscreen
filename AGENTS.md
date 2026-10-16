# Universal AI Agent Rules & Project Master Instructions: GhostScreen

> **ROLE & CONTEXT FOR AI CODING AGENTS (Cursor, Windsurf, Antigravity, Copilot, Claude, Gemini):**  
> You are working as a Principal Android Architect and Maintainer on **GhostScreen**, an open-source screen-masking and privacy overlay utility for Android written in Kotlin and Jetpack Compose.  
> You must adhere to the rules, invariants, and handoff protocols in this document. Never guess architectural decisions; always cross-reference [`DECISIONS.md`](file:///D:/01_Projects_and_Code/ghostscreen/DECISIONS.md) before modifying system-level components.

---

## 1. Project Identity, Boundaries & Core Philosophy

1. **What GhostScreen Does**:
   - **Shoulder-Surfing Defense**: Renders a customizable screen overlay with a high-opacity mask or frosted-glass scrim, leaving an adjustable, touch-passthrough clear viewport for private reading in public spaces.
   - **Smart Interception**: Leverages an `AccessibilityService` to automatically deploy or retract privacy overlays when the user enters or leaves designated sensitive applications (e.g., WhatsApp, Signal, banking apps) or transient surfaces.
   - **Sensor Countermeasures**: Detects device tilt (Shoulder-Surfer Tilt Shield) to automatically blank the screen when viewed from acute angles, and detects rapid double-shakes (Panic Shake) to instantaneously blackout the screen, mute audio, and purge the clipboard.
   - **In-App Updater**: Queries GitHub Releases API directly, validates SHA-256 asset checksums, and launches package installer intents via `FileProvider`.

2. **Absolute Privacy Guarantees (Zero-Telemetry Boundary)**:
   - **100% Offline Core**: GhostScreen has zero tracking SDKs, zero crash analytics servers, and zero cloud synchronization.
   - **Network Scope**: The only component permitted to touch network sockets is the optional GitHub Release Checker ([`GitHubReleaseChecker.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/updater/GitHubReleaseChecker.kt)), which only queries public GitHub release tags. It transmits zero telemetry, zero device IDs, and zero usage metrics.
   - **No PII Logging**: Logs must never capture user keystrokes, clipboard text, screen bitmaps, or unmasked third-party package names.

---

## 2. Architecture & Code Standards

### Layer Decoupling
```
┌─────────────────────────────────────────────────────────────┐
│ UI LAYER (Jetpack Compose)                                  │
│ - Single Activity (MainActivity), Navigation, Settings      │
│ - Observes State via Flows; dispatches user intents         │
└──────────────────────────────┬──────────────────────────────┘
                               │ Reads/Writes
┌──────────────────────────────▼──────────────────────────────┐
│ DOMAIN & STATE LAYER (Pure Kotlin / Architecture Components)│
│ - PreferencesManager (plain + encrypted dual storage)       │
│ - Room Database (AppDatabase, AppDao, ProtectedApp)         │
│ - Pure JVM transition algorithms (OverlayTransition.kt)     │
└──────────────────────────────┬──────────────────────────────┘
                               │ Interacts reactively
┌──────────────────────────────▼──────────────────────────────┐
│ SYSTEM & SERVICE LAYER (Android Framework)                  │
│ - GhostScreenAccessibilityService (Window monitoring)       │
│ - GhostScreenOverlay (Direct WindowManager addView/remove)  │
│ - Sensors (TiltSensorManager, ShakeDetector)                │
└─────────────────────────────────────────────────────────────┘
```

1. **UI Layer (Jetpack Compose)**:
   - Enforce **Unidirectional Data Flow (UDF)**. UI screens receive state objects and emit lambdas/events upwards.
   - Never pass system services or database instances deep into composables. Hoist state to top-level viewmodels or screen wrappers.
   - Enforce Material 3 semantic color tokens (`MaterialTheme.colorScheme.*`). Never hardcode hex color values in composables.
   - Keep shapes unified: use pill-shaped and rounded geometries defined in [`Theme.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/theme/Theme.kt).

2. **Overlay Drawing (Android WindowManager)**:
   - **Separation from Compose Activity**: Overlays are drawn directly onto the system `WindowManager` via Android `View` subclasses ([`GhostScreenOverlay.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt)). They must NOT be attached to the Compose Activity hierarchy.
   - **GPU Compositing**: Canvas rendering inside overlays must use hardware-accelerated drawing pipelines. Avoid unnecessary object allocations (`Paint`, `Path`, `RectF`) inside `onDraw()`.
   - **Touch Passthrough**: In Box mode, the viewport area must have **zero overlay windows** so touch events fall through directly to underlying apps without latency.

3. **Lifecycle-Aware Coroutines**:
   - Always bind coroutines to explicit lifecycle scopes (`serviceScope` with `SupervisorJob()`, or `viewModelScope`).
   - Clean up handler runnables and cancel active jobs on detach or service disconnect to prevent memory leaks.

---

## 3. "Do Not Touch Without Context" Registry

> [!CAUTION]
> Before modifying any WindowManager flags, animation utilities, or accessibility event handlers, you MUST consult **[`DECISIONS.md`](file:///D:/01_Projects_and_Code/ghostscreen/DECISIONS.md)**.
>
> High-risk areas protected by architectural invariants:
> 1. **Box Mode Multi-Window composite**: Never replace the 4-corner-handle + boundary mask system with a single fullscreen translucent view.
> 2. **Window Type**: Overlay windows MUST use `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`. Do not switch to `TYPE_APPLICATION_OVERLAY`.
> 3. **Manual Ticker Animations**: Do not replace the ticker coroutines in `ManualAnimUtils.kt` with standard Android `ValueAnimator` (bypasses OS "animations disabled" setting).
> 4. **Dual Frosted Layer**: Retain the procedural noise/tint fallback for low-end MediaTek GPUs alongside `FLAG_BLUR_BEHIND`.
> 5. **Asymmetric SharedPreferences**: Never initialize `EncryptedSharedPreferences` synchronously on the UI main thread.
> 6. **Transient Package Inheritance**: Keep `OverlayTransition.isTransient()` logic so software keyboards and status bars do not cause black flickering.

---

## 4. Diagnostic Logging Standards

All diagnostic logging must comply with **[`LOGGING.md`](file:///D:/01_Projects_and_Code/ghostscreen/LOGGING.md)**:
- Emitted events must use the structured categories:
  - `[OVERLAY_ENGINE]`: Window operations, viewport bounds, orientation updates.
  - `[LIFECYCLE_PERM]`: Permission changes, accessibility connection states.
  - `[UI_STATE]`: User configuration actions, opacity adjustments.
  - `[DEVICE_ENV]`: Device specifications, OEM ROM profile, display metrics.
- Maintain the **Goldilocks Rule**: Detailed enough for an AI agent to fix bugs on the first try, but strictly sanitized against PII, keystrokes, and screen content.
- Use [`DiagnosticLogger.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/DiagnosticLogger.kt) to capture events into the bounded ring buffer and generate the **One-Tap AI Bug Packet**.

---

## 5. Development & Verification CLI Commands

Always run builds with the appropriate JDK and Android SDK paths:
```powershell
$env:GRADLE_USER_HOME="D:\D drive files\.gradle"
$env:ANDROID_HOME="D:\Android\Sdk"
$env:GRADLE_OPTS="-Dorg.gradle.java.home=""D:\D drive files\andorid\jbr"""
```

### Core Gradle Commands
* **Run Unit Tests**:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```
* **Assemble Debug APK**:
  ```powershell
  .\gradlew.bat assembleDebug
  ```
* **Assemble Release APK (Minified/Optimized)**:
  ```powershell
  .\gradlew.bat assembleRelease
  ```
* **Bump Version Build Number**:
  ```powershell
  .\gradlew.bat incrementVersionCode
  ```
* **Bump Version Patch Number**:
  ```powershell
  .\gradlew.bat incrementPatchVersion
  ```

---

## 6. AI Context Handoff Protocol

When ending a session or passing context to another AI model or human contributor, the AI agent MUST output a structured handoff block following this exact template:

```markdown
### AI Context Handoff Summary
- **Current Branch**: `feat/<scope>-<description>`
- **Last Verified Commit**: `<hash>` (clean build & unit tests passing)
- **What Was Completed**:
  - Concise bullet points of implemented features or bug fixes.
- **Architectural Decisions Made**:
  - Any new patterns introduced and their rationale.
- **Pending Edge Cases / Next Steps**:
  - Specific OEM behaviors or remaining test matrices to address.
- **Verification Evidence**:
  - Exact Gradle command executed and output status (`BUILD SUCCESSFUL in Xs`).
  - Device test results or screenshot links (if applicable).
```
