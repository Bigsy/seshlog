# Worktree follow-up — 2026-10-29

## Deletion hardening

Protected storage checks now reject overlap in either direction for the checkout, per-worktree
Git directory and common Git directory. The UI rechecks current session liveness; the queued
removal worker repeats a completed provider scan, checkout/branch resolution, project capture,
protected-root and terminal checks before calling Git. Empty-session refresh failures no longer
fall back to cached evidence. No filesystem/process work runs on the EDT.

Three regression tests failed before the fix, then passed. `make check` passed 612 tests with
zero failures/errors and 10 opt-in skips. Existing real-Git tests cover clean deletion and dirty
refusal. External processes can still change files or Git state after validation; these checks
are not an operating-system lock. The per-root operation guard coordinates Seshlog actions only.

## Physical acceptance

Pending: a newly verified synthetic sandbox and the checklist in PLAN.md. No physical checks
have been passed by automated tests. Review, creation and inbox validation will be recorded here.
