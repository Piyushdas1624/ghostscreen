# GhostScreen Diagnostic Logging Architecture & Pipeline

> **PRIVACY INVARIANT:** GhostScreen is an open-source privacy and screen-masking utility.  
> The application must **NEVER** collect, persist, or transmit personally identifiable information (PII), screen content/pixels, keystrokes, contact details, or names of unconfigured private applications.

---

## 1. Architectural Gap Analysis: Current vs. Required Logging

Prior to this specification, GhostScreen had two decoupled logging elements:
1. **`CrashLogger.kt`**: Catches unhandled thread crashes (`Thread.setDefaultUncaughtExceptionHandler`) and writes stack traces to `Downloads/ghostscreen logs`. While helpful for fatal crashes, it provides zero breadcrumbs leading up to non-fatal window state bugs, race conditions, or touch-passthrough failures.
2. **`AuditLog.kt` / `AuditLogScreen.kt`**: A Room database table recording blocked snoop attempts for the end user ("Blocked snoop attempt on com.whatsapp"). This is a user-facing security feature, not a developer diagnostic tool.

### The Missing Piece
Maintainers and AI coding agents diagnosing Android overlay issues require a **lightweight, in-memory, privacy-sanitized ring buffer** capturing the last 50 state transitions across window management, accessibility focus events, sensor triggers, and permissions.

---

## 2. Structured Log Taxonomy

Every diagnostic event emitted by GhostScreen must follow this standard format:
```text
[TAG] [LEVEL] Message | {key=value, key=value}
```

### Taxonomy Categories

| Category Tag | Subsystem | What It Captures | Example Event |
| :--- | :--- | :--- | :--- |
| `[OVERLAY_ENGINE]` | Window Manager & Views | Window attachment/detachment, layout flag updates, screen orientation recalculations, touch pass-through coordinates, viewport rects. | `[OVERLAY_ENGINE] [INFO] Mode switched to BOX | {w=1080, h=2400, orientation=PORTRAIT, handles=4}` |
| `[LIFECYCLE_PERM]` | System Binding & Permissions | Overlay permission state (`SYSTEM_ALERT_WINDOW`), accessibility binding status, foreground service start/stop reasons, OEM background kills. | `[LIFECYCLE_PERM] [INFO] Accessibility connected | {armed=true, manual=false, overlay_granted=true}` |
| `[UI_STATE]` | Compose & Preferences | Mask opacity changes, color profile selection, sensor thresholds, user toggle events (sanitized). | `[UI_STATE] [DEBUG] Opacity adjusted | {displayPct=65%, realAlpha=0.72, hardwareBlur=true}` |
| `[DEVICE_ENV]` | Hardware & OS Context | Device manufacturer, model, SoC/GPU profile, Android API level, screen density, window cross-blur capability. | `[DEVICE_ENV] [INFO] Snapshot | {oem=Infinix, model=X6815D, api=35, density=2.75, blur_support=false}` |

---

## 3. The Goldilocks Calibration: Actionable vs. Privacy-Safe

Logs must provide sufficient granularity to reproduce state bugs on the first try while guaranteeing absolute privacy:

### What MUST Be Logged
* Relative layout coordinates (`x=120, y=450, w=840, h=600`) of the clear viewport.
* Android WindowManager layout flags (`FLAG_NOT_TOUCHABLE`, `FLAG_LAYOUT_IN_SCREEN`, etc.).
* Screen orientation shifts (Portrait $\leftrightarrow$ Landscape).
* Sensor triggers (Tilt angle exceeding threshold, Shake detector RMS acceleration).
* Update lifecycle states (Check started, SHA-256 match, installer intent dispatched).

### What MUST NEVER Be Logged (Strict Privacy Redaction)
* **Screen Pixels or Image Buffers**: Never capture screenshots or canvas bitmaps in diagnostic logs.
* **Sensitive Window Content**: Never read `AccessibilityNodeInfo` text contents, passwords, or input values.
* **Raw Third-Party Package Names**: Third-party package names must be categorized (e.g., `APP_PROTECTED`, `APP_UNPROTECTED`, or hashed `pkg_sha256:7f3a...`). Only system framework packages (`com.android.systemui`, `com.google.android.inputmethod.latin`, `android`) may appear in plain text.
* **Clipboard Contents**: Panic double-shake purges the clipboard; logs record only `[OVERLAY_ENGINE] [WARN] Panic shake: Clipboard purged`, never the copied text.

### Build-Time R8/ProGuard Stripping
In release builds (`app/build.gradle.kts`), raw `android.util.Log.v()` and `Log.d()` statements are stripped by R8 rules:
```proguard
# Strip verbose and debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
```
Sanitized `INFO`, `WARN`, and `ERROR` breadcrumbs remain in the in-memory ring buffer for diagnostic export.

---

## 4. The "One-Tap AI Bug Packet" Protocol

To enable instant troubleshooting by AI coding assistants or maintainers, GhostScreen implements an in-app **AI Bug Packet Generator** ([`DiagnosticLogger.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/DiagnosticLogger.kt)).

When a user taps **"Copy Diagnostic Report"** in Settings $\rightarrow$ Diagnostics, GhostScreen generates a pre-formatted Markdown code block directly onto the clipboard:

```markdown
<!-- GHOSTSCREEN_AI_BUG_PACKET_V1 -->
### Architectural Index (.agents context header)
- Overlay Engine: `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt`
- Accessibility Interceptor: `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt`
- Preferences & State: `app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt`
- Invariants & OEM Quirks: `DECISIONS.md`

### System Environment
- GhostScreen: v1.0.0-b2 (versionCode 10000)
- Device: Infinix X6815D (Infinix / MT6769)
- Android OS: Android 14 (API 34)
- Display: 1080x2400 @ 2.75x (Density DPI: 440)
- Cross-Window Blur Supported: false (Fallback scrim active)
- Overlay Permission: GRANTED
- Accessibility Service: CONNECTED

### Recent State Trace (Ring Buffer - Last 25 Events)
2026-10-16 12:15:02.102 [DEVICE_ENV] [INFO] App initialized
2026-10-16 12:15:05.412 [LIFECYCLE_PERM] [INFO] Accessibility connected | armed=true
2026-10-16 12:15:06.120 [OVERLAY_ENGINE] [INFO] Window attached: MaskView (TYPE_ACCESSIBILITY_OVERLAY)
2026-10-16 12:15:06.124 [OVERLAY_ENGINE] [INFO] Attached 4 corner handles + opacity pill
2026-10-16 12:15:10.880 [UI_STATE] [INFO] Viewport dragged | {x=150, y=600, w=780, h=520}
2026-10-16 12:15:14.301 [OVERLAY_ENGINE] [WARN] Orientation changed to LANDSCAPE (2) -> Recalculating bounds
<!-- END_GHOSTSCREEN_AI_BUG_PACKET -->
```

### Why This Format Matters
1. **Zero Cold-Start Prompt Overhead**: The `.agents` context header tells any AI agent which exact Kotlin source files govern the failing subsystem.
2. **Deterministic Root-Cause Pinpointing**: An AI model can follow the timestamped state transitions to spot race conditions or missing flag updates on the first attempt.
3. **User Confidence**: Users can preview the plain-text markdown before posting to GitHub, confirming zero personal data is leaked.
