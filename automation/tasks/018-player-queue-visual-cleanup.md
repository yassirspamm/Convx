---
id: 018-player-queue-visual-cleanup
status: pending
iteration: 0
iteration_cap: 5   # subjective: "looks clustered" has no single correct layout
worker_model: opus
worker_effort: medium
verifier_model: opus
verifier_effort: medium
depends_on: []
source: ui_glitches
---

## Goal

The full player screen's "Continue Playing / Next in Queue" area looks
visually cluttered — user's words: "not looking too good, it's clustered."

## Acceptance criteria

- No specific fix prescribed — this is a spacing/hierarchy cleanup of the
  queue list section on the full player screen: shuffle/repeat/radio pills
  row, the "Continue Playing" label, and the queue rows themselves.
- Improve visual breathing room/hierarchy without removing functionality.
- Verified with before/after screenshot; judged by the verifier's own
  assessment since there's no single correct layout.

## Context

Reference: `ui glicthes/Screenshot_20260831-124044.png`.
