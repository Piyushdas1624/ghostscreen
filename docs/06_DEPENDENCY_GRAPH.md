# System Dependency Graph: GhostScreen

This document maps the package dependencies, ownership hierarchies, navigation flows, and database interactions within the [GhostScreen](file:///d:/ghostscreen) application.

---

## 1. Package Graph (Architecture Decoupling)

This graph displays the structural relationships between packages. The arrows indicate outgoing imports (efferent coupling).

```mermaid
graph TD
    UI[com.etaegord.ghostscreen.ui] -->|Imports| Data[com.etaegord.ghostscreen.data]
    UI -->|Imports| Service[com.etaegord.ghostscreen.service]
    UI -->|Imports| Util[com.etaegord.ghostscreen.util]
    
    Service -->|Imports| Data
    Service -->|Imports| Util
    
    Data -->|Self-Contained| Room[androidx.room]
    Util -->|Self-Contained| JDK[kotlin.math]
```

### Coupling Instability Metrics
The instability metric ($I = C_e / (C_a + C_e)$) measures package susceptibility to change:

- **`data` Package**: $C_e = 0$, $C_a = 2 \rightarrow$ **Instability $I = 0.0$** (Highly Stable). Changes in data logic propagate upward; changes in other packages do not affect it.
- **`util` Package**: $C_e = 0$, $C_a = 2 \rightarrow$ **Instability $I = 0.0$** (Highly Stable). Pure mathematical and transition engines.
- **`service` Package**: $C_e = 2$, $C_a = 1 \rightarrow$ **Instability $I = 0.67$** (Moderately Stable). Coordinates system integrations.
- **`ui` Package**: $C_e = 3$, $C_a = 0 \rightarrow$ **Instability $I = 1.0$** (Highly Unstable). Presentation layer that absorbs changes from all other modules.

---

## 2. Core Service Ownership & View Hierarchy

This graph details object ownership and lifetime containment within the background execution pipeline.

```mermaid
graph TD
    AndroidOS[Android OS OS System] -->|Manages Lifecycle| AccService[GhostScreenAccessibilityService]
    AccService -->|Owns / Instantiates| Preferences[PreferencesManager]
    AccService -->|Owns / Instantiates| Database[AppDatabase]
    AccService -->|Instantiates & Triggers| Overlay[GhostScreenOverlay]
    
    Overlay -->|Constructs & Controls| ExitButton[ExitButtonView]
    Overlay -->|Constructs & Controls| MaskView[MaskView]
    Overlay -->|Constructs & Controls| MaskWindow[MaskWindowView]
    Overlay -->|Constructs & Controls| SplitControllers[StripView]
    Overlay -->|Constructs & Controls| CornerHandles[CornerHandleView]
    Overlay -->|Constructs & Controls| OpacityDrop[OpacityDropView]
```

---

## 3. Class-Level Structural Dependency Diagram

The complete class-to-class interaction graph illustrates imports and composition relationships:

```mermaid
classDiagram
    class MainActivity {
        -PreferencesManager preferencesManager
        -AppDatabase db
        +onCreate()
    }
    class ShortcutActivity {
        +onCreate()
        -isAccessibilityEnabled()
    }
    class GhostScreenAccessibilityService {
        -PreferencesManager preferencesManager
        -AppDatabase db
        -GhostScreenOverlay overlay
        +onAccessibilityEvent()
        -checkCurrentApp()
    }
    class GhostScreenOverlay {
        -Context context
        -PreferencesManager preferencesManager
        -MaskView maskView
        -StripView stripTopView
        -StripView stripBotView
        +show()
        +hide()
        +applyBoxDelta()
    }
    class GhostScreenTileService {
        -PreferencesManager preferencesManager
        +onClick()
    }
    class PreferencesManager {
        -SharedPreferences plainPrefs
        -SharedPreferences encryptedPrefs
        +isArmed
        +isManualOverrideActive
    }
    class AppDatabase {
        +appDao()
    }
    class AppDao {
        +getAllProtectedApps()
        +insertProtectedApp()
        +insertAuditLog()
    }
    class OverlayTransition {
        +decide()
        +isTransient()
    }
    class OpacityCurve {
        +displayToRealPct()
        +realPctToAlpha()
    }

    MainActivity --> PreferencesManager
    MainActivity --> AppDatabase
    ShortcutActivity --> PreferencesManager
    GhostScreenAccessibilityService --> PreferencesManager
    GhostScreenAccessibilityService --> AppDatabase
    GhostScreenAccessibilityService --> GhostScreenOverlay
    GhostScreenAccessibilityService --> OverlayTransition
    GhostScreenOverlay --> PreferencesManager
    GhostScreenOverlay --> OpacityCurve
    GhostScreenTileService --> PreferencesManager
    AppDatabase --> AppDao
```

---

## 4. Compose Navigation & UI Flow

This graph illustrates user navigation transitions across views inside [MainActivity](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/ui/MainActivity.kt).

```mermaid
stateDiagram-v2
    [*] --> Onboarding : Launch (If onboarding incomplete or permissions missing)
    [*] --> Home : Launch (If onboarding done & permissions verified)
    
    Onboarding --> Home : Navigation (After setup finished)
    
    state Home {
        [*] --> MainDashboard
        MainDashboard --> SmartMask : Tap Smart Mask Card
        MainDashboard --> AuditLog : Tap Audit Log Card
    }
    
    state NavigationBar {
        Home --> Settings : Tap Settings Icon
        Settings --> About : Tap About Icon
        About --> Home : Tap Home Icon
    }
```

---

## 5. Database Interaction Topography

The tables, fields, and operations defined within the [AppDatabase](file:///d:/ghostscreen/app/src/main/java/com/etaegord/ghostscreen/data/AppDatabase.kt) schema:

```mermaid
erDiagram
    PROTECTED_APPS {
        string packageName PK
        string appName
        long addedAt
    }
    AUDIT_LOGS {
        long id PK
        long timestamp
        string packageName
        string appName
        string action
    }

    AUDIT_LOGS }|--|| PROTECTED_APPS : "logs access attempts to"
```
