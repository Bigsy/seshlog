# Review checkout changes and send comments

Implemented 2026-10-31 for IntelliJ 2026.2 / build 262, JDK 25, Kotlin API 2.3.

## Using the review

1. Select a Seshlog session and choose **Review Changes and Comment…**. A review tab opens in
   IntelliJ's **Commit** tool window, or **Changes** with the classic VCS layout. The provider,
   exact session ID and checkout remain visible. The project needs an available VCS tool window;
   Safe Mode can disable it. Use the session's checkout window in that case.
2. Choose a local comparison base, initially `HEAD`. Loading the first file fixes it to a commit
   ID. **Files** uses the native Changes tree, directory grouping and Show Diff. These are checkout
   changes from all contributors. **Recorded edit files only** filters incomplete tool records,
   which do not establish authorship and can omit shell edits.
3. Open a native diff editor tab. Select real lines on either side and use the **+** gutter action,
   **Add Review Comment** in the editor popup, or **Ctrl+Alt+Shift+K**. Saved comments have a gutter
   marker. The note editor shows the selected excerpt and recipient and retains input after errors.
4. **Comments** collects notes across files. Double-click one to focus its existing diff tab/range.
   Edit or remove notes, add an **Overall Note**, and use **Preview** or **Copy Review** to inspect
   the exact outgoing message. Cancel leaves the saved note unchanged.
5. **Send N comments** validates source files and the exact open recipient, then sends one frozen
   message. **Go to Session** only focuses the verified original terminal; it never resumes,
   launches or chooses a replacement.

Only Seshlog's session-bound diff tabs acquire comments. Ordinary Commit diffs do not implicitly
choose a recipient. Comments currently support **side-by-side text diffs**; other presentations
explicitly instruct the user to switch. Binary files, empty sides, symlinks, submodules and oversized
files retain existing restrictions. The ordinary folder review remains unbound.

The right side is an immutable **on-disk snapshot**. Unsaved buffers block sending until saved and
refreshed; Seshlog never saves them. Newer files mark comments **Needs recheck**. Refresh preserves
notes and old anchors. Inspect the refreshed diff, then explicitly confirm an unchanged selection,
or remove/reselect it. There is no guessed relocation. Files can change after final validation;
the message includes revision hashes and asks the agent to check current code.

Closing a tab or changing project windows keeps the draft. Reopening the same provider, session and
checkout reuses it. **Discard Review** is explicit. Drafts and read-only **Sent Batches** stay in
memory until IDE exit/crash; they do not survive restart.

## Direct-send support

| Receiver | Support |
| --- | --- |
| Codex daemon reporting exactly **0.166.0**, macOS with readable `lsof` Unix peer evidence | Direct queue delivery through an existing private local socket. The selected live TUI must have a connected peer on that exact endpoint. Matching Codex home, loaded exact thread and checkout are also required. |
| Other Codex versions, remote/embedded/unknown launch arguments, unloaded/ambiguous thread or missing/untrusted socket | Unavailable; copy/navigation remain. No launch, resume or terminal fallback. |
| Claude Code **2.1.312** / documented channels | Copy/navigation; a suitable acknowledgement contract was not established. |
| opencode / Pi | Copy/navigation; direct messaging was not established. |
| Linux / Windows / unavailable peer evidence | Copy/navigation; direct delivery is unavailable until a reliable endpoint-binding backend is implemented. |

**Queued by agent** confirms a correlated receipt, not consumption, approval or completion.
Busy input remains in Codex's queue. Known refusals are **Rejected**. An unverified route/recipient
is **Unavailable**. Lost/mismatched receipts and possible errors after persistence are **Delivery
unknown**. Failure/unknown keeps notes. Explicit retry after unknown warns of a possible duplicate;
nothing retries automatically. An earlier unknown outcome remains unresolved after a failed retry
or a receipt for a different batch; the warning persists until explicit draft discard. Local
cancellation after dispatch cannot recall a queued message.

Both validation passes require a successful fresh scan with healthy Codex storage; scan timeouts,
failures and incomplete scans cannot fall back to cached session metadata. Process arguments can
exclude a route but cannot prove the configured daemon is the terminal's daemon. macOS delivery
joins the selected process's live Unix peer descriptor to the socket's named server descriptor,
then checks the socket identity again before writing. These bounded descriptor reads stay in
memory and do not log paths, environment values or kernel addresses.

Only included comment revisions are archived by a genuine receipt, even if the view closes or the
terminal later switches. New comments/edits remain unsent. Saving an editor opened before its
revision was archived restores the new edit as an unsent revision. Each sent batch keeps its own
receipt and exact read-only payload.

