---
id: 002-ui-glitches-generator
status: done
iteration: 0
iteration_cap: unlimited   # objective: file is either manifested+task-generated or not
worker_model: sonnet
worker_effort: medium
verifier_model: sonnet
verifier_effort: medium
depends_on: []
---

## Goal

This is a meta-task, not a fix: every run, before anything else, scan
`ui glicthes/` (repo root, recursive) for files not yet in
`automation/manifest.json`'s `ui_glitches_processed` list, and generate one
new task file per new item.

## Acceptance criteria

- Every file under `ui glicthes/` (screenshots, and the
  `newui _animataion_that/` videos/photos subfolder) has a corresponding
  entry in the manifest and, if new this run, a generated
  `automation/tasks/NNN-glitch-<slug>.md`.
- Each generated task file: worker reads the actual image/video (Read tool;
  extract representative frames from video if needed), states in its own
  words what bug or desired look it shows, and sets acceptance criteria as
  "matches the reference as closely as achievable on native Android Compose,
  verified by a side-by-side screenshot against the reference file, taken
  on the real device."
- A bug screenshot (something broken) gets a bugfix-shaped task; a
  `newui _animataion_that/` video/photo (a desired look/animation) gets a
  feature/port-shaped task, explicitly noting it's a "match as closely as
  possible" job, not pixel-exact, since source material is iOS/other-app
  footage.
- Manifest updated immediately per file processed (not batched at the end),
  so a crash mid-scan doesn't regenerate duplicate task files next run.

## Context

`ui glicthes/` filenames as of the WIP checkpoint commit include (non-exhaustive,
re-scan for current state): several `Screenshot_2026083*.png/jpg` bug shots,
and `newui _animataion_that/` containing `artist look.jpg`, `playislist looks .jpg`,
`tab ui playlist looks .jpg`, `artistandplaylist screen animaton and blur an seacrch.mp4`,
`bounc animaition in glass items .mp4`, `shared element morph transisiton .mp4`,
`topbarmicroanimationAndsharedtranstionAnimation.mp4`.
