# UX_REDESIGN_PROPOSAL.md — GhostScreen

> **Philosophy**: GhostScreen protects people from shoulder-surfing. Its UI must make *one thing obvious*: **am I protected right now, and against what?** Every redesign decision below serves that single question.
> **Constraint**: All proposals respect the locked architecture (BOX passthrough, `TYPE_ACCESSIBILITY_OVERLAY`, manual tickers) and the Material 3 Expressive token system already in `Theme.kt`/`Color.kt`.

---

## 0. Core Problem Statement

Today, GhostScreen has **two protection modes that look like one**:

| | Smart Mask | Manual Mask |
|---|---|---|
| Triggered by | Entering a *selected* app | A tap (Tile/Shortcut) |
| Scope | Per-app | Whole screen |
| Toggled by | Home "ARMED" button | QS Tile / Shortcut |

Users cannot tell them apart. The Home button, the Tile, and the Shortcut all *feel* like "turn privacy on." This single confusion is the root cause of the majority of reported issues (BUG #6, the "armed behaves like manual" complaint, the tile-latency perception). **This redesign exists to separate them visibly and explain them once.**

---

## 1. Home Screen Redesign

### 1.1 What's wrong today
- **Two Smart Mask sections** (`HomeScreen.kt:328` full Card + `:392` StatCard). Duplicate.
- One giant concentric "ARMED" power button that says nothing about *what* is armed.
- A status chip ("Ready to Mask") with ambiguous wording.
- No Manual Mask entry point at all — it's hidden in the Tile/Shortcut.

### 1.2 Redesigned layout (top → bottom)

```
┌─────────────────────────────────────────┐
│  [icon] GhostScreen          [settings]  │  ← compact top bar
├─────────────────────────────────────────┤
│  ● Protecting 4 apps                      │  ← status chip (context-aware)
├─────────────────────────────────────────┤
│                                            │
│         ╭─────────────────────╮           │
│         │                     │           │
│         │   SMART MASK        │           │  ← PRIMARY card
│         │   ● Active          │           │     (the real master control)
│         │   Mask appears in   │           │
│         │   your selected apps│           │
│         │                     │           │
│         │   [ ARM  ]  [Apps>] │           │
│         ╰─────────────────────╯           │
│                                            │
│   ╭───────────────╮ ╭───────────────╮    │
│   │ MANUAL MASK   │ │ AUDIT LOG     │    │  ← secondary row
│   │ Instant,      │ │ 12 protections│    │     (Manual is now a peer)
│   │ whole screen  │ │ this week     │    │
│   │ [Turn on]     │ │ [Open]        │    │
│   ╰───────────────╯ ╰───────────────╯    │
│                                            │
└─────────────────────────────────────────┘
```

### 1.3 Key changes
1. **Smart Mask becomes the primary card**, not a bare toggle. It contains:
   - The arm switch (the existing concentric-circle control, repurposed).
   - A live line: "Protecting N apps" (or "Select apps to protect →" if 0).
   - A direct "Apps" link into Smart Mask settings.
2. **Manual Mask becomes a visible, labeled secondary action** — not a hidden Tile. Its card explains *in one line* what it does differently: "Instant, whole-screen mask. Ignores your app list."
3. **Status chip goes context-aware**:
   - 0 apps, disarmed → "Pick apps to protect"
   - N apps, armed → "Protecting N apps"
   - Manual on → "Manual mask on (whole screen)"
   - Both → "Manual mask on" (Manual wins; chip says so honestly)
4. **The duplicate Smart Mask StatCard is removed.** Audit Log stays as the second secondary card.

### 1.4 Mutual-exclusion behavior (pairs with BUG #6 fix)
- Tapping **Arm Smart Mask** clears Manual Mask (so they never silently coexist).
- Tapping **Manual Mask** does **not** disarm Smart — it just overrides it visibly. The chip always reflects which is active.

---

## 2. Manual Mask — Make it understandable

### 2.1 Today
Users don't know it exists, what it is, or when to use it. Onboarding page 3 mentions it but doesn't justify it.

### 2.2 Proposal
- **Rename to "Instant Mask"** in UI strings (keep `manual_override_active` pref key for compatibility). "Instant" communicates the value (now, everywhere) better than "Manual" (which implies effort).
- **One-line value prop everywhere it appears**: "Covers the whole screen right now — for reading in a queue, showing a photo to one person, etc."
- **Three entry points, all labeled identically**:
  1. Home secondary card (new).
  2. QS Tile — relabel to "GhostScreen — Instant".
  3. Shortcut — already exists.
- **Visual distinction from Smart**: Instant uses the **tertiary** color token; Smart uses **primary**. Never the same color.

---

## 3. Smart Mask — Make it discoverable and self-explanatory

### 3.1 Today
The Smart Mask screen is a flat app list behind a search box. No explanation of *conditional* masking (keyboard/shade only mask over protected apps). The disclaimer modal walls the user before they see apps.

### 3.2 Proposals
- **Inline "Recommended for full protection" pinned card** at the top of the app list, containing the user's detected System UI + keyboard (Gboard/Samsung/etc.), each with a one-tap toggle. This replaces the modal disclaimer (content moves into a "?" info icon).
- **Conditional-masking explainer** as a persistent, dismissable banner: "🔑 Keyboards and the notification shade are masked *only while you're in a protected app* — so typing in WhatsApp is hidden, but typing in your notes app is normal."
- **Bulk action**: "Protect all suggested" (messaging/banking/photo keywords).
- **Empty state**: when the user has armed but selected 0 apps, the Home card and this screen both say "You're armed but have no apps selected — nothing will be masked yet."

---

## 4. Audit Log → Privacy Dashboard

### 4.1 Today
A flat reverse-chronological list of "{app} Access Intercepted". No insight.

### 4.2 Redesign: a 3-section dashboard

```
┌─────────────────────────────────────────┐
│  Privacy Dashboard            [export]   │
├─────────────────────────────────────────┤
│                                            │
│      TODAY         THIS WEEK              │
│       14           62  protections        │  ← headline stats
│                                            │
│  ─────────────────────────────            │
│  Last 7 days                              │
│  ▁ ▃ ▅ ▇ ▂ ▄ ▆     ← simple bar timeline  │
│  M T W T F S S                            │
│  ─────────────────────────────            │
│  Most protected apps                      │
│  ● WhatsApp          28                   │
│  ● Banking           14                   │
│  ● System UI          9                   │  ← top apps
│  ─────────────────────────────            │
│  Recent activity                [clear]   │
│  • WhatsApp · 2m ago                      │  ← existing list, condensed
│  • Banking   · 1h ago                     │
└─────────────────────────────────────────┘
```

### 4.3 Feature set (offline-only, privacy-respecting)
| Feature | Data source | Fit |
|---|---|---|
| Today / this-week counts | existing `audit_logs.timestamp` | ✅ pure aggregation |
| 7-day bar timeline | same | ✅ |
| Most-protected apps (top N) | `GROUP BY packageName` | ✅ needs a new DAO query |
| Recent activity list | existing | ✅ keep, condensed |
| **Export** (JSON/CSV to file) | local only, user-initiated | ✅ no network |
| ~~Session durations~~ | not recorded | ❌ would need new IME/app-session tracking — out of scope |
| ~~"Keyboard protections" breakdown~~ | `action` field is hardcoded | ⚠️ needs distinct `action` values in `AuditLog` inserts (Smart-show vs Instant-show vs transient-inherit). Recommend adding this — cheap, unlocks the breakdown. |

### 4.4 Enriching the data (small schema-free change)
`AuditLog.action` is currently always `"Protected with Privacy Overlay"` (`GhostScreenAccessibilityService.kt:157`). Introduce typed actions:
- `"smart"` — Smart Mask showed for a protected app.
- `"instant"` — Instant/Manual mask turned on.
- `"transient"` — keyboard/shade inherited a protected app's mask.

No DB migration needed (it's a `String` column); old rows simply read as "unknown" in the dashboard. This unlocks the per-category breakdown.

### 4.5 Retention
Add a Settings toggle: "Keep logs for [30 / 90 / forever]". A nightly/on-open prune query (`DELETE WHERE timestamp < ?`). Keeps the DB bounded.

---

## 5. Onboarding Redesign

### 5.1 New 5-page flow (was 4)
1. **Welcome** — value prop, hero.
2. **NEW: How protection works** — side-by-side illustration:
   - Left: "Smart Mask — pick your apps, we hide them automatically."
   - Right: "Instant Mask — one tap, whole screen, for right now."
3. **Permissions** — overlay + accessibility (current page 2).
4. **Set up Smart Mask** — pick 2-3 suggested apps inline (WhatsApp, banking) so arming *immediately* does something. Replaces current page 3 ("Manual Override" — which was premature).
5. **All set** — with a first-overlay coachmark CTA.

### 5.2 Fixes to existing onboarding
- Remove the **fake tappable toggle** in the permission mockup (`OnboardingScreens.kt:278`) — replace with a static illustration.
- Generalize the "Smart Sidebar" tip to non-Samsung devices.
- The permission auto-advance guard (`hadMissingOnEntry`) is good — keep.

---

## 6. Overlay First-Appearance Coachmark

When the overlay shows for the **first time ever**, overlay a one-time, dismissable hint on the mask itself:
- "Drag the eye to move your clear zone."
- "Drag horizontally on the eye to change darkness."
- "Long-press to switch to Box mode."

Stored via a new boolean pref `overlay_coachmark_shown`. Drawn as a small `Canvas` text bubble on the fullscreen passive `MaskView` for one session only.

---

## 7. Animation & Motion Guidelines

### 7.1 Introduce `ui/theme/Motion.kt`
Centralize duration + easing tokens:
```kotlin
object Motion {
    val DurationFast = 150.milliseconds
    val DurationMedium = 300.milliseconds
    val DurationSlow = 500.milliseconds
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
}
```

### 7.2 Apply rules
| Element | Current | Proposed |
|---|---|---|
| Toggle color | `animateColorAsState(tween(400))` | `tween(DurationMedium, easing = EmphasizedDecelerate)` |
| Segmented Solid/Blur | snap | crossfade background (`tween(DurationFast)`) |
| Nav route changes | none | fade-through (M3 nav spec) |
| Overlay show/hide | instant `addView`/`removeView` | keep instant — overlays must feel immediate; animation here risks feeling laggy |
| Home pulse | `InfiniteTransition` + `graphicsLayer` | ✅ keep |
| Onboarding success rings | manual ticker | ✅ keep (system-scale-disable safe) |
| First-appear coachmark | n/a | fade in, auto-dismiss after 6s or on first drag |

### 7.3 Respect system animation scale
The locked rule: **overlay mask animations** bypass the scale (manual tickers). **In-app** animations should **respect** the scale (standard Compose APIs already do). Keep this split explicit and documented in `Motion.kt`.

---

## 8. Empty States & Feedback

| Screen | Today | Proposed |
|---|---|---|
| Audit Log empty | "No intrusions logged" + long text | "You haven't been protected yet" + **CTA: "Arm Smart Mask"** |
| Smart Mask, 0 apps | (just empty list) | Inline illustration + "Pick the apps you want to hide" |
| Home, armed + 0 apps | silent no-op | Warning chip: "Armed but no apps selected" |
| Overlay first show | no guidance | Coachmark (§6) |
| Toggle success | haptic only | haptic + status chip update (already partial) |

---

## 9. Information Architecture (final)

```
Home
 ├─ Smart Mask card ──→ Smart Mask Settings (app list + recommended)
 ├─ Instant Mask card ──→ (toggles inline; deep-link to Settings for tile setup)
 └─ Audit card ────────→ Privacy Dashboard

Settings
 ├─ Appearance (color/blur/opacity/cutout defaults)
 ├─ Behavior (launch on boot, haptics, log retention)
 ├─ Instant Mask (tile setup, shortcut)
 ├─ Icon (dynamic color chooser — revives dead code)
 └─ About (version-bound, links)
```

Onboarding: 5 pages (§5).

---

## 10. Copy / Microcopy Changes

| Current | Proposed | Why |
|---|---|---|
| "ARMED" / "Activate" | "Smart Mask on" / "Turn on Smart Mask" | Names the feature |
| "Manual Override" | "Instant Mask" | Value, not mechanism |
| "Protection Active" / "Ready to Mask" | context-aware (§1.3) | Removes ambiguity |
| "GhostScreen (On)" tile | "GhostScreen — Instant" | Distinguishes from Smart |
| "Intruder Audit Log" | "Privacy Dashboard" | Matches the new value |
| "X blocked" (audit count) | "X protections" | Positive framing; we protect, not block |

---

## 11. Implementation Phasing (with the other reports)

| Phase | Scope | Depends on |
|---|---|---|
| **UX-1** | Home dedup + Smart/Instant separation + status chip | BUG #6 logic guard |
| **UX-2** | Onboarding "How it works" page + app-pick step | UX-1 copy |
| **UX-3** | Privacy Dashboard (aggregation queries + UI) | `AuditLog.action` enrichment |
| **UX-4** | Motion.kt + nav transitions + state layers | M3 tokenization (PRODUCT report) |
| **UX-5** | Coachmark + empty states | UX-1 |
| **UX-6** | Settings restructure + dynamic icon | — |

Each phase = one or more single-focus commits, each built + unit-tested.

---

## 12. What we explicitly will NOT do

- No cloud, no sync, no analytics (locked non-goals).
- No screen-capture-based "real" blur (locked non-goal).
- No BLoC/Redux/external state-management library (Compose state is enough; ponytail rule).
- No rename of the app or the package.
- No removal of the Manual-ticker animation system (locked).

*End of report. Awaits approval before implementation.*
