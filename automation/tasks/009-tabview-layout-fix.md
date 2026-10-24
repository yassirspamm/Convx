---
id: 009-tabview-layout-fix
status: pending
iteration: 0
iteration_cap: unlimited   # objective: layout renders correctly or it doesn't
worker_model: sonnet
worker_effort: medium
verifier_model: opus
verifier_effort: high
depends_on: []
---

## Goal

Tab view UI layout is broken right now — find what's actually wrong on the
real device and fix it.

## Acceptance criteria

- No repro details given beyond "broken right now" — first round is
  investigative: build+install the current signed APK, exercise every tab
  view surface (side-bar drag puck, Library/Search/Home tab layouts — see
  project memory `tabview-progress-2026-08-10` for what was in progress),
  screenshot anything visually wrong, and identify the specific defect(s).
- Fix root cause, re-verify on-device with before/after screenshots.
- No regression to the drag-puck/Album/Playlist/Home hero work already
  shipped per that memory.

## Context

Tab-view work in progress per `tabview-progress-2026-08-10`: side-bar drag
puck + Album/Playlist/Home heroes done; Library/Search wide grids were still
open. Re-derive current state fresh — don't trust that memory as still
accurate, it's a point-in-time snapshot.

Reference look for the tablet/wide-layout artist page:
`ui glicthes/newui _animataion_that/tab ui playlist looks .jpg` (Taylor
Swift artist page, wide layout, glassy dark bottom toolbar) — match this
structure where the current wide-layout artist page diverges from it.
