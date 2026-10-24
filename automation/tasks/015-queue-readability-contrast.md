---
id: 015-queue-readability-contrast
status: pending
iteration: 0
iteration_cap: unlimited   # objective: WCAG-style contrast ratio check
worker_model: sonnet
worker_effort: medium
verifier_model: opus
verifier_effort: high
depends_on: []
source: ui_glitches
---

## Goal

Text on dynamically-tinted backgrounds (player queue rows, artist page body
text) can end up low-contrast/hard to read against certain hero colors.

## Acceptance criteria

- Player's "Next in Queue" row text and the artist page "About" body text
  both maintain a minimum readable contrast ratio (target: WCAG AA, 4.5:1
  for body text) against whatever hero/tint color is currently active,
  across at least 5 visually distinct source artworks (dark, bright,
  saturated, monochrome, mid-tone).
- Fix via the existing color engine's contrast-aware text color derivation
  (`ColorEngineCore.kt`) rather than a hardcoded override — extend it to
  cover these call sites if it doesn't already.
- This is a narrower, contrast-specific companion to task 006 (artist
  chroma tint) — coordinate so they don't fight each other's changes to
  the same files.

## Context

References: `ui glicthes/Screenshot_20260831-124136.png` ("Wrong color?"
annotation on artist bio text), `ui glicthes/Screenshot_20260831-124607.png`
("Forgot color issues too, like readability" annotation on queue rows).
