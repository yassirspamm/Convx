---
id: 019-dj-sheet-handle-position
status: pending
iteration: 0
iteration_cap: unlimited   # objective: layout position either matches spec or not
worker_model: haiku
worker_effort: low
verifier_model: sonnet
verifier_effort: low
depends_on: []
source: ui_glitches
---

## Goal

The DJ/Auto-DJ player sheet's top drag handle sits too high / too close to
the status bar — user's words: "this is wayyyyy up bring it down a bit."

## Acceptance criteria

- Find the DJ mode player sheet's top handle/spacing and adjust so it has
  normal breathing room from the status bar, matching the spacing other
  bottom-sheet-style player surfaces in the app use.
- Verified on-device with a screenshot.

## Context

Reference: `ui glicthes/share_8767541273537082027.png` — DJ Auto-DJ track
("What Did I Miss?") with the drag handle circled right against the status
bar.
