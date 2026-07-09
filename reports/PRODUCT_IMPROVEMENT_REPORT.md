# PRODUCT_IMPROVEMENT_REPORT.md — GhostScreen

> **Status**: Proposal, pre-implementation. **Trust the code over stale docs.**
> **Scope**: Whole-app UI/UX, Material 3 Expressive compliance, per-screen critique.
> **Author**: Lead Maintainer review pass.
> **Method**: Static read of every Compose screen + theme + overlay view. No device profile yet (see *Performance* report for profiling action items).

---

## 0. Executive Summary

GhostScreen's *visual system* is genuinely strong: a coherent M3 Expressive token set, pill geometry, GPU-composited animations, and a tasteful purple/ghost brand. The overlay engine is the product's crown jewel and is already performant.

The weaknesses are **structural and informational**, not aesthetic:

1. **The Home screen tells two different stories** — "Arm everything" vs "Protect specific apps" — and the UI reinforces the wrong one (a giant global power button + duplicated Smart Mask sections).
2. **Two core concepts are never explained**: *Manual Mask* and *Smart Mask*. Users cannot tell them apart, and the onboarding never disambiguates them.
3. **The Audit Log is a flat list** with no analytical value, despite holding exactly the data needed for a real privacy dashboard.
4. **Material 3 compliance is ~85%** — strong on color tokens, weak on motion (no `AnimatedContent`/shared-element transitions, ad-hoc `animateColorAsState`) and on a few spacing/typography inconsistencies.

No screen is "ugly." Several are *confusing*. This report separates **cosmetic polish** from **information-architecture fixes**, and prioritizes the latter.

---

## 1. Material 3 Expressive Compliance Audit

| Dimension | Verdict | Evidence |
|---|---|---|
| **Color tokens** | ✅ Strong | All UI screens use `MaterialTheme.colorScheme.*`. Zero raw hex in `ui/` (verified by grep). Hex appears only for *user-chosen mask colors* in `SettingsScreen` presets — legitimate data, not theme colors. |
| **On/color pairing** | ✅ Correct | `primaryContainer` content consistently uses `onPrimaryContainer` (HomeScreen:358, etc.). `errorContainer`→`onErrorContainer` correct in banners. |
| **Shape system** | ⚠️ Inconsistent | `Theme.kt` defines a proper `GhostScreenShapes` scale (8/16/24/32/48dp) — but **screens hardcode `RoundedCornerShape(24.dp)`/`(16.dp)` inline** instead of `MaterialTheme.shapes.medium/small`. The token system exists but is largely unused. |
| **Typography** | ⚠️ Underused | `Type.kt` defines a scale but screens frequently pass raw `fontSize = 14.sp`/`fontWeight = FontWeight.Bold` instead of `MaterialTheme.typography.titleMedium` etc. Makes global type tweaks impossible. |
| **Motion** | ❌ Weakest area | No `AnimatedContent`, no shared-element transitions between routes, no nav enter/exit specs. Color transitions use `animateColorAsState` ad hoc. The overlay's manual tickers are intentionally excluded (correct) — but *in-app* motion is below M3 Expressive expectations. |
| **State layers** | ❌ Missing | `Modifier.clickable` used directly on swatches/chips without `indication = ripple()`/`LocalIndication`. No visible press feedback on the segmented mode selector in Settings. |
| **Edge-to-edge** | ✅ Good | `enableEdgeToEdge()` + transparent system bars + `systemBarsPadding()` in onboarding. |
| **Accessibility (a11y)** | ⚠️ Partial | Icons mostly have `contentDescription`, but many decorative ones use `null` correctly while some interactive icons lack descriptions. No `LargeTopAppBar`/large-touch consideration. Color contrast passes (deep purple on white). |

### 1.1 M3 Action Items (cosmetic, low-risk)
- **A1** Replace inline `RoundedCornerShape(N.dp)` → `MaterialTheme.shapes.*` across all screens. Single global source of truth.
- **A2** Replace raw `fontSize`/`fontWeight` → `MaterialTheme.typography.*` tokens.
- **A3** Add M3 state-layer ripples to all custom `Modifier.clickable` (swatches, segmented control, custom switches in onboarding mockup).
- **A4** Define nav-route enter/exit animation specs once in `GhostScreenApp` NavHost (fade-through or slide).
- **A5** Audit `contentDescription` for a11y (see §6 below).

---

## 2. Per-Screen Critique