## Bounds and privacy

- 10 drafts, 50 comments each, 4,000 characters per note, 20 selected excerpt lines / 4,000 characters,
  128 KiB UTF-8 per outgoing message. Across reviews, retain at most 32 MiB of snapshot text plus
  a separate 2 MiB of comments, overall notes, pending/sent batches and receipt state (34 MiB of
  accounted retained text in total, excluding object/platform overhead). Snapshot loading cannot
  consume the state allowance: even 8 MiB ASCII on each side leaves room to comment and submit
  an otherwise empty review. Pending batches reserve receipt space before dispatch; accepted
  batches remain charged until explicit draft discard. Snapshots and readonly native documents
  share immutable buffers, including independent tabs; each retained snapshot is
  counted once. Unchanged refreshes reuse it; old revisions remain charged while open. Only actual
  processor disposal releases editor ownership; presentation changes keep both sides and the tab.
  Closing the last processor clears the request, while any text still owned by a draft stays charged.
  Close older diff tabs or explicitly discard drafts to free snapshot space; shorten notes or
  discard reviews to free state space. Extra content is rejected with an explanation; unsent notes
  are never silently evicted or truncated.
- Existing Git limits: 8 MiB per file side and 5,000 files. Display line endings are normalized;
  revision hashes still cover the original raw bytes. Resolved bases, file existence, hashes and checkout identity
  protect against moving refs, malformed UTF-8, replaced checkouts and stale child diffs.
- Filesystem/Git/hash/socket work runs on workers. UI, terminal state and unsaved-document metadata
  are captured on the EDT with generation/disposal guards.
- Seshlog does not persist draft text, excerpts, payloads or receipts, or index the review service's
  contents. Diagnostics contain opaque references, counts, byte lengths and bounded outcomes only.
- **Explicit send hands the review to the selected agent.** The agent may save it and send it to its
  model under normal settings. Seshlog never edits those histories itself. Agent-saved messages
  subsequently follow normal history search policy: lexical terms/positions can be indexed without
  a raw transcript copy. Preview/copy never call a model.

Delivery uses bounded JSON-RPC/WebSocket over an existing Unix socket owned privately by this user.
There is no shell interpolation, message file, keystroke injection, input clearing, approval response,
agent/server launch or model-endpoint call. Jobs have cancellation/deadlines and bounded frames,
fragments, notifications and total reads.

## Upstream evidence

