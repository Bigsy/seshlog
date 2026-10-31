# Diff review feasibility — D1

Investigated 2026-10-31. **D1 establishes a feasible Codex 0.166.0 queue route.** The feature is
not yet available. Source inspection, synthetic contract/platform tests and an explicitly
authorized single-turn live check passed. Open-terminal integration and physical acceptance
remain separate requirements in D2–D7.

## Capability evidence

The installed executables reported **Codex CLI 0.166.0** and **Claude Code 2.1.312**. Help/version
inspection and Codex schema generation do not start an agent. The separately authorized live
check below started one disposable Codex thread and one model turn with fictional text. Agent
configuration was not changed; Codex may retain that synthetic thread in its normal history.

| Candidate | Address/input | Receipt | Busy/refusal policy | Limits | Gate result |
| --- | --- | --- | --- | --- | --- |
| Codex 0.166.0 `queue` CLI | `--thread <UUID> --message <TEXT>`; exact names also accepted upstream, but unsuitable for Seshlog identity | Source formats `Queued message <receipt> for thread <UUID>.` after a typed queue-add response | Explicit `--remote` connects to the selected server; the ordinary path can start one. `--resume` can load an unloaded thread and must be omitted | Source uses the server limits below; no stdin-message option in inspected help | Promising source-backed candidate; open-IDE integration not proved |
| Codex 0.166.0 app-server `thread/queue/add` | JSON-RPC `id`; params `threadId`, `clientUserMessageId`, `input`; experimental handshake | Response contains `queuedSubmission` with `id`, `clientUserMessageId`, `input`; no thread ID in that object. Source persists before replying | Queues for loaded or stored/unloaded threads; refuses missing/archived/ephemeral and certain subagent targets. Busy input waits for idle; an interrupted thread retains its queue | 100 queued items per thread; 1,048,576 Unicode scalar values across text inputs. Seshlog's proposed 128 KiB UTF-8 bound is smaller. Store generates a fresh row ID on enqueue | Candidate only; no tested version/capability discovery and terminal-validation integration |
| Claude Code 2.1.312 CLI | Top-level help and `agents --help` expose no addressed send operation | None established | No contract established for an ordinary open terminal session | Not established | Unavailable |
| Claude Code channels, current documentation | Previously configured MCP channel, opted into the receiving session | Notifications have no agent acknowledgement; policy can silently drop them | Requires user/session opt-in and applicable organization approval; cannot attach silently to an arbitrary running session | Not established for this feature | Does not satisfy this release's delivery contract |

