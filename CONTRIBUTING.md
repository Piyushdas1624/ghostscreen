# Contributing to GhostScreen

Thank you for contributing to **GhostScreen**! As an open-source Android privacy tool, maintaining strict code quality, zero-telemetry boundaries, and reproducible build integrity is essential.

All contributors—whether human developers or autonomous AI agents—must follow the guidelines in this document.

---

## 1. Development Environment & Setup

GhostScreen targets Android 15 (API 35) and requires:
* **JDK:** Java 21 (JDK 21 LTS, e.g. OpenJDK or JetBrains Runtime `jbr`)
* **Android SDK:** Platform SDK 35, Build-Tools 35.0.0
* **Gradle:** 8.13 (managed via `./gradlew`)
* **Android Gradle Plugin (AGP):** 8.13.2
* **Kotlin:** 2.0.21 with Jetpack Compose Compiler Plugin 2.0.21

### Local Environment Variables (PowerShell Example)
```powershell
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:GRADLE_OPTS = "-Dorg.gradle.java.home=""D:\path\to\jdk-21"""
```

---

## 2. Git Branching Strategy

> [!IMPORTANT]
> **Never commit directly to `main` or `fable-5`.**  
> All modifications must occur in dedicated, single-purpose topic branches cut from the default branch.

### Branch Naming Conventions
* **Features:** `feat/<scope>-<kebab-case-description>` (e.g., `feat/overlay-pinch-zoom`, `feat/updater-wifi-toggle`)
* **Bug Fixes:** `fix/<issue-id>-<short-description>` (e.g., `fix/issue-12-rotation-misalignment`)
* **Refactoring:** `refactor/<subsystem>` (e.g., `refactor/preferences-flow`)
* **Performance:** `perf/<scope>` (e.g., `perf/compose-recomposition-guard`)
* **Chores / Maintenance:** `chore/<task>` (e.g., `chore/bump-agp-8-14`)
* **Documentation:** `docs/<topic>` (e.g., `docs/logging-pipeline`)

---

## 3. Commit Message Standards (Conventional Commits)

GhostScreen enforces the **Conventional Commits 1.0.0** specification. Automated release changelogs are generated directly from these commit messages.

### Commit Format
```text
<type>(<scope>): <concise description in present tense>

[optional body explaining WHY the change was necessary]

[optional footer: Fixes #123, Closes #456]
```

### Allowed Types
* `feat`: A new user-facing feature or enhancement
* `fix`: A bug fix
* `perf`: A code change that improves performance or reduces battery/memory usage
* `refactor`: A code change that neither fixes a bug nor adds a feature
* `docs`: Documentation updates only
* `test`: Adding missing unit/instrumented tests or correcting existing tests
* `chore`: Build script updates, dependency bumps, or tool configuration

### Rules for Commits
1. **Atomic & Buildable**: Every commit must compile cleanly and pass unit tests independently.
2. **Explain the "Why"**: The commit body should document the problem, context, and rationale, not simply re-state the diff.
3. **Respect Invariants**: If touching system window flags or sensor hooks, reference the corresponding rule in [`DECISIONS.md`](file:///D:/01_Projects_and_Code/ghostscreen/DECISIONS.md).

---

## 4. Pre-Merge Quality Gate & Verification Checklist

Before opening a Pull Request or merging code into the default branch, contributors must verify:

- [ ] **Unit Tests Clean**: All unit tests must pass without warnings:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```
- [ ] **Debug Compilation Clean**: The debug APK must build with 0 compiler errors:
  ```powershell
  .\gradlew.bat assembleDebug
  ```
- [ ] **Privacy Invariant Upheld**: No telemetry SDKs, analytics endpoints, or unredacted PII logging added.
- [ ] **Architectural Invariant Upheld**: No regressions against [`DECISIONS.md`](file:///D:/01_Projects_and_Code/ghostscreen/DECISIONS.md) (e.g., touch passthrough in Box mode preserved, window type remains `TYPE_ACCESSIBILITY_OVERLAY`).
- [ ] **UI Scalability**: Verified layout rendering in both Light and Dark Material 3 color themes.
- [ ] **Diagnostic Logging**: Important state transitions emit structured events via [`DiagnosticLogger`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/util/DiagnosticLogger.kt) using proper tags (`[OVERLAY_ENGINE]`, `[LIFECYCLE_PERM]`, etc.).

---

## 5. Pull Request Process

1. Fork or branch from `fable-5` / `main`.
2. Follow the commit message guidelines.
3. Fill out the [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md) completely, including linked issue numbers and diagnostic verification evidence.
4. CI checks (Gradle assemble, unit tests, static checks) must pass before maintainer review.
