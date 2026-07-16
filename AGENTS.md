# Agent Instructions & Project Guardrails: GhostScreen

> **Recommended reading order for a cold-start session:**
> 1. [PROJECT_MANIFEST.md](file:///d:/ghostscreen/PROJECT_MANIFEST.md) — vision, status, architecture decisions, known limitations
> 2. [docs/10_AI_CONTEXT.md](file:///d:/ghostscreen/docs/10_AI_CONTEXT.md) — exact code patterns, ADB debugging, what must not change
> 3. This file — build commands, commit workflow, coding conventions
> 4. The specific source file(s) relevant to the task

This file is the system instruction manual for AI coding assistants (Claude Code, Gemini CLI, and agentic IDE plugins) working on **GhostScreen**. Do not modify source code before completing steps 1–3 above.

---

## 1. Project Goals & Vision
- **Shoulder-Surfing Defense**: Native Android overlay that conceals the screen with a semi-transparent mask, leaving a customizable and movable clear viewport for private reading.
- **Dynamic Smart Interception**: Background Accessibility Service that automatically draws the mask overlay when entering user-configured apps (WhatsApp, banking, etc.) or transient system components (Gboard keyboard, notifications panel).
- **100% Local Privacy**: Absolute offline architecture. No analytics, tracking SDKs, or cloud connections. All data resides securely in a local Room database and encrypted settings.
- **Non-Distributive Sideloading**: Geared for F-Droid and sideloaded APK releases (no Google Play Store publishing constraints).

---

## 2. Core Architecture Rules
1. **Separation of Layers**:
   - **UI Layer**: Compose-based single activity ([MainActivity](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt)).
   - **Service Layer**: System-level components ([GhostScreenAccessibilityService](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt)).
   - **Logical Layer**: Pure, framework-free Kotlin engines ([OverlayTransition](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OverlayTransition.kt), [OpacityCurve](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/OpacityCurve.kt)) that can be tested on the JVM.
2. **Overlay Drawing**: Views are added directly to the system `WindowManager` inside [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt). They do not run inside the Compose activity context.
3. **Reactive Settings Configuration**: App components must communicate status and preference updates reactively through `PreferencesManager` listeners rather than direct binding or custom broadcasts.

---

## 3. What Must NEVER Be Changed
- **Box Mode Touch-Passthrough Zone**: In Box mode, the center cutout must remain free of overlay windows. The overlay must be composed of 4 separate corner handle windows and 4 boundary mask windows. Do not use a single fullscreen transparent window, as it will block touches to the underlying application.
- **System Overlay Window Flag**: Overlay windows must be registered with `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`. Do not change this to `TYPE_APPLICATION_OVERLAY`, as it will block drawing over the status bar, system keyboard (Gboard), and notification drawer.
- **Bypassed Animation System**: Manual coroutine ticker-loops in [ManualAnimUtils.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/utils/ManualAnimUtils.kt) must not be replaced with Compose animation components. They ensure screen masks animate smoothly even when users disable system-level animations.

---

## 4. Coding Conventions & Preferred Patterns
- **Material 3 Semantic Styling**: Never use raw hex color strings (e.g. `#FF0000`) in UI code. Use M3 semantic color scheme values (e.g. `MaterialTheme.colorScheme.primaryContainer`).
- **Dynamic Themes**: Ensure any card or surface uses matching color and on-color pairs (e.g., `primaryContainer` with `onPrimaryContainer`) to support light and dark modes.
- **Pill Geometry**: Standardize card and chip corners using the shape mappings defined in [Theme.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/theme/Theme.kt).
- **GPU Compositing**: Perform Compose animations using `graphicsLayer` modifiers to run operations directly on the GPU compositor, avoiding constant UI recompositions.

---

## 5. Development CLI Commands

### 5.1 Build Commands
- **Compile Debug APK**:
  ```powershell
  ./gradlew assembleDebug
  ```
- **Compile Optimized Release APK**:
  ```powershell
  ./gradlew assembleRelease
  ```

### 5.2 Test Commands
- **Run Unit Tests**:
  ```powershell
  ./gradlew testDebugUnitTest
  ```
- **Run Instrumented Device Tests**:
  ```powershell
  ./gradlew connectedAndroidTest
  ```

---

## 6. Commit & Implementation Workflow
- **Commit Granularity**: Suggest modifications in small, phased, single-focus commits. Refer to the phased roadmap in [docs/08_REFACTORING_PLAN.md](file:///d:/ghostscreen/docs/08_REFACTORING_PLAN.md).
- **Verification Rule**: Compile and run the unit test suite after each change:
  ```powershell
  ./gradlew testDebugUnitTest
  ```
- **Ponytail (Lazy Dev) Rule**: Respect the lazy senior developer guidelines:
  1. YAGNI: Do not add abstractions, libraries, or boilerplate unless requested.
  2. Native Platform First: Prefer standard library features over external dependencies.
  3. Simple over Clever: Keep code concise and easy to maintain.
  4. Shortcut Transparency: Mark shortcuts with a `ponytail:` comment describing constraints and upgrade paths.
