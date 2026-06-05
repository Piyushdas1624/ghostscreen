---
name: GhostScreen Expressive
colors:
  surface: '#fdf8fd'
  surface-dim: '#ddd9de'
  surface-bright: '#fdf8fd'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f7f2f8'
  surface-container: '#f1ecf2'
  surface-container-high: '#ebe7ec'
  surface-container-highest: '#e5e1e7'
  on-surface: '#1c1b1f'
  on-surface-variant: '#494551'
  inverse-surface: '#313034'
  inverse-on-surface: '#f4eff5'
  outline: '#7a7582'
  outline-variant: '#cbc4d2'
  surface-tint: '#6750a4'
  primary: '#4f378a'
  on-primary: '#ffffff'
  primary-container: '#6750a4'
  on-primary-container: '#e0d2ff'
  inverse-primary: '#cfbcff'
  secondary: '#625b71'
  on-secondary: '#ffffff'
  secondary-container: '#e8def9'
  on-secondary-container: '#686177'
  tertiary: '#633b48'
  on-tertiary: '#ffffff'
  tertiary-container: '#7d5260'
  on-tertiary-container: '#ffcbda'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#cfbcff'
  on-primary-fixed: '#22005d'
  on-primary-fixed-variant: '#4f378a'
  secondary-fixed: '#e8def9'
  secondary-fixed-dim: '#ccc2dc'
  on-secondary-fixed: '#1e192b'
  on-secondary-fixed-variant: '#4a4358'
  tertiary-fixed: '#ffd9e3'
  tertiary-fixed-dim: '#eeb8c8'
  on-tertiary-fixed: '#31111d'
  on-tertiary-fixed-variant: '#633b48'
  background: '#fdf8fd'
  on-background: '#1c1b1f'
  surface-variant: '#e5e1e7'
typography:
  display-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 57px
    fontWeight: '400'
    lineHeight: 64px
    letterSpacing: -0.25px
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  title-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.5px
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.25px
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  base: 8px
  margin-mobile: 16px
  margin-desktop: 24px
  gutter: 16px
  pill-padding-x: 24px
  pill-padding-y: 16px
---

## Brand & Style

The design system for GhostScreen is rooted in the **Material 3 Expressive** philosophy. It prioritizes fluid motion, tactile feedback, and a playful yet high-quality aesthetic. The target audience includes tech-savvy users who value personalization and a modern, high-polish interface.

The visual style is **Corporate / Modern** with a twist of **Expressive Playfulness**. It utilizes heavy, pill-shaped geometry and "squishy" interactive states to make digital interactions feel physical and satisfying. The UI should evoke a sense of responsiveness and intelligence, adapting its personality through dynamic color and smooth transitions.

## Colors

This design system leverages **Material You (M3) Dynamic Color**. The primary, secondary, and tertiary palettes are generated from the user's wallpaper or a seed color, ensuring the UI feels personal and integrated with the OS.

- **Primary:** Used for key actions and the most expressive pill-shaped elements.
- **Secondary:** Used for less prominent components and tonal layering.
- **Tertiary:** Used for accentuating specific functional groups or providing contrast.
- **Neutral:** Used for surfaces and backgrounds, following the M3 Tonal Palette logic (0-100 luminance).

The system must support both **Light and Dark modes** seamlessly, utilizing the `on-primary`, `primary-container`, and `surface-variant` tokens to maintain accessible contrast ratios (WCAG AA).

## Typography

The typography uses **Plus Jakarta Sans** for headlines to inject a soft, modern, and approachable character into the system. For body and functional text, **Roboto Flex** provides maximum legibility and systematic versatility.

The type scale follows Material 3 standards but emphasizes weight for "Expressive" moments. Titles and Headlines should use the semi-bold weights of Plus Jakarta Sans to anchor the page, while labels in buttons and chips use the medium weight of Roboto Flex for clarity.

## Layout & Spacing

This design system uses a **Fluid Grid** model based on an 8px spacing rhythm. 

- **Mobile:** 4-column grid with 16px side margins and 16px gutters.
- **Tablet/Desktop:** 12-column grid with 24px margins and 24px gutters.

Large, expressive elements like Sliders and Buttons should span full width or significant column segments to maintain the "thick" and "satisfying" aesthetic. Negative space is used generously to prevent the bold geometry from feeling cluttered.

## Elevation & Depth

Depth is primarily communicated through **Tonal Layers** and **Surface Tints** rather than heavy shadows. 

- **Level 0 (Surface):** The base background.
- **Level 1-3 (Containers):** Higher elevation is indicated by a shift in color (using Primary or Neutral Tonal Palettes) rather than shadow.
- **Expressive Focus:** For floating action buttons or critical cards, a very soft, highly-diffused ambient shadow (10-15% opacity) may be used to provide a subtle lift.
- **Interaction:** Upon press, elements should "sink" (lose elevation) or "expand" (increase surface area) to provide tactile feedback.

## Shapes

The shape language is the core of the "GhostScreen" identity. It is defined by **Pill-shaped (Maximum roundedness)** geometry. Every interactive container, from small chips to large buttons and input fields, utilizes fully rounded corners. This creates a friendly, organic, and fluid look that avoids the rigidity of sharp corners.

## Components

### Sliders
The defining component of this system. Sliders must be "thick," using a height of at least 48px-56px for the track. The active portion of the track should be a vibrant Primary color, while the inactive part is a subtle Surface Variant. The "thumb" or handle is often integrated into the track itself or represented by a large, satisfying circle.

### Buttons
Buttons are large, expressive pill shapes (height: 56px). Use `Primary Container` for standard actions and `Primary` for high-emphasis actions. Text should be centered, utilizing `label-lg` typography.

### Chips & Tags
Used for filtering and status. These are small pill shapes with `Secondary Container` backgrounds. They should feel like "pebbles" that are easy to tap.

### Input Fields
Filled text fields with maximum corner radius (pill-shaped). The label should float or sit inside the container to maintain the clean, rounded silhouette.

### Cards
Cards use `rounded-xl` (32px radius) rather than full pill shapes to maintain layout efficiency while staying consistent with the soft aesthetic. Use `Surface-Container-Low` for the background.

### Expressive Elements
- **Segmented Buttons:** Fully pill-shaped containers with a sliding background selector.
- **Progress Bars:** Thick (12px+) with rounded caps, mirroring the Slider track aesthetic.