### 2.1 Onboarding (`OnboardingScreens.kt`, 478 lines)
**What works**: 4-page `HorizontalPager`, clean page indicators, permission auto-advance logic (`hadMissingOnEntry` guard, `OnboardingScreens.kt:204`) is well-thought-out.

**Problems**:
- **P0 — Concept gap**: Page 2 ("Permission Guide") and Page 3 ("Manual Override") introduce permissions and a *feature*, but **never explain the two-mode model** (Smart vs Manual). Users finish onboarding unable to answer "what did I just enable?"
- **P1 — "Mockup allowed" toggle** (`OnboardingScreens.kt:278`) is a *fake interactive switch* inside a permission mockup. It is tappable and flips state, which is misleading — it does nothing real. Either make it clearly a non-interactive illustration, or remove it.
- **P2 — Pro Tip ("Smart Sidebar")** on the final page references a Samsung-specific feature ("Hold the app icon to add the Manual Shortcut to your Sidebar") with no fallback for other OEMs.
- **P3 — Welcome page** has a large empty `Box` (the `surfaceContainerHighest` card) with just a small icon+label; visually thin for a hero page.

**Proposals**:
- Insert a **"How GhostScreen protects you"** page between Welcome and Permissions, with a side-by-side illustration: *Smart Mask* (shield over app list) vs *Manual Mask* (single button). One sentence each.
- Replace the fake toggle with a static illustration + a real "Grant permission" CTA below.
- Generalize the Smart Sidebar tip to: "Add the Quick Settings tile or a 1-tap shortcut" (both of which the app actually supports).

### 2.2 Home Screen (`HomeScreen.kt`, 583 lines)
**This is the screen with the most issues.** See also `UX_REDESIGN_PROPOSAL.md`.

