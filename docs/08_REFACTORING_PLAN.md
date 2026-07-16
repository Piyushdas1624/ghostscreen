# Phased Refactoring Plan: GhostScreen

This document provides a commit-by-commit refactoring roadmap to prepare [GhostScreen](file:///d:/ghostscreen) for production. It prioritizes stability and concurrency fixes before performing structural cleanups and optimization.

---

## Refactoring Roadmap Overview

```
Phase 1 (Stability & Concurrency)
   ├── Commit 1: Fix PreferencesManager Startup Sync
   └── Commit 2: Fix Service Coroutine Race Condition
Phase 2 (UX, Layout, & Memory Cleanup)
   ├── Commit 3: Delete Unused Root Files & Clean Broadcasts
   ├── Commit 4: Add View Detached Callback Resource Cleanup
   └── Commit 5: Add Orientation Change Layout Relayout
Phase 3 (Architecture & DI Refactoring)
   ├── Commit 6: Abstract Database & Prefs behind Repositories
   └── Commit 7: Integrate Hilt Dependency Injection Framework
Phase 4 (Performance & Production Tuning)
   ├── Commit 8: Optimize LazyColumn Bitmap Loading & Search
   └── Commit 9: Enable R8/Proguard Minification & Rules
```

---

## Phase 1: Core Concurrency & Concurrency Fixes (Immediate)

### Commit 1: Fix PreferencesManager Startup Sync
- **Description**: Add plain preferences synchronization inside the background loading block in [PreferencesManager.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt).
- **Implementation**:
  ```kotlin
  // Inside the init block scope, right before assigning encryptedPrefs = ePrefs:
  val editor = ePrefs.edit()
  plainPrefs.all.forEach { (key, value) ->
      if (key != KEY_MIGRATED) {
          when (value) {
              is Boolean -> editor.putBoolean(key, value)
              is Int -> editor.putInt(key, value)
              is Float -> editor.putFloat(key, value)
              is String -> editor.putString(key, value)
              is Long -> editor.putLong(key, value)
          }
      }
  }
  editor.putBoolean(KEY_MIGRATED, true)
  editor.apply()
  encryptedPrefs = ePrefs
  ```
- **Estimated Risk**: Low
- **Estimated LOC Changed**: ~20 lines
- **Safety Boundary**: Verify settings changed immediately on boot persist after a full restart.

### Commit 2: Fix Service Coroutine Race Condition
- **Description**: Convert `checkCurrentApp` in [GhostScreenAccessibilityService.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt) to a suspending function.
- **Implementation**:
  ```kotlin
  // In onAccessibilityEvent:
  pendingCheckJob?.cancel()
  pendingCheckJob = serviceScope.launch {
      delay(150)
      checkCurrentApp(packageName) // Suspending call
  }

  // Signature:
  private suspend fun checkCurrentApp(packageName: String) {
      val isTransient = OverlayTransition.isTransient(packageName)
      val effectivePackage = if (isTransient) lastActiveAppPackage else packageName
      val shouldProtect = if (effectivePackage != null) {
          withContext(Dispatchers.IO) {
              db.appDao().isAppProtected(effectivePackage)
          }
      } else false
      // ... decision rules ...
  }
  ```
- **Estimated Risk**: Low-Medium
- **Estimated LOC Changed**: ~45 lines
- **Safety Boundary**: Switch foreground apps rapidly (e.g. 5 switches in 2 seconds) and check overlay status.

---

## Phase 2: UX, Layout, & Memory Cleanup (Short-Term)

### Commit 3: Delete Unused Root Files & Clean Broadcasts
- **Description**: Remove `rewrite.kt`, `temp.txt`, and `logcat.txt` from the project root. Remove the unused `com.etaegord.ghostscreen.EVALUATE_STATE` broadcast actions.
- **Estimated Risk**: None
- **Estimated LOC Changed**: -800 lines (deletion)

### Commit 4: Add View Detached Callback Resource Cleanup
- **Description**: Add `onDetachedFromWindow()` to view classes in [GhostScreenOverlay.kt](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt) to cancel pending runnables.
- **Implementation**:
  ```kotlin
  // Inside StripView class:
  override fun onDetachedFromWindow() {
      super.onDetachedFromWindow()
      mainHandler.removeCallbacks(idleRun)
      mainHandler.removeCallbacks(longRun)
  }

  // Inside OpacityDropView class:
  override fun onDetachedFromWindow() {
      super.onDetachedFromWindow()
      mainHandler.removeCallbacks(idleRun)
      mainHandler.removeCallbacks(hideLbl)
      mainHandler.removeCallbacks(longRun)
  }
  ```
- **Estimated Risk**: Low
- **Estimated LOC Changed**: ~15 lines

### Commit 5: Add Orientation Change Layout Relayout
- **Description**: Listen to configuration changes and recalculate dimensions.
- **Implementation**:
  ```kotlin
  // In GhostScreenAccessibilityService:
  override fun onConfigurationChanged(newConfig: Configuration) {
      super.onConfigurationChanged(newConfig)
      overlay?.onScreenConfigurationChanged()
  }

  // In GhostScreenOverlay:
  fun onScreenConfigurationChanged() {
      if (!isShowing) return
      updateScreenDimensions()
      mainHandler.post {
          if (overlayMode == Mode.SPLIT) updateStripLayouts()
          else updateCornerPositions()
      }
  }
  ```
- **Estimated Risk**: Medium
- **Estimated LOC Changed**: ~40 lines

---

## Phase 3: Architecture & DI Refactoring (Mid-Term)

### Commit 6: Abstract Database & Prefs behind Repositories
- **Description**: Create Repository layers to isolate raw DAO and preference operations.
- **Implementation**:
  ```kotlin
  interface AppsRepository {
      fun getProtectedApps(): Flow<List<ProtectedApp>>
      suspend fun isAppProtected(packageName: String): Boolean
      suspend fun protectApp(packageName: String, appName: String)
      suspend fun unprotectApp(packageName: String)
  }
  
  class AppsRepositoryImpl(private val appDao: AppDao) : AppsRepository {
      override fun getProtectedApps(): Flow<List<ProtectedApp>> = appDao.getAllProtectedApps()
      override suspend fun isAppProtected(packageName: String): Boolean = appDao.isAppProtected(packageName)
      override suspend fun protectApp(packageName: String, appName: String) = appDao.insertProtectedApp(ProtectedApp(packageName, appName))
      override suspend fun unprotectApp(packageName: String) = appDao.deleteProtectedAppByPackage(packageName)
  }
  ```
- **Estimated Risk**: Low-Medium
- **Estimated LOC Changed**: ~120 lines

### Commit 7: Integrate Hilt Dependency Injection Framework
- **Description**: Set up Hilt to inject dependencies dynamically instead of hardcoded instantiation.
- **Implementation**:
  ```kotlin
  @Module
  @InstallIn(SingletonComponent::class)
  object AppModule {
      @Provides
      @Singleton
      fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getDatabase(context)

      @Provides
      fun provideAppDao(db: AppDatabase): AppDao = db.appDao()

      @Provides
      @Singleton
      fun providePreferences(@ApplicationContext context: Context): PreferencesManager = PreferencesManager(context)
  }
  ```
- **Estimated Risk**: Medium
- **Estimated LOC Changed**: ~100 lines

---

## Phase 4: Performance & Production Tuning (Pre-Release)

### Commit 8: Optimize LazyColumn Bitmap Loading & Search
- **Description**: Cache app icon conversions to bitmaps at the repository/ViewModel level to prevent GC jank in the LazyColumn.
- **Implementation**:
  ```kotlin
  // Use memory cache mapping for app icons inside the ViewModel:
  private val iconCache = mutableMapOf<String, ImageBitmap>()

  fun getCachedIcon(app: AppItemInfo): ImageBitmap {
      return iconCache.getOrPut(app.packageName) {
          app.icon?.toBitmap(56, 56)?.asImageBitmap() ?: defaultIcon
      }
  }
  ```
- **Estimated Risk**: Low
- **Estimated LOC Changed**: ~30 lines

### Commit 9: Enable R8/Proguard Minification & Rules
- **Description**: Set `isMinifyEnabled = true` in the release block of `app/build.gradle.kts`. Add keep rules.
- **Implementation**:
  ```proguard
  # Proguard Keep Rules (proguard-rules.pro)
  -keep class com.etaegord.ghostscreen.data.** { *; }
  -keepclassmembers class * extends androidx.room.RoomDatabase {
      public <init>(...);
  }
  -keep class com.etaegord.ghostscreen.service.GhostScreenAccessibilityService { *; }
  ```
- **Estimated Risk**: Medium-High
- **Estimated LOC Changed**: ~20 lines
