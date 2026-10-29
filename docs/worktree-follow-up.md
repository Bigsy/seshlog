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

The synthetic sandbox at `/private/tmp/seshlog-followup-sandbox` passed verification at
2026-10-29T21:06:02Z: IU-262.10968.63, JBR 25.0.4, macOS aarch64, plugin 0.8.3. All four providers
were READY and read synthetic storage only (147 Claude, 61 Codex, 13 opencode, 25 Pi sessions).
The loaded JAR matched the build. The final profile was subsequently regenerated for corrected
cross-checkout fork stubs; it passed again at 2026-10-29T21:13:05Z (IDE PID 39268). Its current report is
`<sandbox root>/verification.txt`.

Physical UI acceptance is **blocked by the UI automation attachment**: the computer tool listed
the sandbox as `Main` / `com.jetbrains.jbr.java`, but both names and its verified Java executable
path returned `Invalid app`. Attaching to `com.jetbrains.intellij` reached the daily IDE, which
was not used for testing. No physical rows are passed. Keyboard/theme/accessibility, native diff,
trust cancellation and actual terminal fork/inbox interactions still require manual checks.
Windows/Linux and real-CLI cross-checkout behavior also remain unverified.

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

## Attention inbox

Added Find Action/toolbar access to a modeless inbox across projects. Hidden sessions are excluded;
up to 200 newest unread sessions are shown with a total count. Selected previews read at most eight
recent messages and display up to 6,000 characters of assistant text. Previews remain in memory;
explicit Mark Read uses the receipt captured before loading. Errors and missing replies cannot
acknowledge a completion. Terminal/worktree navigation reuses the switcher's identity guards.

`make check`: 623 tests, zero failures/errors, 10 opt-in skips. Four tests cover cross-project
ordering/hidden exclusion, stale selection/content/disposal, receipt copying/newer-completion
protection, assistant-only selection and preview bounds. Physical acceptance remains pending.

## Package and compatibility validation

`make release` passed; ZIP integrity and nested-JAR inspection found no sandbox classes, fixtures,
transcripts, databases or models. All three new action registrations are in the descriptor.
Artifact: `build/distributions/seshlog-0.8.3.zip` (development build, version not bumped).
SHA-256: `47f48c48ad84b35e2c7334841e7d235542ccb3728cbe31f8028ca044bf1fab3c`.

`./gradlew buildPlugin verifyPlugin --offline` passed. Plugin Verifier 1.411 reports Compatible:

| IDE | Scheduled removal | Deprecated | Experimental |
| --- | ---: | ---: | ---: |
| IU-262.10968.63 | 2 | 16 | 67 |
| IU-263.7307.44 | 3 | 20 | 60 |

No new feature classes appear in the API-usage warnings. The new artifact was not published or
installed in the daily IDE. These checks do not establish physical acceptance.

## Synthetic fork regression and final tests

The old stub agents retained their source cwd when forking, which would invalidate future physical
checks of the new-worktree flow. A new synthetic integration regression failed before correction.
Claude forks now record their launch directory; Codex forks honor explicit `--cd` and record it in
new session metadata. The integration test creates real disposable worktrees, forks both stubs,
rescans through production providers and confirms new IDs/destinations while source sessions remain
unchanged. Stubs are test-only and never packaged.

Final `make check`: **624 tests, zero failures/errors, 10 opt-in skips**. The refreshed profile
contains these stubs. No manual UI or real-model/CLI behavior is claimed by this test.
