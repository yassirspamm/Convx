---
id: 021-video-reference-review
status: pending
iteration: 0
iteration_cap: 5   # subjective: video-referenced feel-matching
worker_model: opus
worker_effort: high
verifier_model: opus
verifier_effort: high
depends_on: []
source: ui_glitches
---

## Goal

Six video files in `ui glicthes/` show desired animations/transitions or
screen-recorded bug repros that need frame extraction to act on (the Read
tool doesn't play video — pull representative frames first).

## Acceptance criteria

- For each video, extract a handful of representative frames (via
  whatever tool is available on the machine — check for `ffmpeg` on PATH
  first; if unavailable, this task stays blocked and should say so plainly
  rather than guessing at content from the filename alone).
- Four are named feature/look references (generate a sub-task per one once
  its content is understood, following the same "match as closely as
  achievable" pattern as task 020):
  - `newui _animataion_that/artistandplaylist screen animaton and blur an seacrch.mp4`
  - `newui _animataion_that/bounc animaition in glass items .mp4`
  - `newui _animataion_that/shared element morph transisiton .mp4`
  - `newui _animataion_that/topbarmicroanimationAndsharedtranstionAnimation.mp4`
- Two are screen recordings, likely bug repros, not look references —
  extract frames and generate a bugfix-shaped sub-task if a real defect
  is visible, otherwise note them as inconclusive:
  - `screen-20260831-125605-1788198961779.mp4~2.mp4`
  - `screen-20260831-130113-1788199260633.mp4`

## Context

This task's own iteration cap is small since "understand what a video
shows" either succeeds or the tooling genuinely isn't available — don't
burn many rounds on it if `ffmpeg` isn't installed; report that plainly
and move on rather than looping.
