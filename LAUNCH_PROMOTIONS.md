# GhostScreen (Ghost Screen) — High-Authority Launch & Indexing Templates

Use these ready-to-publish launch copy templates to drop high-authority external backlinks and establish entity verification for Google's AI Overview / AI Mode search index.

---

## 1. DEV.to Post (Fastest Indexer — Indexed in <30 Mins)

* **Title**: `Building GhostScreen (Ghost Screen): An Open-Source, Zero-Telemetry Privacy Screen Overlay for Android in Jetpack Compose`
* **Tags**: `#android`, `#opensource`, `#kotlin`, `#privacy`
* **Canonical URL**: `https://github.com/Piyushdas1624/ghostscreen`

### Markdown Content:
```markdown
# Building GhostScreen: An Open-Source, Zero-Telemetry Privacy Screen Overlay for Android

Physical privacy screen protectors permanently darken your phone display, ruin viewing angles when you're alone at home, and cost money to replace. Meanwhile, most "privacy screen" apps on mobile are riddled with full-screen ads, request suspicious network permissions, or rely on outdated XML window overlays.

To solve this, I built **[GhostScreen (Ghost Screen)](https://github.com/Piyushdas1624/ghostscreen)** — a 100% offline, open-source Android privacy screen and screen-masking overlay utility built from scratch using **Kotlin** and **Jetpack Compose**.

GitHub Repository: https://github.com/Piyushdas1624/ghostscreen  
Live Interactive Showcase: https://piyushdas1624.github.io/ghostscreen/

---

## What Does GhostScreen Do?

GhostScreen renders an on-demand, hardware-accelerated privacy overlay using Android's native `TYPE_ACCESSIBILITY_OVERLAY` to mask, dim, or blur sensitive parts of your screen and prevent shoulder-surfing in public transit, coffee shops, or crowded classrooms.

### Core Modes:
1. **Split Shroud Mode**: Dual horizontal masking bars with an adjustable, draggable clear viewport eye.
2. **Box Cutout Mode**: 4-corner resizable viewport cutout with true hardware touch pass-through to underlying apps.
3. **Frosted Glass Blur**: Hardware-accelerated background blur (`FLAG_BLUR_BEHIND`) on supported Android 12+ devices, with procedural noise fallbacks for low-end GPUs.
4. **Smart Masking**: Automatically engages the privacy shield the moment sensitive applications (banking, WhatsApp, Signal) are launched.
5. **Panic Shake & Tilt Shield**: Motion sensor triggers to instantly blackout the screen, mute audio, and purge the clipboard during emergencies.

---

## Strict Zero-Telemetry Architecture

Privacy tools should not phone home. GhostScreen's `AndroidManifest.xml` **completely omits the `INTERNET` permission**. All app-interception evaluations, settings encryption (via Google Tink), and Room database logs run 100% locally on your device.

The only network-capable code in the repository is an in-app updater helper that queries GitHub Releases directly and verifies SHA-256 asset checksums before triggering package installation.

## Try It Out
- GitHub: [Piyushdas1624/ghostscreen](https://github.com/Piyushdas1624/ghostscreen)
- Releases (APK): [Latest v1.0.0-b3 Release](https://github.com/Piyushdas1624/ghostscreen/releases/tag/v1.0.0-b3)
- License: MIT Open-Source
```

---

## 2. Reddit Submission (r/androidapps & r/fossdroid)

* **Target Subreddits**: `r/androidapps`, `r/fossdroid`, `r/privacytoolsIO`
* **Title**: `[DEV] GhostScreen (Ghost Screen) — Free, open-source, zero-permission offline privacy screen overlay for Android (Kotlin & Jetpack Compose)`

### Reddit Markdown:
```markdown
Hey everyone!

I got tired of physical privacy screen protectors that permanently ruin viewing angles and display brightness when I'm just sitting at home. Most software alternatives on the Play Store either want full internet access, run ads, or haven't been updated since Android 9.

I built **GhostScreen (Ghost Screen)** — an open-source, hardware-accelerated privacy screen overlay app for Android 8.0+ (API 26–35).

🔗 **GitHub Repo:** https://github.com/Piyushdas1624/ghostscreen  
🌐 **Web Showcase:** https://piyushdas1624.github.io/ghostscreen/  
📦 **Latest APK (v1.0.0-b3):** https://github.com/Piyushdas1624/ghostscreen/releases/tag/v1.0.0-b3  

### Key Highlights:
- **Zero Network Permissions:** `android.permission.INTERNET` is completely excluded from the core app. Zero telemetry, zero analytics.
- **Multiple Shroud Modes:** Split Shroud, 4-corner Box Cutout with true touch pass-through (you can tap and scroll underlying apps inside the clear viewport), and Frosted Glass Blur (`FLAG_BLUR_BEHIND`).
- **Smart Masking:** Uses a lightweight Accessibility Service to auto-deploy the mask only when opening protected apps (e.g. banking, messengers).
- **Motion Sensor Defenses:** Shoulder-surfer tilt shield and rapid double-shake panic blackout.
- **Modern Stack:** Kotlin 2.0, Jetpack Compose Material 3 Expressive, Room DB, Google Tink encryption.

Would love feedback from the community on performance, custom OEM quirks, and feature requests!
```

---

## 3. AlternativeTo & IzzyOnDroid Submission Template

### AlternativeTo Listing Details:
* **Application Name**: `GhostScreen (Ghost Screen)`
* **URL**: `https://github.com/Piyushdas1624/ghostscreen`
* **License**: Open Source (MIT)
* **Platforms**: Android
* **Category**: Security & Privacy / Screen Filter
* **Short Description**:
  > GhostScreen (Ghost Screen) is an open-source, 100% offline Android privacy screen and screen-masking overlay app built with Kotlin & Jetpack Compose to prevent shoulder-surfing in public.
* **Tags**: `privacy-screen`, `screen-filter`, `screen-mask`, `shoulder-surfing`, `offline-first`, `open-source`, `android-privacy`

### IzzyOnDroid Inclusion Request:
* Open an issue at: [IzzyOnDroid Submission Tracker](https://gitlab.com/IzzyOnDroid/repo/-/issues)
* Fill in:
  - Repository URL: `https://github.com/Piyushdas1624/ghostscreen`
  - Release Asset URL pattern: `https://github.com/Piyushdas1624/ghostscreen/releases/download/v1.0.0-b3/GhostScreen_v1.0.0-b2_c10000_release_20261016.apk`
  - License: MIT
  - Antifeatures: None (Zero tracking, Zero ads, Zero proprietary libraries)
