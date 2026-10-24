---
id: 020-album-playlist-detail-look
status: pending
iteration: 0
iteration_cap: 5   # subjective: "match as closely as achievable" has no exact target
worker_model: opus
worker_effort: high
verifier_model: opus
verifier_effort: high
depends_on: [006-artist-chroma-tint]
source: ui_glitches
---

## Goal

Bring the album/playlist detail screen closer to an Apple-Music-style
layout: large square artwork card, a pill-shaped Play/shuffle/download
button row, genre/year/lossless-badge metadata line, and a clean numbered
tracklist.

## Acceptance criteria

- Match the reference images as closely as achievable on native Android
  Compose — not pixel-exact (source is Apple Music screenshots), but the
  same structural layout and visual hierarchy.
- Depends on task 006 landing first since both touch color/chroma
  derivation on a detail screen — avoid conflicting changes.
- Verified with a side-by-side screenshot against the reference.

## Context

References: `ui glicthes/newui _animataion_that/artist look.jpg` (Cleo Sol
"Gentlewoman" album screen) and
`ui glicthes/newui _animataion_that/playislist looks .jpg` (Assata Jones
"Raw Love" album screen) — both show the same target layout despite the
"artist look" filename; neither is actually an artist page.
