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

Physical UI acceptance was initially **blocked by the UI automation attachment**: the computer tool listed
the sandbox as `Main` / `com.jetbrains.jbr.java`, but both names and its verified Java executable
path returned `Invalid app`. Attaching to `com.jetbrains.intellij` reached the daily IDE, which
was not used for testing. The user subsequently confirmed that **Seshlog: Attention Inbox** opens
through Find Action in the still-running sandbox (2026-10-29). This is a user-reported launch
check only. Attachment was subsequently repaired using a sandbox-local macOS app bundle, as described
in [the sandbox guide](synthetic-sandbox.md#macos-computer-use-attachment). The relaunched IDE
(PID 42619) passed verification at 2026-10-29T21:29:44Z with the same plugin JAR and all four providers
reading synthetic storage. Computer Use attached to `harbor – README.md`, invoked Find Action by
keyboard, and opened **Seshlog Attention Inbox**. Its empty state and disabled row actions were read
through accessibility. Subsequent physical checks completed as follows:

| Flow | Observed result |
| --- | --- |
| Review Worktree Changes | Clean HEAD showed no changes; synthetic modified/untracked files appeared after Refresh. Show Diff opened a native README diff. HEAD~1 included the committed CHANGELOG; an invalid ref displayed an error and disabled Show Diff. |
| Claude new-worktree fork | Created `acceptance/claude-fork` in `harbor-fork`, opened a new trusted window and launched the synthetic fork with a new ID and destination cwd. The new checkout stayed clean; modified/untracked source files remained in the source. |
| Attention inbox | A synthetic reply appeared with its assistant preview and enabled row actions. Previewing left it unread; Mark Read removed it and restored the empty state. |
| Filters → Agents | Found a disappearing popup that prevented provider selection. Replaced it with a native IntelliJ submenu; verified All agents exposed Codex sessions. |
| Codex new-worktree fork | Created `acceptance/codex-fork` in `harbor-codex`, opened a new trusted window and launched the synthetic fork with explicit `--cd`, a new ID and the correct destination cwd. |
| Delete Worktree | Cancel preserved `harbor-retry`. With a synthetic untracked guard file present, Delete was refused; both the checkout and file survived. No force deletion was used. |

The submenu fix has a regression that failed before the change. Final `make check` passed
**625 tests, zero failures/errors, 10 opt-in skips**. The rebuilt sandbox (PID 47091) passed
verification at 2026-10-29T21:43:33Z before the final UI checks. Its loaded JAR matched the build:
`fd25ec043d937909ce34d6294b08d7a389f01a552a8cd8c9a01905bd13ff8ebd`.
All four providers were READY and synthetic; counts then were 148 Claude, 61 Codex, 13 opencode
and 25 Pi sessions. The subsequent Codex fork added one synthetic session.

Dirty deletion refusal is also recorded in `<sandbox root>/ide/log/seshlog/`, run
`cebf31b7-9745-47e9-9402-e468ceda8f3f`, request 86 at 2026-10-29T21:47:36–37Z:
`worktree_delete_result` reports Git exit 128, followed by `worktree_delete_failed`.

This completes the physical smoke pass, not the full acceptance matrix. Successful deletion with
hide/keep choices, locked-worktree refusal, trust cancellation, existing-window navigation,
source-terminal survival, inbox navigation/newer-reply races, and full keyboard/accessibility/theme/
narrow-layout coverage remain open. Windows/Linux and real-CLI cross-checkout behavior also remain
unverified. The broader original worktree edge cases remain listed in PLAN.md.

## Review Worktree Changes

Added session/folder actions and a modeless base-ref/file-list dialog with native IntelliJ diffs.
Git output and current files are bounded and stay in memory; no temporary source copies. Reads
revalidate checkout identity before/after and reject symlink paths. Request generations discard
stale results, cancellation stops pending reads, and disposal closes the worker.

`make check`: 615 tests, zero failures/errors, 10 opt-in skips. Three synthetic real-Git tests
cover committed/staged/unstaged/untracked changes, NUL-delimited unusual filenames, rename/delete,
invalid refs, replaced identities, symlinks, binary content, oversized files and Git output limits.
Physical smoke results are recorded above; broader acceptance remains open.

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
and destination command quoting. Physical smoke results are recorded above; broader acceptance remains open.

## Attention inbox

Added Find Action/toolbar access to a modeless inbox across projects. Hidden sessions are excluded;
up to 200 newest unread sessions are shown with a total count. Selected previews read at most eight
recent messages and display up to 6,000 characters of assistant text. Previews remain in memory;
explicit Mark Read uses the receipt captured before loading. Errors and missing replies cannot
acknowledge a completion. Terminal/worktree navigation reuses the switcher's identity guards.

`make check`: 623 tests, zero failures/errors, 10 opt-in skips. Four tests cover cross-project
ordering/hidden exclusion, stale selection/content/disposal, receipt copying/newer-completion
protection, assistant-only selection and preview bounds. Physical smoke results are recorded above;
broader acceptance remains open.

## Package and compatibility validation

`make release` passed; ZIP integrity and nested-JAR inspection found no sandbox classes, fixtures,
transcripts, databases or models. All three new action registrations are in the descriptor.
Artifact: `build/distributions/seshlog-0.8.3.zip` (development build, version not bumped).
Final rebuilt ZIP SHA-256: `9a896e0d8430b92c2511d8f7c0522e254c672ef2bba84bc0556f0eb21ea2d34a`.
ZIP integrity passed again after the submenu fix.

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

At this stage `make check` passed **624 tests, zero failures/errors, 10 opt-in skips**. The refreshed profile
contains these stubs. No manual UI or real-model/CLI behavior is claimed by this test.
