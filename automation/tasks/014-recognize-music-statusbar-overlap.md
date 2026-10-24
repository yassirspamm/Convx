---
id: 014-recognize-music-statusbar-overlap
status: pending
iteration: 0
iteration_cap: unlimited   # objective: overlap present or not
worker_model: haiku
worker_effort: low
verifier_model: sonnet
verifier_effort: medium
depends_on: []
source: ui_glitches
---

## Goal

The "Recognize Music" (Shazam-style) screen's top bar overlaps the system
status bar instead of sitting below it.

## Acceptance criteria

- Find the Recognize Music screen composable, add proper status-bar inset
  handling (`statusBarsPadding()`/`WindowInsets` per the pattern used
  elsewhere in the app, e.g. `appTopBarWindowInsets`).
- Verified on-device: title text and status bar icons no longer overlap.

## Context

Reference: `ui glicthes/Screenshot_20260831-123759.png` — clock/status
icons drawn directly over the "Recognize Music" title bar.
