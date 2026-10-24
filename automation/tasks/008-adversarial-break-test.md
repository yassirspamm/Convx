---
id: 008-adversarial-break-test
status: pending
iteration: 0
iteration_cap: unlimited   # objective: a fresh fuzzing/probing round finds something new, or it doesn't
worker_model: opus
worker_effort: high
verifier_model: opus
verifier_effort: xhigh
depends_on: []
---

## Goal

Actively try to break the app — both UI/UX robustness fuzzing and real
security probing of network-facing surfaces — on the real signed-APK
install, report everything found, then loop fixes until clean.

## Acceptance criteria

- **UI/UX fuzzing**: rapid/overlapping taps, rotation spam, back-button
  mashing during navigation/transitions, malformed playlist import files,
  killing the app mid-download, low-memory pressure (`adb shell am
  send-trim-memory`), airplane-mode toggling mid-playback/mid-sync, and any
  other bizarre real-user-adjacent abuse. Report every crash/ANR/stuck-state
  found with exact repro steps and a `logcat` excerpt.
- **Security probing**: the YouTube/Tidal/Saavn stream-URL fetch paths, the
  Discord bot integration (token handling — read-only, never actually
  posting or leaking), and any user-input-to-network path (search, URL
  paste, preset import — see project memory `preset-import-threat-model`
  for known-locked-down limits, don't relax those). Report any real finding
  (injection, unvalidated input reaching a sensitive sink, secrets in logs)
  — not theoretical concerns.
- For every finding: generate a follow-up fix task file
  (`automation/tasks/NNN-fix-<finding>.md`), do not fix inline in this task.
- This task's own loop only ends when a fresh fuzzing/probing round finds
  nothing new to report.

## Context

This task reports; it does not fix. Each finding becomes its own
worker/verifier-looped task file so a bad fix here can't be rubber-stamped
by the same agent that found the bug.
