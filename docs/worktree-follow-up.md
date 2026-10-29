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

## Review Worktree Changes

Added session/folder actions and a modeless base-ref/file-list dialog with native IntelliJ diffs.
Git output and current files are bounded and stay in memory; no temporary source copies. Reads
revalidate checkout identity before/after and reject symlink paths. Request generations discard
stale results, cancellation stops pending reads, and disposal closes the worker.

`make check`: 615 tests, zero failures/errors, 10 opt-in skips. Three synthetic real-Git tests
cover committed/staged/unstaged/untracked changes, NUL-delimited unusual filenames, rename/delete,
invalid refs, replaced identities, symlinks, binary content, oversized files and Git output limits.
Physical acceptance remains pending.

## Fork into New Worktree

Added a local-ref/branch/destination dialog and creation orchestration with preserved partial
success. Source session and checkout are revalidated before creation and after trust/import.
The source/destination participate in the same pending-operation set as opening/deletion.
Creation maps subdirectories, rejects existing/nested/protected destinations, disables checkout
hooks and leaves dirty/untracked files in the source. It never deletes partial results.

Native forks initially support Claude and Codex. Codex receives explicit `--cd`; Claude resumes
by ID within the shared Git repository and forks from the terminal's destination directory.
References checked 2026-10-29: [Codex CLI](https://learn.chatgpt.com/docs/developer-commands)
and [Claude CLI](https://code.claude.com/docs/en/cli-reference). These references establish command
contracts; synthetic stubs do not establish end-to-end behavior of every installed CLI version.

`make check`: 619 tests, zero failures/errors, 10 opt-in skips. Four new tests cover real-Git
creation, dirty-source preservation, subdirectory mapping, hooks, protected/nested/existing paths,
invalid refs/branches, missing subdirectories, cancellation, stale destinations, launch errors
and destination command quoting. Physical checks remain pending.
