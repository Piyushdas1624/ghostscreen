## Description

### Linked Issue
Fixes #<!-- Insert issue number here -->

### Context & Root Cause
<!-- Explain why this change is necessary. What was broken or missing? -->

### Solution Summary
<!-- Describe what was modified across Compose UI, WindowManager Overlays, or Services. Reference DECISIONS.md if touching architectural invariants. -->

---

## Diagnostic & Verification Evidence

### 1. Build & Unit Test Verification
- [ ] Command executed: `.\gradlew.bat testDebugUnitTest`
- [ ] Status: **BUILD SUCCESSFUL** (0 failing tests)

### 2. Manual Device / Emulator Verification
- Device / Emulator model & Android API tested:
- Tested behaviors:
  - [ ] Box mode touch-passthrough zone remains interactive
  - [ ] Screen orientation rotation (Portrait $\leftrightarrow$ Landscape) aligns correctly
  - [ ] Light & Dark Material 3 theme compliance
  - [ ] Service recovery after low-memory kill / orientation change
  - [ ] Permission revocation handled gracefully

### 3. Screenshots or Diagnostic Traces (if applicable)
<!-- Attach before/after screenshots, ADB screencaps, or paste sanitized Diagnostic Bug Packet logs below -->

---

## Maintainer Quality Checklist

- [ ] My code conforms to the project standards in [`AGENTS.md`](../AGENTS.md) and [`CONTRIBUTING.md`](../CONTRIBUTING.md).
- [ ] I have not removed or violated any architectural invariants in [`DECISIONS.md`](../DECISIONS.md).
- [ ] No telemetry SDKs, analytics endpoints, or unredacted PII logging have been introduced.
- [ ] Commits follow the **Conventional Commits** specification (`feat(...)`, `fix(...)`, etc.).
- [ ] `mapping.txt` and minification compatibility have been considered for release builds.
