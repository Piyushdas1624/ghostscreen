---
trigger: always_on
---

\# UI \& Theming Master Rules: Multi-Mood App



\## 1. Core Directives for the AI

\- \*\*Framework:\*\* \[Specify here: Jetpack Compose / Flutter]. 

\- \*\*No Hardcoded Colors:\*\* NEVER use raw hex codes (e.g., `#FF0000`) in UI components. 

\- \*\*Strict M3 Adherence:\*\* ALWAYS use Material 3 semantic tokens (e.g., `MaterialTheme.colorScheme.primary` or `Theme.of(context).colorScheme.primary`).

\- \*\*Contrast Guarantee:\*\* If a container uses a `\[color]` token (e.g., `primaryContainer`), its content MUST use the matching `on\[Color]` token (e.g., `onPrimaryContainer`). 



\## 2. Dynamic Theme Definitions

The app supports multiple environmental and emotional themes. The AI must map the underlying M3 color scheme to these specifications when generating the theme provider.



\### Base Modes (Reference App Defaults)

\* \*\*Light Mode:\*\* Clean, white/off-white surface (`#FBFDF8`). Primary accent is Earthy Brown/Orange (like the "Continue" button). Text is almost black (`#1C1B1F`).

\* \*\*Dark Mode:\*\* Deep grey/brown background (`#1A1A1A`). Primary accent is warm brown. Text is off-white (`#E6E1E5`).



\### Mood: Positive Mode

\* \*\*Vibe:\*\* Calming, uplifting, breathable.

\* \*\*Background:\*\* Subtle light blue (`#EAF4F4`).

\* \*\*Primary Action (Buttons):\*\* Subtle sage green (`#81C784`).

\* \*\*Text (onBackground):\*\* Deep Slate/Navy (`#1B263B`) to ensure high contrast and readability.

\* \*\*Text on Buttons (onPrimary):\*\* Very dark green (`#003300`).



\### Mood: Anxious Mode

\* \*\*Vibe:\*\* Grounding, acknowledging tension but not overwhelming.

\* \*\*Background:\*\* Little pinkish-red/blush (`#FFEBEE`).

\* \*\*Primary Action (Buttons):\*\* Muted Purple (`#9575CD`).

\* \*\*Text (onBackground):\*\* Deep Maroon/Eggplant (`#3E2723`) to maintain high visibility without being harsh black.

\* \*\*Text on Buttons (onPrimary):\*\* White (`#FFFFFF`).



\### Aesthetic: Cherry Blossom Glassmorphic

\* \*\*Vibe:\*\* Ethereal, layered, Japanese spring.

\* \*\*Background:\*\* Soft gradient from pale pink (`#FCE4EC`) to white.

\* \*\*UI Components:\*\* Do not use solid colors for cards/buttons. Use semi-transparent white (`rgba(255,255,255, 0.4)`) with a \*\*Blur/BackdropFilter\*\* effect to create glassmorphism. Add a subtle 1px white border to simulate glass edges.

\* \*\*Primary Action (Text/Icons):\*\* Deep Rose (`#880E4F`).



\### Aesthetic: Pastel Dream

\* \*\*Vibe:\*\* Soft, playful, dreamy.

\* \*\*Background:\*\* Pastel Lavender (`#F3E5F5`) or Pastel Yellow (`#FFF9C4`).

\* \*\*Primary Action (Buttons):\*\* Pastel Peach (`#FFCCBC`) or Mint (`#B2DFDB`).

\* \*\*Text (onBackground/onPrimary):\*\* Dark greyish-purple (`#4A148C`).

\* \*\*Typography Override:\*\* When this theme is active, switch the primary font family to a rounded, softer typeface (e.g., Nunito or Quicksand).



\### Aesthetic: Pitch AMOLED Black

\* \*\*Vibe:\*\* Maximum battery saving, sleek, high contrast.

\* \*\*Background:\*\* True Black (`#000000`).

\* \*\*Surface (Cards):\*\* Very dark grey (`#121212`).

\* \*\*Primary Action (Buttons):\*\* High-contrast vibrant color from the user's data (e.g., Bright Orange or Neon Purple).

\* \*\*Text (onBackground):\*\* Pure White (`#FFFFFF`) or light grey (`#E0E0E0`) to prevent eye strain.



\### Aesthetic: Grayscale (Old Days Film)

\* \*\*Vibe:\*\* Nostalgic, muted, cinematic.

\* \*\*Background:\*\* Vintage warm grey (`#D7D7D7`).

\* \*\*Primary Action (Buttons):\*\* Dark charcoal (`#424242`).

\* \*\*Text:\*\* Almost black (`#212121`) for light grey backgrounds, off-white for dark charcoal.

\* \*\*Special Effect:\*\* The AI must wrap the main screen layout in an overlay that applies a subtle noise/grain texture (using a repeating semi-transparent noise image or a shader) to simulate old film.



\## 3. UI Component Instructions

When the user asks you to build a component (like a "Notepad" or "Check-in Button"):

1\. \*\*Never ask what color it should be.\*\* Check the active theme above.

2\. If it's the main action, use `primary` and `onPrimary`.

3\. If it's a secondary element (like the unselected mood chips in the reference image), use `surfaceVariant` and `onSurfaceVariant`.

4\. Apply modifiers for special themes (e.g., `Modifier.blur` for Cherry Blossom, `Modifier.paint(noiseTexture)` for Grayscale) dynamically based on the current theme state.


5) completely bypassed Compose's default animation system for these specific elements! build robust, manual Coroutine ticker-loops (

rememberManualFloatAnim
 and 

rememberManualLoopAnim
). utilize an raw loop engine. That will make the  animations run smoothly without fail, even if the user entirely disabled animations at the system level! 



6) I need a strict map of all the local storage variables, Room entities, DataStore preferences, and schemas you have created or plan to create for this app (like Age, Interests, Journal Entries, Usage Logs for the behavioral AI, etc.).

Create a file named structuredatabase.md in the project root.
if it already exist in the workflow do not delte the older texts, keep the texts,and implement what you added with date and time. 

You MUST use this exact example of structural format for every entity/table:

Markdown
#### **1. Database/Table: `journal_entries`**
* **Type:** Room Entity
* **Purpose:** Stores all user journal entries and metadata.
* **Fields:**
    * `id` (Int, Primary Key, Auto-generate)
    * `title` (String)
    * `body` (String)
    * `mood` (String)
    * `stressLevel` (Int)
    * `timestamp` (Long)
Map out everything (Journal Entries, App Usage Logs, User Preferences) using this exact Markdown format.