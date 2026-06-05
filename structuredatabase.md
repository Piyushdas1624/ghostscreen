# GhostScreen Structured Database Schema

> Last updated: 2026-05-25 16:18 UTC  
> Updated during: Performance Fix — Async EncryptedSharedPreferences

---

## Room Database

**Database name:** `ghostscreen_database`  
**Database version:** 1  
**Export schema:** false  
**Migration strategy:** `fallbackToDestructiveMigration()`

---

#### **1. Database/Table: `protected_apps`**
* **Type:** Room Entity
* **Purpose:** Stores all user-selected apps that should trigger the privacy overlay when opened.
* **Fields:**
    * `packageName` (String, Primary Key) — Android package name of the protected app
    * `appName` (String) — Human-readable app name
    * `addedAt` (Long) — Timestamp when the app was added to protection list (default: `System.currentTimeMillis()`)

---

#### **2. Database/Table: `audit_logs`**
* **Type:** Room Entity
* **Purpose:** Records all interception events when a protected app is accessed while the system is armed.
* **Fields:**
    * `id` (Long, Primary Key, Auto-generate)
    * `timestamp` (Long) — When the event occurred (default: `System.currentTimeMillis()`)
    * `packageName` (String) — Package name of the app that was accessed
    * `appName` (String) — Human-readable name of the accessed app
    * `action` (String) — Description of the action taken (default: `"Blocked snoop attempt"`)

---

## EncryptedSharedPreferences (Async)

**Preferences name:** `ghostscreen_secure_prefs`  
**Encryption:** AES256_GCM (value) / AES256_SIV (key)  
**Initialization:** Background thread (`Dispatchers.IO`) to avoid blocking main thread  
**Migration:** One-time auto-migration from plain prefs on first successful init

---

## Plain SharedPreferences (Instant)

**Preferences name:** `ghostscreen_prefs`  
**Purpose:** Provides instant reads on app launch while encrypted prefs are being initialized asynchronously. Writes go to both plain and encrypted stores.  
**Note:** This was added to fix a critical 9-second startup lag caused by Tink crypto verification blocking the main thread.

---

#### **3. Preferences: `ghostscreen_prefs` / `ghostscreen_secure_prefs`**
* **Type:** SharedPreferences (plain) + EncryptedSharedPreferences (async)
* **Purpose:** Stores all user configuration, overlay state, and feature flags.
* **Fields:**
    * `armed` (Boolean, default: `false`) — Master toggle: whether the privacy overlay system is active
    * `transparency` (Int, range: 0–100, default: `75`) — Overlay mask transparency percentage
    * `color` (String, default: `"#000000"`) — Hex color code for the overlay mask
    * `launch_on_boot` (Boolean, default: `false`) — Whether to auto-arm on device boot
    * `vibrate_on_activation` (Boolean, default: `true`) — Haptic feedback when overlay triggers
    * `onboarding_completed` (Boolean, default: `false`) — Whether the user has finished onboarding
    * `cutout_y_pct` (Float, range: 0.0–1.0, default: `0.5`) — Center Y position of the viewing window as percentage of screen height
    * `cutout_height_pct` (Float, range: 0.05–0.9, default: `0.25`) — Height of the viewing window as percentage of screen height
