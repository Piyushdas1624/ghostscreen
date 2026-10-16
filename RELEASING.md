# GhostScreen Release Playbook & Release Engineering

This document outlines the end-to-end release process for **GhostScreen**, covering versioning mathematics, R8/ProGuard obfuscation rules, release signing, checksum generation, and distribution readiness for GitHub Releases, F-Droid, and IzzyOnDroid.

---

## 1. Versioning Architecture

GhostScreen adheres to **Semantic Versioning (SemVer 2.0.0)** coupled with an automated build number generator configured in [`version.properties`](file:///D:/01_Projects_and_Code/ghostscreen/version.properties) and [`app/build.gradle.kts`](file:///D:/01_Projects_and_Code/ghostscreen/app/build.gradle.kts).

### Version Calculation Formula
In `app/build.gradle.kts`:
```kotlin
val calculatedVersionCode = (versionMajor * 10000) + (versionMinor * 100) + versionPatch
val calculatedVersionName = "$versionMajor.$versionMinor.$versionPatch-b$versionBuild"
```

| Component | Description | Example |
| :--- | :--- | :--- |
| `VERSION_MAJOR` | Breaking architectural shift, major UI rewrite, or Android minimum API upgrade | `1` |
| `VERSION_MINOR` | New user-facing features (e.g., In-app updater, Panic Shake, Notification Shield) | `0` |
| `VERSION_PATCH` | Bug fixes, performance optimizations, translation updates | `0` |
| `VERSION_BUILD` | Monotonically increasing CI/test build counter | `2` |
| **Resulting `versionName`** | Formatted string | `1.0.0-b2` |
| **Resulting `versionCode`** | Monotonically increasing integer for package managers | `10000` |

### Automated Gradle Tasks
* **Increment Build Counter**:
  ```powershell
  .\gradlew.bat incrementVersionCode
  ```
* **Increment Patch Version**:
  ```powershell
  .\gradlew.bat incrementPatchVersion
  ```

---

## 2. Release Signing Security

> [!CAUTION]
> **NEVER commit release keystores (`*.jks`, `*.keystore`) or plaintext keystore passwords to git.**

### Local Development Signing
For local release builds, configure `local.properties` (which is excluded via `.gitignore`):
```properties
RELEASE_STORE_FILE=C:/secure/keys/ghostscreen-release.jks
RELEASE_STORE_PASSWORD=superSecretStorePassword
RELEASE_KEY_ALIAS=ghostscreen
RELEASE_KEY_PASSWORD=superSecretKeyPassword
```

### GitHub Actions CI/CD Signing
In GitHub Actions pipelines, signing credentials are read from GitHub Repository Secrets:
* `KEYSTORE_BASE64`: Base64-encoded release `.jks` file
* `RELEASE_STORE_PASSWORD`: Keystore master password
* `RELEASE_KEY_ALIAS`: Key alias name
* `RELEASE_KEY_PASSWORD`: Key password

---

## 3. R8 / ProGuard Optimization & `mapping.txt` Archival

When building a minified production release (`assembleRelease`):
1. **R8 Minification**: Strips unused bytecode, optimizes inline methods, and shrinks DEX payload size by 35–50%.
2. **Log Stripping**: Strips verbose and debug Logcat calls via `-assumenosideeffects` rules in `proguard-rules.pro`.
3. **`mapping.txt` Archival (Mandatory)**:  
   Every release produces a ProGuard mapping file at:
   ```
   app/build/outputs/mapping/release/mapping.txt
   ```
   **This file MUST be archived as a release artifact.** Without `mapping.txt`, any obfuscated crash stack trace uploaded by a user (e.g., `at com.etaegord.ghostscreen.a.b(Unknown Source)`) cannot be de-obfuscated using Android's `retrace` tool.

---

## 4. SHA-256 Checksum Verification

Every released APK must have a companion `.sha256` checksum file attached to its GitHub Release.

### Generating Checksums
```powershell
# PowerShell
Get-FileHash -Algorithm SHA256 "app/build/outputs/apk/release/GhostScreen_v1.0.0-b2_c10000_release_*.apk" | ForEach-Object { "$($_.Hash)  $((Get-Item $_.Path).Name)" } | Out-File -Encoding utf8 "GhostScreen_v1.0.0-b2.apk.sha256"
```

The GhostScreen in-app updater ([`ChecksumVerifier.kt`](file:///D:/01_Projects_and_Code/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/updater/ChecksumVerifier.kt)) downloads this `.sha256` file automatically and validates the payload before launching the package installer intent.

---

## 5. F-Droid & IzzyOnDroid Reproducible Builds

GhostScreen is designed for first-class distribution on F-Droid and IzzyOnDroid. To comply with F-Droid build server policies:
1. **Zero Proprietary Blobs**: GhostScreen contains no proprietary Google Play Services (GMS) binaries.
2. **Deterministic Build Process**: No dynamic timestamps or non-deterministic file ordering inside the APK compilation pipeline.
3. **F-Droid Metadata File**: Provide `metadata/com.etaegord.ghostscreen.yml` with clear source repo mapping, tags, and Gradle build instructions.

---

## 6. Step-by-Step Release Checklist

1. **Verify Quality Gates**:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
2. **Bump Version**: Run `incrementPatchVersion` or update `version.properties`.
3. **Commit & Tag**:
   ```bash
   git commit -am "chore(release): prepare v1.0.0-b3"
   git tag -a v1.0.0-b3 -m "Release v1.0.0-b3"
   git push origin fable-5 --tags
   ```
4. **Build Release APK**:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
5. **Generate Checksums & Draft GitHub Release**:
   - Attach the signed APK (`GhostScreen_v1.0.0-b3_*.apk`).
   - Attach the `.sha256` checksum file.
   - Attach the R8 `mapping.txt` file (or upload to CI release artifacts).
   - Paste release notes formatted with feature changelogs and bug fixes.