**Problems**:
- **P0 — Duplicate Smart Mask sections.** A full-width "Smart Mask" Card (`HomeScreen.kt:328-383`) *and* a "Smart Mask" `StatCard` in the Quick Stats row (`HomeScreen.kt:392-399`). Both navigate to the same screen. Visual redundancy + wasted vertical space.
- **P0 — The master toggle conflates two features.** The giant concentric-circle "ARMED" button (`HomeScreen.kt:217-325`) sets `preferencesManager.isArmed`, which the service treats as a *global* switch. Combined with the documented Smart-Mask logic, this reads to users as "turn on privacy" but actually means "enable auto-detection for my selected apps." If they have **zero protected apps selected**, arming does nothing visible — confusion. This is the root of the reported *"Home Armed toggle behaves like Manual Mask"* bug (see BUG report).
- **P1 — Pulse animation** uses `InfiniteTransition` correctly (GPU via `graphicsLayer`) — good. But it runs **even when disarmed**? No — guarded by `if (isArmed)` (`:228`). Correct. (Recording this because the audit report claimed idle recomposition; the code shows it's already fixed.)
- **P1 — `animateColorAsState`** on the toggle (`:270`) is fine for a one-shot transition; acceptable.
- **P2 — Permission banners** are good but disappear permanently once granted; no way to re-verify status from Home beyond the small status chip.
- **P2 — Status chip** ("Protection Active" / "Ready to Mask") wording is ambiguous: "Ready to Mask" doesn't tell the user *what to do next*.

**Proposals**:
- Remove the duplicate StatCard; keep one clear "Smart Mask" entry point.
- Redesign the primary control area to **separate Smart Mask (arm) from Manual Mask (instant on)** as two distinct, labeled actions. (Full redesign in UX report.)
- Status chip → context-aware: "Protecting N apps" / "Add apps to protect" / "Manual mask on".

### 2.3 Smart Mask Settings (`SmartMaskSettingsScreen.kt`, 523 lines)
**What works**: Search + filter chips + suggested/normal sections + cached icon bitmaps (`remember(app.packageName)`, `:461`). The OEM-specific SystemUI/keyboard package list (`:194-204`) is genuinely useful.

**Problems**:
- **P0 — Filter crash & "GhostScreen" search crash** (see BUG report). Root cause: `filteredList`/`partition` logic + missing null-guard around self-package. Reproducible.
- **P1 — Loading is a `LaunchedEffect(Unit)` with no cancellation/reload** on permission/data change. If the user revokes launcher-query visibility mid-session, the list stays stale.
- **P1 — Disclaimer dialog** is good content but walls the user *before* they see any apps; better to show apps and surface the SystemUI/keyboard suggestion inline as a highlighted card.
- **P2 — No bulk actions** (enable all suggested, disable all). Tedious for users with many apps.
- **P2 — Toggle writes are `scope.launch(Dispatchers.IO)` per-row** (`:383`, `:414`) — correct, but no optimistic UI; the switch waits for DB write before `collectAsState` updates it. Feels laggy on slow devices.

**Proposals**:
- Fix crashes (BUG report).
- Optimistic toggle: flip local state immediately, rollback on failure.
- Inline the SystemUI/keyboard recommendation as a pinned "Recommended for full protection" card at the top, not a modal.
- Add "Enable all suggested" action.

### 2.4 Settings (`SettingsScreen.kt`, 444 lines)
**What works**: Segmented Solid/Blur control, color picker dialog with RGB sliders + hex + presets, behavior toggles.

**Problems**:
- **P0 — Color editor lag** (see BUG report). The `LazyRow` of swatches recomposes excessively because `isSelected` depends on `overlayColor` which is `mutableStateOf` updated on every pick; the whole row re-measures.
- **P1 — "Quick Settings Tile" card** (`:309`) is purely decorative (a static mock tile). Low value, high vertical space. Either make it a deep-link ("Add tile to quick settings") or remove.
- **P1 — Missing settings**: mask opacity default, cutout size default, audit-log retention/clear-on-uninstall, dynamic-icon chooser (the code exists in manifest aliases but no UI exposes it — `setupRandomIconIfNeeded` is commented out).
- **P2 — Blur description** says "Control blur intensity using the eye controller" but the eye controller controls *opacity*, not blur radius. Misleading.

**Proposals**:
- Fix color editor perf (derive `isSelected` via `derivedStateOf`, key the `LazyRow` items).
- Add a real "Appearance" group: default opacity, default cutout, icon color chooser.
- Correct the blur description; expose `blurRadius` as its own slider here (it's already a pref, `blurRadius`, unused in UI).

### 2.5 Audit Log (`AuditLogScreen.kt`, 232 lines)
**Lowest value-per-line screen.** A flat reverse-chronological list of "{app} Access Intercepted" rows. No aggregation, no insight.

**Problems**:
- **P0 — No analytical value.** Users can't answer "how often am I protected?" or "which app triggers most?"
- **P1 — "action" field** in `AuditLog` is hardcoded to "Protected with Privacy Overlay" for every insert (`GhostScreenAccessibilityService.kt:157`) — so the field carries no information.
- **P1 — No export**, no retention control, no empty-state CTA.
- **P2 — Timestamp formatting** uses `Locale.ENGLISH` hardcoded; should respect user locale.

**Proposals**: Full redesign → see `UX_REDESIGN_PROPOSAL.md` (privacy dashboard: today count, 7-day timeline, top apps, export).

### 2.6 About (`AboutScreen.kt`, 197 lines)
**Problems**:
- **P0 — Factually wrong claims** (lines 175-176): "PorterDuff.Mode.CLEAR cutout masking" and "ViewTreeObserver touchable region calculation." **Neither exists in the code.** BOX mode uses multi-window passthrough. This misleads contributors and users.
- **P1 — Static version string** "v5.0 - The Master Blueprint" — not derived from `BuildConfig.VERSION_NAME`. Will drift.
- **P1 — No links** (repo, license, F-Droid). For an open-source app this is expected.

**Proposals**: Fix the technical-blurb to match reality; bind version to `BuildConfig`; add repo/license/"check for updates" links.

---

## 3. Information Architecture Problems (cross-screen)

### 3.1 The Two-Model Confusion
GhostScreen has **two protection modes** that share UI vocabulary:

| Mode | Trigger | Scope | Persistence |
|---|---|---|---|
| **Smart Mask** (`isArmed`) | Auto, on entering a *selected* app | Per-app | Until disarmed |
| **Manual Mask** (`isManualOverrideActive`) | User, via Tile/Shortcut | Global, ignores app list | Until toggled off |

Nowhere is this stated. The Home "ARMED" button, the Tile, and the Shortcut all *look* like the same "turn privacy on" action. **This single confusion likely generates most of the user-reported "bugs"** (overlay on when it shouldn't be, tile lag perception, "armed behaves like manual").

**Proposal**: Make the dual-model explicit in (a) onboarding, (b) Home layout, (c) Settings, (d) Tile label ("Smart" vs "Manual").

### 3.2 Navigation Depth
Current: Home ↔ {Settings, SmartMask, AuditLog, About}. All flat. Acceptable for an app this size, but **Settings and Smart Mask overlap** (both configure protection). Consider grouping.

---

## 4. Animation & Motion Review

| Element | Current | Verdict |
|---|---|---|
| Home pulse ring | `InfiniteTransition` + `graphicsLayer` | ✅ Correct, GPU-only |
| Onboarding "All set" rings | `rememberManualLoopAnim` (manual ticker) | ✅ Bypasses anim-scale disable — correct intent |
| Toggle color | `animateColorAsState(tween(400))` | ⚠️ OK but should be M3 `motionScheme`/emphasized easing |
| Segmented control (Settings) | No transition between Solid/Blur backgrounds | ❌ Snaps; should crossfade |
| Permission mockup switch | `animateColorAsState` + `animateDpAsState` | ⚠️ Custom; M3 `Switch` would be more consistent |
| Nav transitions | None | ❌ Add specs |

**Proposal**: Introduce a small `Motion.kt` (duration tokens + emphasized easing) and apply uniformly. Keep manual tickers only where system-scale-disable matters (overlay + onboarding success).

---

## 5. Discoverability & Onboarding Gaps

- **Manual Mask is invisible** until the user reads the onboarding page or stumbles on the Tile. No Home entry point, no explanation.
- **Smart Mask's "select apps" step is buried** behind a card tap. First-run users who arm without selecting apps see nothing happen.
- **No empty-state CTAs** on Audit Log ("You haven't been protected yet — arm Smart Mask").
- **No "first protection" moment** — when the overlay first appears, there's no coaching ("Drag the eye to move the clear zone").

**Proposals**:
- First-run coachmark on the overlay's first appearance (one-time, dismissable).
- Empty Audit state with a CTA.
- Surface Manual Mask as a distinct, explained control on Home.

---

## 6. Accessibility (a11y) Audit

| Issue | Location | Fix |
|---|---|---|
| Decorative icons `contentDescription = null` while some *interactive* icons also lack description | various | Ensure every actionable icon has a description |
| Touch targets < 48dp | Onboarding mock switch ~28dp tall | Resize to ≥48dp or mark as non-interactive |
| Color-only state | Filter chips rely on color for selected state | Add icon/check in addition to color |
| No dynamic font scaling support verified | All `fontSize = N.sp` | Prefer `MaterialTheme.typography` (scales with user setting) |
| Swipe-only pager nav | Onboarding | Add the existing "NEXT" buttons (present ✓) — OK |

---

## 7. Responsiveness

- **Edge-to-edge**: handled.
- **Rotation**: **NOT handled in overlay** (documented bug, see BUG report). In-app Compose handles rotation natively.
- **Large screens / foldables**: no `WindowSizeClass` usage; Home would benefit from a two-column layout on tablets/foldables. Low priority (phone-first app) but worth a note.
- **Right-to-left**: `supportsRtl="true"` set; no `Modifier.padding(start/end)` audit done. Likely minor issues.

---

## 8. Prioritized Backlog (cosmetic + IA)

| ID | Item | Type | Effort | Risk |
|---|---|---|---|---|
| IA-1 | Disambiguate Smart vs Manual Mask across onboarding/Home/Tile | IA | M | M |
| IA-2 | Redesign Home (remove duplicate Smart Mask, split controls) | IA | M | M |
| IA-3 | Redesign Audit Log into privacy dashboard | IA | L | L |
| IA-4 | Add "How protection works" onboarding page | IA | S | L |
| M3-1 | Tokenize shapes (`MaterialTheme.shapes`) | cosmetic | S | L |
| M3-2 | Tokenize typography | cosmetic | S | L |
| M3-3 | Add nav transition specs | cosmetic | S | L |
| M3-4 | Add state-layer ripples to custom clickables | cosmetic | S | L |
| DOC-1 | Fix AboutScreen technical claims | correctness | S | L |
| DOC-2 | Bind About version to BuildConfig | correctness | S | L |
| A11Y-1 | contentDescription + touch-target sweep | a11y | M | L |
| SET-1 | Expose blur-radius + dynamic-icon in Settings | feature | M | L |

**Recommended sequence**: IA-1 + IA-2 first (they unblock correct user mental model), then BUG fixes (separate report), then M3 polish.

---

## 9. What NOT to Change (locked)

Per `PROJECT_MANIFEST.md` §4 and `AGENTS.md` §3 — these are **architectural locks**, not UI decisions, and remain off-limits regardless of this report:
- BOX-mode multi-window passthrough layout.
- `TYPE_ACCESSIBILITY_OVERLAY` window type.
- Manual coroutine ticker loops in `ManualAnimUtils.kt`.

---

*End of report. Awaits approval before implementation.*
