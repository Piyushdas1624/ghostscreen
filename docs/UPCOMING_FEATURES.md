# GhostScreen: Upcoming Features Architectural Specification & Technical Feasibility Report

> **Document Version**: 2.0  
> **Target Platform**: Android 10 - 15 (API 29 - 35)  
> **Status**: Design & Prototype Phase (Validation Lab: `showcase/index.html`)  
> **Codebase Target**: `E:\01_Projects_and_Code\ghostscreen` (Branch `fable-5`)

---

## Executive Summary

This document establishes the technical architecture, hardware feasibility, failure modes, and mitigation strategies for upcoming GhostScreen privacy features. Each feature has been evaluated against real-world Android OS constraints—specifically Android 12+ camera privacy indicators, Binder IPC latency, PenTile display physics, and gesture navigation timings.

---

## 1. Feature Specifications & Real-World Feasibility Analysis

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       UPCOMING FEATURE PORTFOLIO                            │
├──────────────────────────────┬──────────────────────────────┬───────────────┤
│ Feature                      │ Core Mechanism               │ Complexity    │
├──────────────────────────────┼──────────────────────────────┼───────────────┤
│ 1. Gaze Guard (Peeker Sentry)│ CameraX + ML Kit Face (0.5Hz)│ High (OS-Gated│
│ 2. Decoy PIN Touch Scrambler │ Multi-Point Canvas Decoy     │ Medium        │
│ 3. Anti-OCR Moiré Watermark  │ High-Frequency Spatial Grid  │ Medium        │
│ 4. Recents Shield (Camouflage│ Navigation Inset Touch-Gating│ High          │
│ 5. Spotlight Torch Mode      │ Annulus Ring Passthrough     │ Low-Medium    │
│ 6. Gyroscope Shoulder Shield │ 6-DoF Sensor Fusion + Kalman │ Medium        │
│ 7. AI Field Auto-Shielding   │ Leaf-Node Scoped Gating      │ High (IPC)    │
│ 8. Micro-Louver Shader       │ 45° Pre-rendered BitmapShader│ Low           │
│ 9. Panic Double-Shake        │ Jerk-Vector Differentiation  │ Low           │
└──────────────────────────────┴──────────────────────────────┴───────────────┘
```

---

### Feature 1: Front-Camera Peeker Sentry (`Gaze Guard`)

#### Conceptual Objective
Continuously or periodically inspects the user's environment via the front-facing camera. If a secondary human face is detected peering over the shoulder, GhostScreen triggers two discrete haptic warning pulses and deploys an instant privacy veil over the top 70% of the screen.

#### Technical Architecture
- **Framework**: `CameraX 1.3+` (`ImageAnalysis.Builder`) backed by `Google ML Kit Face Detection` (`FaceDetectorOptions.PERFORMANCE_MODE_FAST`).
- **Cadence**: Low-frequency polling (1 frame every 2000ms = 0.5 FPS) on an isolated background `HandlerThread`.
- **Classification Filter**: A secondary face must satisfy:
  1. Euler Y (head turn angle) $\le \pm 25^\circ$ (facing the screen).
  2. Bounding box area between 15% and 60% of primary user face (positioned behind the user).
  3. Distance separation $> 30\text{cm}$ in depth estimation.

#### Critical Problems & Real-World Pitfalls

| # | Problem | Root Cause | Impact on User |
|---|---|---|---|
| **1.1** | **The Green Privacy Indicator Trap** | Starting in Android 12 (API 31), the OS permanently renders a bright green camera dot in the top-right status bar whenever *any* app accesses camera hardware. | A privacy app that causes a permanent or blinking green camera dot makes users anxious and paranoid that GhostScreen is spyware. |
| **1.2** | **Camera Hardware Lockouts** | Android camera hardware is typically exclusive single-client. If the user receives a WhatsApp video call or opens Instagram/Camera, CameraX throws `CameraAccessException(CAMERA_IN_USE)`. | Crashes or prevents legitimate apps from opening the camera. |
| **1.3** | **Battery & Thermal Throttling** | Initializing the camera HAL, ISP (Image Signal Processor), and NPU/DSP pipeline—even at 0.5 FPS—prevents the SoC from entering deep sleep states (`C-states`). | Consumes 8%–14% additional battery per hour. |
| **1.4** | **Low-Light / Transit Failures** | In dimly lit trains or subways, front-camera sensor gain spikes, introducing high ISO noise that causes ML Kit face confidence to drop below detection thresholds. | Complete failure to detect peeking in the exact environment where shoulder-surfing occurs most. |

#### Mitigation & Engineering Strategy
1. **Targeted App Binding**: NEVER run camera polling globally. Gaze Guard must only arm inside designated *Ultra-Secure Apps* (e.g. Banking, Crypto, Vaults) and terminate immediately upon app exit.
2. **Camera Priority Yielding**: Register `CameraManager.AvailabilityCallback`. The millisecond another app requests the camera, GhostScreen releases the camera session in $< 50\text{ms}$.
3. **Ultra-Low Resolution Stream**: Request `Size(320, 240)` `YUV_420_888` grayscale buffer to minimize memory bandwidth and ISP power.
4. **Transparent User Explainer**: Display an onboarding dialog: *"GhostScreen uses the front camera to detect onlookers. You will see Android's green privacy indicator active while protected apps are open."*

---

### Feature 2: Decoy PIN Touch Scrambler

#### Conceptual Objective
When typing a banking PIN, passcord, or master password, GhostScreen intercepts the user's touch and simultaneously renders 2–4 randomized dummy touch ripple circles across the keypad, preventing nearby bystanders or overhead surveillance cameras from identifying the entered digits.

#### Technical Architecture
- **Rendering Layer**: Hardware-accelerated Canvas inside `MaskView` (single-pass GPU invalidation).
- **Ripple Dynamics**: Expanding circle vector animations ($r: 8\text{dp} \to 36\text{dp}$, alpha: $0.8 \to 0.0$ over $220\text{ms}$).
- **Randomizer**: Generates coordinates within the keypad bounding box matching grid cell centroids.

#### Critical Problems & Real-World Pitfalls

| # | Problem | Root Cause | Impact on User |
|---|---|---|---|
| **2.1** | **User Visual Disorientation** | When the user taps '5', and three identical ripple effects erupt on '1', '7', and '9', the user's peripheral vision is confused. | User loses confidence in their own typing ("Did I misclick?"), leading to mistyped PINs and account lockouts. |
| **2.2** | **Keyboard Process Isolation (Gboard / IME)** | Standard system keyboards (Gboard, Samsung Keyboard) run inside an isolated `InputMethodService` window (`TYPE_INPUT_METHOD`). | An accessibility overlay cannot know the internal button coordinates of Gboard without invasive layout scraping. Works cleanly only on in-app custom numpads. |
| **2.3** | **Video Slow-Motion De-Scrambling** | If decoy ripples appear on the exact same millisecond as the real touch, an attacker reviewing a high-FPS recording can correlate the real finger flesh compression against the screen. | Security failure against 60fps/120fps video recording. |

#### Mitigation & Engineering Strategy
1. **Perceptual Differentiation**:
   - The **real touch** receives immediate physical haptic feedback (`VibrationEffect.EFFECT_CLICK`) and a subtle inner core dot.
   - The **decoy ripples** have zero haptic feedback and an open halo ring.
2. **Temporal Micro-Jitter ($\Delta t = \pm 15\text{ms}$)**:
   - Decoy ripples fire with a randomized Gaussian delay of $10\text{ms} - 35\text{ms}$ before and after the real touch, breaking synchronous video correlation attacks.
3. **Custom In-App Keypad Detection**:
   - Limit scrambler activation to detected numeric grid layouts (standard 3x4 dialer/PIN pads in banking apps) identified via accessibility node bounds.

---

### Feature 3: Anti-OCR Moiré Watermark Noise

#### Conceptual Objective
Superimposes a microscopic, high-frequency diagonal steganographic grid over sensitive text elements. The grid is transparent to the human eye at normal reading distance, but generates massive optical aliasing (moiré fringe patterns) when photographed by smartphone camera sensors or scanned by OCR engines.

#### Technical Architecture
- **Grid Geometry**: $45^\circ$ alternating lines with a pitch of $p = 2\text{px}$ (1px opaque, 1px transparent) rendered via a repeating `BitmapShader`.
- **Contrast Modulation**: Alpha level modulated between 12% and 28% depending on background luminance.

#### Critical Problems & Real-World Pitfalls

| # | Problem | Root Cause | Impact on User |
|---|---|---|---|
| **3.1** | **Modern Computational AI De-Moiré** | Modern smartphone cameras (iPhone, Google Pixel, Galaxy S-series) use multi-frame RAW fusion and deep learning ISP filters designed specifically to detect and eradicate moiré patterns. | Advanced cameras bypass static spatial grids. |
| **3.2** | **OLED PenTile Diamond Subpixel Fringing** | Most modern OLED displays do not use linear RGB stripes; they use Diamond PenTile matrices (2 green subpixels per red/blue). Static diagonal single-pixel lines cause high-contrast chromatic aberration (color fringing). | Text appears blurry with rainbow colored fringes to the phone's owner. |
| **3.3** | **User Visual Fatigue** | Superimposing high-frequency patterns over text degrades Weber contrast by 25%–35%. | Eye strain and headaches during prolonged reading sessions. |

#### Mitigation & Engineering Strategy
1. **Dynamic Temporal Alternation (60Hz / 120Hz)**:
   - Instead of a static spatial pattern, invert the phase of the grid on every VSYNC frame ($60\text{Hz}$ or $120\text{Hz}$).
   - **Human Persistence of Vision**: The human eye integrates the two opposite phases into a smooth, slightly dimmed uniform tint.
   - **Rolling-Shutter CMOS Cameras**: Camera sensors capture discrete line-by-line exposures ($1/60\text{s} - 1/1000\text{s}$), creating catastrophic black-and-white horizontal tear lines across photos.
2. **PenTile-Aware Geometry**: Use $2.5\text{dp}$ line widths with smooth Gaussian antialiasing rather than hard 1px subpixel cuts to prevent chromatic fringing.

---

### Feature 4: Task-Switcher Camouflage (`Recents Shield`)

#### Conceptual Objective
Instantly blinds or camouflages the screen the moment the user initiates a swipe-up gesture to enter the Android Recents/app switcher, preventing banking balances or private chats from appearing in the system multitasking overview.

#### Technical Architecture
- **Trigger**: Intercepts the bottom navigation bar gesture inset touch events.
- **Action**: Invalidate `MaskView` to draw a solid 100% black/frosted privacy cover before the OS takes the task snapshot.

#### Critical Problems & Real-World Pitfalls

| # | Problem | Root Cause | Impact on User |
|---|---|---|---|
| **4.1** | **Late Snapshot Race Condition** | In Android 12+, the system gesture navigation transition begins inside `system_server` before an Accessibility Service receives `TYPE_WINDOW_STATE_CHANGED`. | By the time accessibility gets notified, Android has already snapshotted the unmasked screen for the Recents card. |
| **4.2** | **App `FLAG_SECURE` Collision** | Financial apps that already implement `WindowManager.LayoutParams.FLAG_SECURE` will reject screenshot captures natively, resulting in a black card regardless. | Redundant processing for apps that already protect themselves natively. |

#### Mitigation & Engineering Strategy
1. **Pre-Emptive Bottom Inset Touch Strip**:
   - Register a microscopic 4dp touchable window at the bottom edge of the display (`Gravity.BOTTOM`).
   - When an `ACTION_DOWN` occurs in the gesture bar area, immediately apply the blackout shield *before* the gesture navigation threshold is crossed.
2. **Fallback to `TYPE_WINDOWS_CHANGED`**:
   - Monitor `AccessibilityEvent.TYPE_WINDOWS_CHANGED` with `WINDOW_CACHE` enabled to capture window transition start signals $< 16\text{ms}$.

---

## 2. Updated Master Comparison & Viability Matrix

| Feature | Primary Threat Model | Battery Impact | Implementation Risk | Verdict |
|---|---|---|---|---|
| **Box Mode (Enhanced)** | Overhead & side bystanders | 0.0% | Low | **Production Ready** |
| **Split Slit Mode** | Crowded transit / bus reading | 0.0% | Low | **Production Ready** |
| **Spotlight Torch (Annulus)** | Surgical micro-inspection | <0.1% | Low-Medium | **Recommended for v1.1** |
| **Gyroscope Tilt Blackout** | Rapid shoulder-surfer glance | 0.2% | Medium | **Recommended for v1.1** |
| **AI Field Micro-Shielding** | Credential/balance exposure | 0.3% - 0.5% | Medium-High | **Recommended for v1.2** |
| **Panic Double-Shake** | Physical device snatch / confiscation | 0.1% | Low | **Recommended for v1.1** |
| **Decoy PIN Scrambler** | Over-the-shoulder PIN recording | <0.1% | Medium | **Viable for Custom Pads** |
| **Task-Switcher Camouflage** | Multitasking snapshot exposure | <0.1% | Medium | **Recommended for v1.1** |
| **Gaze Guard (Camera Sentry)**| Sneak peeking from behind | 8.0% - 14.0%| Very High (OS Indicator) | **Opt-In Experimental** |
| **Anti-OCR Moiré Watermark** | Smartphone camera photography | 0.2% | High (PenTile Aliasing) | **Experimental Prototype** |

---

## 3. Interactive Validation Lab: `showcase/index.html`

All 9 features and their corresponding failure modes/mitigations have been modeled interactively in the web validation lab:
- **Location**: [`E:\01_Projects_and_Code\ghostscreen\showcase\index.html`](file:///E:/01_Projects_and_Code/ghostscreen/showcase/index.html)
- **Controls Available**:
  - Live Gaze Guard simulator with shoulder-surfer detection toggle and green privacy dot indicator.
  - Interactive PIN Scrambler with synchronous multi-ripple decoy generation.
  - Steganographic Anti-OCR Moiré toggle with camera lens sensor simulator.
  - Interactive Recents Camouflage gesture simulator.
  - 3D Gyroscope tilt slider with $40^\circ$ blackout threshold.
  - Spotlight Torch beam pointer tracking and interactive radius slider.