Installed versions inspected on 2026-10-31: Codex **0.166.0**, Claude Code **2.1.312**. Help/version and
offline schema export did not start an agent. The [Codex command reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)
describes the addressed queue; [app-server documentation](https://learn.chatgpt.com/docs/app-server)
describes JSON-RPC and Unix/WebSocket transports. The experimental queue contract required source
inspection, so other receiver versions remain unavailable.

Tag `rust-v0.166.0` resolves to commit `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856`:

- [CLI dispatch](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/cli/src/queue_cmd.rs),
  [queue command](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/tui/src/session_queue_commands.rs)
  and [server selection](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/tui/src/session_archive_commands.rs)
  show that ordinary CLI delivery can launch a server and `--resume` can load a stopped thread.
  Production Seshlog instead connects directly to the existing socket.
- The [request processor](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/app-server/src/request_processors/thread_queue_processor.rs),
  [service](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/ext/queue/src/service.rs)
  and [store](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/state/src/runtime/queued_items.rs)
  define `thread/queue/add`: `threadId`, `clientUserMessageId`, `input`; response
  `queuedSubmission` echoes client ID/input with a provider receipt ID after persistence.
  Capacity is 100 items/thread; [text limit](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/protocol/src/user_input.rs)
  is 1,048,576 Unicode scalar values. Seshlog's 128 KiB bound is smaller. Fresh row IDs are not
  an idempotency guarantee. Internal errors can follow persistence and must remain unknown.
  Upstream also queues stored/unloaded threads; Seshlog additionally requires a loaded thread
  and verified open IDE terminal.
- [Server tests](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/app-server/tests/suite/v2/thread_queue.rs)
  and [service tests](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/ext/queue/tests/queue_service.rs)
  describe busy queues, capacity/policy refusal, interrupted queues and cold-thread behavior.
  They were inspected, not executed here. A later prompt-hook refusal can follow a queue receipt.

The [Claude channel guide](https://code.claude.com/docs/en/channels) requires prior session opt-in.
Its [protocol reference](https://code.claude.com/docs/en/channels-reference) permits silent drops
without an agent acknowledgement, which does not meet this release's receipt requirement.

### Authorized live compatibility check

The user authorized exactly one disposable model turn with fictional text. The guarded test script
checked the exact CLI version, started a stdio app-server/thread under `/private/tmp`, queued
“Fictional Seshlog transport check; reply OK. Do not access files or run tools.” and waited for one
completed turn:

```sh
SESHLOG_DIFF_REVIEW_LIVE=1 python3 src/test/resources/review/codex-queue-live-check.py \
  --codex /Users/hedworth/.local/bin/codex
```

Exit 0: `version=0.166.0 queued_receipts=1`, `completed_turns=1 real_model_turns=1`. No retries or
approval responses. The script did not edit configuration/history; Codex may retain that fictional
thread normally. Without the opt-in, the guard rejected a nonexistent executable before resolving
or launching it. This proves the installed binary's stdio queue/completion path, separately from
existing-TUI socket/terminal identity. No private histories or additional model turns were sent.

## Tests and physical evidence

Pure tests cover formatting, bounds, snapshots, raw-byte changes, moving bases, unsaved buffers,
immutable batches, duplicate locks, newer edits, late receipts, cancellation and diagnostics.
Platform tests use the registered DiffExtension, native viewers/actions/disposal, Changes nodes,
Commit content ownership, editor-tab reuse/range navigation and a 360×580 layout regression.
A VCS-unavailable regression prevents invisible pending review tabs.

Real private Unix WebSocket tests cover handshake, framing/fragmentation/ping, strict UTF-8, bounds,
permissions, version/home/thread/checkout checks, late revocation, refusal, deadlines, interruption
and uncertain receipts. `ReviewSyntheticEndToEndTest` launches a generated queue and two fictional
TUIs, then uses the production provider/adapter to prove holding/release, one addressed batch across
files/sides, refusal, lost receipts and two-TUI swaps. Stubs prove integration, not upstream behavior.

Physical checks used only `/private/tmp/sl-diff-20261031`, its fictional histories and signed local
`launcher/Seshlog Review Sandbox.app`: IU-262.10968.63, JBR 25.0.4, macOS aarch64. Synthetic
verification passed before recorded UI runs, proving plugin/helper hashes and synthetic scan roots.

| Physical check | Observed result |
| --- | --- |
| Reworked, docked Commit and native editor tabs; PID 58121, verified 15:34:07 UTC | Three comments across README current/base and Backoff current; one previewed batch; queue receipt while held, then exactly one consumed message after release. |
| Refuse / lost receipt / partial input | Rejection and unknown retained the note. Retry warned about duplication; No sent nothing. Unsubmitted terminal input was untouched. |
| Thread switch and newer file | `/new` made the original recipient unavailable without replacement delivery. Refresh preserved a changed-file note as Needs recheck and disabled send. |
| Classic, final Files/Comments layout; PID 59875, verified 15:41:19 UTC | Two comments across README current and Backoff base; tab/range navigation; cancelled edit preserved original. One queued batch, archived receipt/payload and one consumed message. |
| Two project windows | Opened harbor-retry in Safe Mode without broadening trust. Harbor delivery stayed addressed to its Classic TUI with both windows open. The absent Safe Mode VCS window led to an explicit unavailable response and regression. |
| Appearance/layout | Dark and Islands Light inspected; gutter and keyboard entry worked. Terminal-visible layout exposed a collapsed-list bug; Files/Comments tabs fixed it, with a passing 360×580 regression and visible controls. |

The integrated Reworked recipient has one queued, one rejected and one lost receipt entry, with
two delivered review messages. Classic has one queued entry and one delivered message. No queue
files remained after release. Rotating diagnostics contained none of the synthetic notes/excerpts.

Physical cancellation during an outstanding socket write, simultaneous two-TUI swapping and unsaved
buffer editing are automated-test evidence only. Windows/Linux appearance, unified comments, real
existing-TUI socket delivery and the broader tracking C7 matrix were not physically accepted here.
No daily-IDE install or publishing occurred. The follow-up restored the original authoritative
`PLAN.md` and preserved its open Codex tracking and search work. Physical acceptance of the new
safety gates and native diff memory lifecycle remains open.

## Release validation

Boundary follow-up, 2026-10-31 (R10): the exact 8 MiB ASCII-per-side regression failed on its first
short comment before the fix. The unchanged 32 MiB snapshot allowance is now independent of 2 MiB
for review state, including frozen overall notes. Tests cover both-side comments, overall notes,
submission, a maximum-length receipt, newer edits and a subsequent attempt at that boundary.
Extra snapshot text is still rejected. A separate two-draft regression fills the state allowance
with sent batches, verifies failure preserves notes without a pending submission, then frees
capacity through explicit discard. `make check` passed **742 tests, 10 opt-in skips, zero
failures/errors**. `make release` and recursive ZIP/JAR inspection passed (no test fixtures/helpers).
Artifact: `build/distributions/seshlog-0.8.5.zip`, SHA-256
`ad1766b5911030318a9438b81a353806ef104ca42a5f00f3cdf1626c3df33e9b`.
Offline Plugin Verifier passed for IU-262.11879.100 (2 scheduled-removal / 16 deprecated / 79
experimental usages) and IU-263.7307.112 (3 / 20 / 72); existing API and SDK layout warnings remain.
No new physical IDE acceptance or real model turn was performed; D7 remains open.

Second reliability follow-up, 2026-10-31 (R7–R9): forced request refresh, duplicate text charging
and platform availability each reproduced before the fix. Cleanup now follows actual native
processor disposal. Snapshots and independent native documents share the same immutable buffers;
the 5 MiB-per-side case opens within 32 MiB, unchanged refreshes reuse text, and old revisions stay
bounded. Linux/Windows controls and service preflight report platform support before target checks.
Clean compilation and `make check` passed **740 tests, 10 opt-in skips, zero failures/errors**.
An intermediate cached build restored stale constructor callers; the preserved ordinary panel
constructor and fresh compilation resolved it. The ordinary cached `make release` then passed the
same 740 tests, built the ZIP and passed recursive ZIP/JAR inspection with no fixtures/helpers.
Artifact: `build/distributions/seshlog-0.8.5.zip`, SHA-256
`5451e2dd547e6f7177a37b6310c528b3c2d2cb634abfc2dcd395b95f65c90f8e`.
Offline Plugin Verifier passed for IU-262.11879.100 (2 scheduled-removal / 16 deprecated / 79
experimental usages) and IU-263.7307.112 (3 / 20 / 72); SDK layout warnings remain.
No new physical IDE acceptance or real model turn was performed; D7 remains open.

First reliability follow-up, 2026-10-31: all six reported issues are addressed and tracked as R1–R6 in
`PLAN.md`. The original 710 plan lines and all 50 existing open checklist entries are preserved.
Regressions reproduced each unsafe behavior before its fix. New coverage includes two synthetic
homes with the same loaded thread/checkout, real Unix peer inspection, socket replacement, failed
refresh at both validation passes, persistent unknown outcomes, deletion recreated empty, and real
native editor processors/splits retaining old revisions. `make check` executed **734 tests,
10 opt-in skips, zero failures/errors**. `make release` passed; recursive ZIP/JAR inspection found
no test fixtures or sandbox helpers. Current artifact: `build/distributions/seshlog-0.8.5.zip`,
SHA-256 `28c31e021aad9f3fc1409a71d038338f8c4d58a95c2b569d30dba7753fef5547`.
Offline Plugin Verifier passed for IU-262.11879.100 (2 scheduled-removal / 16 deprecated / 79
experimental API usages) and IU-263.7307.112 (3 / 20 / 72); SDK layout warnings remain.
The follow-up did not run new real model turns or new physical IDE acceptance.

Baseline before the reliability follow-up: `make check` and `make release` passed with **724 tests,
10 opt-in skips, zero failures/errors**. The hashes and physical verification below refer to that
earlier build, not acceptance of the follow-up fixes.
Final `./gradlew verifyPlugin --offline` passed on IU-262.11879.100 (2 scheduled-removal,
16 deprecated, 79 experimental usages) and IU-263.7307.112 (3 / 20 / 72). SDK layout warnings about
missing classpath entries remain. The first 263 verification exposed VCS's extraction into bundled
`com.intellij.platform.vcs`; an explicit optional dependency/descriptor fixed class visibility while
retaining 262. This follows the [JetBrains dependency declaration rules](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html).

The final synthetic verification at **15:57:05 UTC**, PID **65331**, passed with plugin JAR SHA-256
`8d2cc80dcb6cf14dec63fcb900e554228f25a388c07f74afaa61e931382aa334`.
The report is `/private/tmp/sl-diff-20261031/verification.txt`. Final UI checks confirmed normal
Commit placement and the explanatory unavailable-VCS dialog in the Safe Mode second window.
The synthetic queue helper was stopped with no pending messages; the review IDE remains available.

ZIP integrity and recursive JAR inspection found no test classes, fixtures, sandbox helpers, live
script or obsolete floating review browser. `git diff --check` passed. Artifact:
`build/distributions/seshlog-0.8.5.zip`, SHA-256
`aa40a67669084a3e192c662d6d38bcc95682d1f080a1e8da3aec6c2da869bb81`.
