# GhostScreen v1.0.0-b2 (Build 10000)

### What's New
- **In-App Updater Engine**: Automated semantic version checks against GitHub Releases API (`/repos/{owner}/{repo}/releases/latest`).
- **Mid-Stream Resilient Downloader**: Download updates with real-time percentage/MB progress tracking and automatic mid-stream mobile data prevention if Wi-Fi drops.
- **SHA-256 Checksum Integrity**: Automatic verification of downloaded APKs against release body or `.sha256` asset hashes before package installation.
- **Package Installer Integration**: Seamless Android `FileProvider` intent trigger with graceful handling of `REQUEST_INSTALL_PACKAGES` permission.
- **Material 3 Update UI**:
  - App Updates section in Settings with current version badge.
  - "Auto-check for updates" & "Download updates over Wi-Fi only" toggles.
  - "Check for Updates" button with inline loading indicator.
  - Non-intrusive cold-start update banner on Home Screen (never hijacks overlay toggling).
  - Rich Update Dialog featuring release title, formatted markdown changelog, publish date chip, APK size badge, and progress indicator.
- **Background Periodic Worker**: Lightweight WorkManager periodic checks posting system notifications with deep-link extras.

### Verification & Checksums
- **Artifact**: `GhostScreen_v1.0.0-b2_c10000_debug_20261015.apk`
- **File Size**: `21,999,810 bytes` (21.0 MB)
- **SHA-256**: `10c95ab45290346d6ccfc7a3a219fd38e5e2801302dbf5f6309fc5d79e1c0a71`
- **Unit Tests**: 65/65 passed clean.
