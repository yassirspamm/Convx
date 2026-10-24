# Overnight fix pipeline

Read `ORCHESTRATOR.md` first (what a firing does), then `loop-structure.md`
(the full spec: task format, model tiering, git discipline, safety, the
build/verify pipeline). Task files live in `tasks/`, evidence in `progress/`.

## Trigger

Scheduled via `CronCreate` — **session-only**, not written to disk. It only
fires if this Claude Code session (and this PC) stays running until 3:30am.
It does NOT survive closing the terminal, sleeping the PC, or exiting
Claude Code. If you close this session before it fires, nothing runs.

Why not `/schedule` (cloud) or a disk-persisted cron: this pipeline needs
`adb`, your connected Android device, the local Android SDK/NDK, and your
local git checkout — none of which a cloud sandbox has. `CronCreate` is the
only native Claude Code primitive that runs in an environment with local
device access, at the cost of needing the session to stay alive.

**To re-arm it another night**: ask Claude to run `CronCreate` again with
the same prompt ("Read and follow automation/ORCHESTRATOR.md exactly") at
your chosen time — one-shot (`recurring: false`) needs a fresh call each
night; a `recurring: true` cron auto-expires after 7 days and needs
re-arming then too.

**To run it right now instead of waiting**: just ask Claude to read and
follow `ORCHESTRATOR.md` directly — no need to wait for the schedule.

**To check/cancel a pending fire this session**: `CronList` to see it,
`CronDelete` with its job id to cancel.

## What it needs from you, once

- The connected Android device stays connected and awake-able overnight
  (the pipeline handles keeping the screen on, see `loop-structure.md`).
- `gradle.properties` keeps the release-signing keystore password (already
  true as of this setup).
- New bug screenshots/videos dropped into `../ui glicthes/` are picked up
  automatically next firing — no need to tell Claude about them.
- The fixed default Glass style value for task `012-glass-scope-navbar-miniplayer`
  — you said you'd supply this later. Until you do, that task uses the
  current default and flags it as provisional in its progress report.

## Reviewing results

Every run's changes land on a fresh `auto/overnight-YYYYMMDD` branch off
`perf/render-120hz` — never on `main`, never pushed. Check `progress/*.md`
for the verifier's evidence on each task, and `tasks/*.md` front matter for
`status: done | blocked | in_progress` per task. A `blocked` task hit its
iteration cap (subjective/continuous goals only — see `loop-structure.md`)
and needs a human look.
