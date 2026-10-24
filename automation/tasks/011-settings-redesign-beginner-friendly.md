---
id: 011-settings-redesign-beginner-friendly
status: pending
iteration: 0
iteration_cap: unlimited   # objective: each named change is either done or not
worker_model: sonnet
worker_effort: high
verifier_model: opus
verifier_effort: high
depends_on: [001-settings-audit, 012-glass-scope-navbar-miniplayer]
---

## Goal

Reorganize Settings into a beginner-friendly simple surface, with
advanced/customization-prone toggles moved to Advanced screens. No new
visual theme — reuse what exists.

## Acceptance criteria

- **Theme default**: `DarkModeKey` defaults to `AUTO` (device theme) if it
  doesn't already. Simple picker offers Light / Dark / (an explicitly
  device-theme-following) Auto — reusing the existing `DarkMode` enum
  as-is, no new visual variant.
- **AMOLED demoted**: `PureBlackKey` (true black OLED mode) moves out of
  the simple theme picker into an Advanced Appearance screen — it stays
  fully functional, just not a top-level choice for a beginner.
- **Glass default**: once task 012 lands (glass setting scoped to nav bar +
  mini player only), the simple settings surface shows ONE glass on/off or
  style choice for those two surfaces, defaulting to the fixed value the
  user will supply before this task runs (placeholder: check
  `automation/manifest.json` or a `GLASS_DEFAULT_STYLE` note left by the
  user — if genuinely not supplied yet, use the current
  `LiquidGlassStyleKey` default and flag it in the report rather than
  guessing).
- Every other toggle audited in task 001 as "advanced/customization-prone"
  (per that task's judgment, not re-litigated here) moves to its
  corresponding `*AdvancedSettings.kt` screen (pattern already exists:
  `AppearanceAdvancedSettings.kt`, `ContentAdvancedSettings.kt`,
  `PlayerAdvancedSettings.kt`).
- Simple settings screens verified on-device: a first-time user can find
  and understand every remaining top-level toggle without a description
  longer than one sentence.

## Context

`DarkMode` enum: `ui/screens/settings/AppearanceSettings.kt:798`. This task
depends on 001 (needs the settings audit's judgment on what's
"advanced-prone") and 012 (needs the glass-scope narrowing done first so
this task's glass default section has something real to point at).

Additional concrete moves the user annotated directly on screenshots
(treat these as confirmed acceptance items, not suggestions):
- Content settings screen: "Show Album Canvas" and the "App language" row
  don't belong there — move Album Canvas to wherever album-display options
  live (Appearance), and App language to General/System settings.
  (`ui glicthes/Screenshot_20260831-1141352.jpg`)
- Player Advanced settings: remove "Hide Volume Bar", "Compact player in
  tab view", and "Stop music on task clear" entirely (user marked these
  with a hard X, not a move) — confirm with the settings-audit task (001)
  whether these are genuinely low-value before deleting; if 001 finds real
  use, downgrade to "move to an even-more-advanced/hidden spot" instead of
  deleting. Move "Albums by release year" to Appearance settings. Move
  "Prevent duplicate tracks in queue" to sit under the Queue section
  header explicitly (currently ambiguous placement).
  (`ui glicthes/Screenshot_20260831-1145163.jpg`)
- Content Advanced settings: the 7 individual lyrics-provider enable
  toggles (LrcLib, KuGou, Better Lyrics, SimpMusic, YouLyPlus, PaxSenix,
  Musixmatch) should combine into one control — the existing "Lyrics
  provider priority" drag-order list should be the single source of truth
  for which providers are active, not a separate on/off toggle per one.
  (`ui glicthes/Screenshot_20260831-1146502.jpg`)
- DIY/Theme customization screen: user flagged the whole "Theme Mode /
  Background & Text Color" section as too complex — simplify or split into
  a basic vs. advanced sub-section within that screen.
  (`ui glicthes/Screenshot_20260831-1142332.jpg`)
- Settings root screen and its search bar: user explicitly said "this
  should be left alone" — do not touch this screen's layout.
  (`ui glicthes/Screenshot_20260831-1147592.jpg`)
- Settings "Appearance" row: user wants a blur/glass treatment added
  there (`ui glicthes/Screenshot_20260831-123909.png`, "Blur pls" note) —
  minor visual addition, not a restructuring.