The [official Codex command reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)
documents a queue command addressed by UUID or exact session name. The installed help additionally
lists `--resume`. Help/documentation alone does not define the receipt contract or receive policy;
the version-tagged source findings below add evidence.
The [app-server documentation](https://learn.chatgpt.com/docs/app-server) describes JSON-RPC and
Unix-socket/WebSocket transports. It does not document the inspected queue method's behavior.
This is an evidence gap, not a finding that Codex cannot support the feature.

The [Claude channel guide](https://code.claude.com/docs/en/channels) requires explicit session
opt-in. Its [protocol reference](https://code.claude.com/docs/en/channels-reference) states that
notifications are not acknowledged and can be dropped silently when the channel is not enabled
or policy blocks it. A successful channel write therefore cannot be reported as accepted.

Other providers were not investigated for direct delivery. Ordinary terminal command execution,
paste, Enter, approval responses and auto-resume are not substitutes for an addressed protocol.

## Local investigation and reproducibility

Commands executed, with the real CLIs used only for help/version or offline schema export:

```sh
codex --version
codex --help
codex queue --help
codex app-server --help
codex app-server generate-json-schema --help
codex app-server generate-json-schema --experimental --out /private/tmp/seshlog-diff-codex-0166-schema
claude --version
claude --help
claude agents --help
```

The installed npm entry point `@openai/codex/bin/codex.js` dispatches to a native binary; Rust
queue implementation source was not present in the installed package. The initial guessed path
`cli/src/queue.rs` returned 404. The upstream tag tree then located `queue_cmd.rs` and the files
below. No implementation semantics have been inferred from binary strings or another version.

Upstream tag `rust-v0.166.0` resolves to commit
`6ff1e1ca3b30fc8b914720681fcf5fe62e95f856`. Read-only source inspection covered:

- [CLI dispatch](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/cli/src/queue_cmd.rs),
  [queue command](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/tui/src/session_queue_commands.rs)
  and [server selection](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/tui/src/session_archive_commands.rs).
  Explicit remote selection connects directly. The default launch path can create a server;
  successful command exit does not establish that the target remains open in this IDE.
- [Queue request processor](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/app-server/src/request_processors/thread_queue_processor.rs),
  [queue service](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/ext/queue/src/service.rs),
  [queue store](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/state/src/runtime/queued_items.rs)
  and [text bound](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/protocol/src/user_input.rs).
  Persistence precedes the response. Queue-add also accepts stored/unloaded threads; the protocol
  does not enforce this feature's open-IDE-terminal requirement. A fresh queue row ID is generated
  for each enqueue; the client message ID should not be treated as an idempotency guarantee.
- [CLI tests](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/cli/tests/queue.rs),
  [server tests](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/app-server/tests/suite/v2/thread_queue.rs)
  and [service tests](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/ext/queue/tests/queue_service.rs)
  describe busy queuing, capacity refusal, interrupted queues, cold-thread resume, unsupported
  remote methods and prompt-hook refusal after queuing. They were read, not executed here.
  A queued receipt therefore cannot establish subsequent model consumption or action.

Source inspection also exposed an important uncertainty: internal errors can occur after
persistence. A regression first failed with such an error classified as rejected. The probe now
keeps internal/unknown server errors in the unknown state; known request refusals alone become
rejected. It never interprets those errors as permission to retry.

Schema files generated by that installed binary, SHA-256:

```text
v2/ThreadQueueAddParams.json
60f25b7d3e3357c215bef9d2fe7047200f545e030dca9e00527ac0620cd0c88f
v2/ThreadQueueAddResponse.json
c11b9772788427b19c9f9decf0765e2b41cbdead1aa9c3d39f42fdd70b339314
```

The text input variant has `type: "text"`, `text`, and optional `text_elements`. The schema also
exposes separate queue list/start methods. Source establishes that queue-add wakes a loaded idle
thread and preserves busy/unloaded queues; a controlled compatibility check is still outstanding.

`PLAN.md` was already empty in the working tree when this work began. It has been preserved.
The committed plan and [tracking report](codex-tracking.md) leave C7 physical acceptance open;
the [wrapper report](codex-terminal-wrapper.md) also describes separate acceptance evidence.
No Codex review-delivery identity acceptance is inferred from historical tracking tests.

## Implemented probes

All code and fictional inputs are in test sources/resources; none is a production transport.

- `NativeDiffCommentProbe` is installed through the real `DiffExtension` extension point in
  platform tests. A native `DiffRequestProcessor` creates `SimpleDiffViewer`. An opt-in request
  key scopes attachments to the probe request. The probe registers a keyboard shortcut on each
  editor and attaches an actionable gutter marker through the editor markup model.
- Platform tests cover old/current sides, a multiline selection ending at the next line's start,
  caret-line selection, empty added/deleted sides, concurrent viewers, and cleanup of markers
  and shortcuts on request replacement/disposal. They invoke the registered action with a key
  event; physical key dispatch, focus behavior and appearance have not been checked.
- `CodexQueueProtocolProbe` formats the installed schema's request shape, uses an exact UUID,
  correlates the response and client message IDs, and checks the echoed input. It calls an
  injected exchange function. There is no CLI, terminal, socket, network or history integration.
- `SyntheticReviewQueue` supplies hypothetical queue/hold/refuse/closed/lost-receipt outcomes.
  Synthetic receipt fixtures cover wrong IDs/payloads, incomplete data, notifications and
  malformed responses. Tests cover UTF-8 limits, Unicode/multiline text and shell metacharacters
  remaining JSON data. The proposed 128 KiB text bound is Seshlog's bound, not an upstream claim.
- Queued means queued by the hypothetical receiver, not consumed. Lost receipts stay unknown;
  there is no automatic retry. A repeated client message ID is deliberately allowed to duplicate
  in the stub, so it cannot accidentally establish upstream idempotency.

The synthetic receiver's error code, policy behavior and framing are test assumptions. These
tests validate candidate client handling, **not Codex or Claude compatibility**. No real CLI was
run against this receiver. Unified/one-sided presentations, a full comment panel, immutable
snapshots, draft ownership and actual delivery are still D2–D7 work.

### Live compatibility check — passed with explicit authorization

`src/test/resources/review/codex-queue-live-check.py` is an explicit, separate opt-in check. It
refuses to resolve/start an executable unless `SESHLOG_DIFF_REVIEW_LIVE=1` is present. Its Python
syntax and that guard were checked with a nonexistent executable; no agent was started.

After explicit user authorization, it checked the exact CLI version, started a local stdio app-server,
creates a disposable thread rooted in a temporary directory, and queues this exact message:

```text
Fictional Seshlog transport check; reply OK. Do not access files or run tools.
```

It verifies the queued receipt's correlation, client message ID and echoed input, then waits for
one completed turn. It prints aggregate evidence only. It never retries or answers approvals.
This uses the real agent's authentication/settings and starts a real model turn; the agent may
retain the synthetic thread under its normal history policy. The script does not edit agent
configuration or history files. Ending the local process cannot recall an already queued message.
It is not an open-terminal identity test and cannot alone accept D5/C7.

The user explicitly authorized this one check, satisfying D1's instruction to **“obtain explicit
authorization before model calls”**. Executed command:

```sh
SESHLOG_DIFF_REVIEW_LIVE=1 python3 src/test/resources/review/codex-queue-live-check.py \
  --codex /Users/hedworth/.local/bin/codex
```

Observed output (exit 0): `version=0.166.0 queued_receipts=1`, followed by
`completed_turns=1 real_model_turns=1`. No retries or approval responses were made. This proves
the installed binary's stdio queue receipt/completion path for this fictional thread; it does
not accept an existing Unix-socket connection or terminal identity on either IDE engine.

## Validation

`make check` and `make release` passed: 687 tests, 10 opt-in skips, no failures/errors; the D1
probes add 9 tests. Tests use IU-262.10968.63 (2026.2.3), JBR 25.0.4, Kotlin 2.3.21/API 2.3.
`./gradlew buildPlugin verifyPlugin --offline` passed. Verifier reports compatibility with
IU-262.11879.100 (2 scheduled-removal, 16 deprecated, 79 experimental usages) and IU-263.7307.112
(3 scheduled-removal, 20 deprecated, 72 experimental usages). It also reported SDK layout
entries with missing classpath elements. This D1 change contains no production classes.

ZIP integrity and recursive JAR inspection found no D1 probes, live script or fixture leakage.
`build/distributions/seshlog-0.8.5.zip` SHA-256:
`fede5162e907a73b203a556601744b63b22890b5cd85ef5c15183c5c3d1f6bbc`.
`git diff --check` passed. Native physical sandbox checks have not been performed; no artifact
was installed in the daily IDE or published.

## Work required to pass the gate

1. Prove the source-backed Codex candidate through an existing local receiver, without any
   launch/resume fallback. Implement and test version/capability discovery, handshake, bounded
   framing, deadlines and cancellation. Combine it with current open-terminal identity checks,
   including closed-thread races. The source proves useful semantics; the installed version's
   end-to-end compatibility and an eligible receiving terminal have not been verified here.
2. Validate that contract with an explicitly authorized controlled real-agent check using only
   fictional content. The probes here do not authorize a model call or a message to a real session.
3. Finish the applicable current Codex C7 identity acceptance. A schema-level thread address does
   not prove which thread occupies a live IDE terminal.
4. Physically check the native diff action, gutter decoration and keyboard/focus path in a freshly
   verified synthetic sandbox. Prove unified mapping separately or disable it explicitly.
5. If Codex passes, continue D2–D7 for that supported version. Keep Claude direct delivery
   unavailable until a suitable acknowledged protocol is established for it.
