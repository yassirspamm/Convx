---
id: 017-liked-music-icon-size
status: pending
iteration: 0
iteration_cap: unlimited   # objective: icon at a defined size or not
worker_model: haiku
worker_effort: low
verifier_model: sonnet
verifier_effort: low
depends_on: []
source: ui_glitches
---

## Goal

The Liked Music playlist header's big thumbs-up placeholder artwork is
oversized — user explicitly said "bring it down a bit."

## Acceptance criteria

- Find the Liked Music fixed-playlist header artwork size (likely
  `LibraryMixScreen.kt`/`fixedPlaylist` or the auto-playlist header
  composable) and reduce it to be proportionate with other playlist
  headers on the same screen shape (compare against a normal
  album-art-backed playlist header for the right target size).
- Verified on-device with a screenshot.

## Context

Reference: `ui glicthes/Screenshot_20260831-124522.png`.
