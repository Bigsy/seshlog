# Plan: agent worktree tracking and opening in IntelliJ

Prepared 2026-10-24. This is an implementation handoff; implementation started 2026-10-25.
Read `AGENTS.md` first. Work through the numbered items in order, one commit per item, with no
co-authorship or generated-by trailers. Tick an item only after its regression tests pass under
`make check`; leave any outstanding `make run` checks explicitly recorded. References below use
symbol names rather than line numbers: re-find the symbols before editing.

## Goal and agreed scope

The user wants to see which Git worktree an agent is working in and right-click the session to
open that checkout in IntelliJ. **Claude Code and Codex are both required in the first version.**
Support sessions that start in a worktree and sessions that move into, between, or out of worktrees
while running, when the agent records reliable evidence of that move. Show uncertainty when the
available records cannot establish the location.

The useful flow is: select an agent session, see its worktree and branch, choose **Open Worktree
in IntelliJ**, and inspect the files that session is using. If that checkout is already open,
offer **Focus Worktree Window** and focus it instead of creating a duplicate project.

Opening a worktree means opening its existing directory as a project. Default to a new window
when it is not already open, keeping the source project and its running terminals alive. Do not
checkout a branch in the source project, copy changes, move the agent, restart it, or transfer
its terminal. Same-window replacement is outside this first version.

Keep the implementation shared where possible: agent readers supply location evidence, a common
Git resolver identifies the checkout, and one UI/action implementation serves both agents.
opencode and Pi must continue to work unchanged; extending their location tracking is optional
and must not delay the two required agents.

Out of scope: creating/removing/pruning worktrees, merging or moving changes between checkouts,
automatic project switching, remote worktrees, a general worktree management UI, and tracking
every child agent's worktree as a separate session. An agent can run individual commands against
several checkouts; that does not necessarily give its parent session one new current directory.

## Constraints to carry through implementation

- Target IntelliJ Platform 2026.2 / build 262, JDK 25 and Kotlin API 2.3. Use supported SDK APIs
  and verify them against this project's actual platform dependency.
- Detection is local and read-only. No network, agent hooks/configuration changes, agent API
  calls, or writes into agent-owned storage. Respect configured data roots, not just defaults.
- Seshlog state belongs in IDE storage. Persist only necessary metadata such as paths and
  evidence timestamps; never persist tool output or conversation bodies. Live Git snapshots
  should remain in memory and be revalidated after restart.
- Parse defensively: skip malformed/unknown records, preserve valid prior metadata when a field
  is absent, and do not turn one unreadable directory or database into a failed global scan.
- All filesystem/Git inspection runs on workers. Rendering and action presentation use immutable
  snapshots. UI delivery must check disposal and reject stale generations/results.
- Use synthetic JSONL/SQL/layout fixtures in `src/test/resources/fixtures/`, never real transcripts.
  Use constructor lambdas for injected resolvers, clocks and project-opening effects. Keep pure
  decisions independent of IntelliJ; use JUnit 4 and platform fixtures only where needed.
- Opening an IntelliJ project normally involves project configuration files. Worktrees can live
  under agent data roots (for example `$CODEX_HOME/worktrees`), so check the literal no-write rule
  in `AGENTS.md` against the SDK's project-storage behaviour before enabling that case. Prefer
  a supported external project-storage option if needed. If this cannot meet the rule, document
  the concrete conflict and obtain a narrowly scoped decision before relaxing it; do not silently
  change `AGENTS.md`. Discovery and tracking can still be implemented independently.

## Current code and gaps

All Kotlin paths in this table are relative to `src/main/kotlin/com/hedworth/seshlog/`.

| Area | Existing entry points and relevant behaviour |
| --- | --- |
| Session metadata | `model/Session.kt`: `Session.cwd`, `gitBranch`, `displayBranch`. There is no explicit worktree association or location provenance. `displayBranch` hides `HEAD`. |
| Git relationship | `index/GitRepository.kt`: `commonDir` follows `.git` and `commondir` to associate sibling checkouts. It does not return the checkout root, worktree identity, HEAD, or availability. A `.git` file alone does not prove a linked worktree; submodules and separate Git directories also use files. |
| Path snapshots | `index/ResolvedPaths.kt`: `resolve`, `merge`, `belongsToRepository`. Existing entries are reused indefinitely until invalidated. Git changes at an unchanged cwd therefore need explicit refresh support. |
| Index and watching | `index/SessionIndex.kt`: `refresh`, `scanOnce`, `pathRefreshGeneration`; `index/SessionWatcher.kt`: `start`, `isRelevantPath`, debounce. Current watches cover provider storage, not general repository metadata. |
| Filtering and grouping | `ui/SessionListViewModel.kt`: `visible`; `ui/SessionTreeModel.kt`: `group`, `groupHits`. Groups use cwd. `settings/AgentFilterState.kt` defaults `includeWorktrees` to true. Path containment currently admits nested worktrees even with sibling worktrees disabled. |
| UI snapshots | `ui/SessionTreePanel.kt`: `preparePaths`, `invalidateResolvedPaths`, `render`, `rebuildTree`. There is already a generation guard for asynchronous path resolution. Both browse and search need the new metadata. |
| Labels | `ui/SessionCellRenderer.kt`: `renderSession`, `tooltip`. Branch appears in details; cwd appears in the tooltip. Filesystem work must stay out of these methods. |
| Claude parsing | `claude/TranscriptParser.kt`: `Accumulator.offerContext` updates cwd/branch on accepted user/context records. `offerUser` skips tool-result records once cwd is known. A move during a tool call may therefore be missed until a later accepted record. |
| Claude live data | `claude/LiveSessionReader.kt` already reads `LiveSession.cwd` and verifies live PID markers. `claude/ClaudeCodeSessionProvider.kt`: `scan` currently takes cwd from parsed transcript metadata, using the live marker for PID/activity. Do not assume the marker's cwd is always current without checking its semantics. |
| Codex parsing | `codex/CodexTranscriptParser.kt`: `Accumulator.offerSessionMeta` keeps the first cwd/branch and does not handle `turn_context`. `rolloutHeader` separately reads initial identity/launch evidence used by terminal tracking. |
| Codex database | `codex/CodexSessionProvider.kt`: `scan`, `metadata`; `codex/CodexStateDatabase.kt`: `ThreadRow`, `read`. Non-null DB cwd/branch currently override the parsed values unconditionally. DB timestamps describe thread updates, not necessarily a directory change. |
| Parse caches | `cache/FileBackedParseCache.kt`, `claude/TranscriptInfoStore.kt`, `codex/CodexTranscriptInfoStore.kt`. Both provider cache versions are currently 6. Parsing is incremental; new semantics must also work with warm/persisted caches. |
| Actions | `ui/actions/SessionActions.kt`: `SessionAction`, `sessionFrom`; `src/main/resources/META-INF/plugin.xml`: `Seshlog.ContextMenu`. Reuse the selected-session action context. |
| Consumers to protect | `terminal/CodexDaemon.kt` uses rollout headers and exact process evidence; `terminal/WorkingDirectoryRecovery.kt` contains `WorkingDirectoryChoice` and remembered replacement directories; resume/fork/copy actions and `restore/SessionRestoreManager.kt` also consume cwd. A remembered resume replacement is not evidence of the agent's actual location. |

Existing tests worth extending: `index/GitRepositoryTest`, `index/ResolvedPathsTest`,
`index/SessionWatcherTest`, `index/SessionWatcherVfsTest`, `claude/TranscriptParserTest`,
`claude/LiveSessionReaderTest`, `claude/ClaudeCodeSessionProviderTest`,
`codex/CodexTranscriptParserTest`, `codex/CodexSessionProviderTest`,
`codex/CodexStateDatabaseTest`, `codex/CodexTranscriptInfoStoreTest`,
`ui/SessionListViewModelTest`, `ui/SessionFilterActionsTest`, `ui/SessionActionInvocationTest`
and `ui/SeshlogToolWindowTest`. Test paths mirror the production packages under `src/test/kotlin/`.
Existing fixtures include `worktree-gitdir.txt`, `worktree-commondir.txt`,
`claude_resumed_queued_context.jsonl`, `codex_session.jsonl`, `codex_state_5.sql`, and
`codex_app_server/` / `codex_subagents/`.

## Behaviour and evidence rules

1. Distinguish the agent's **latest reported session directory**, Git's **checkout root**, and
   the **initial launch directory** used for terminal identification. These can differ. Resolve
   a session in `worktree/src/module` to its worktree root for opening, retaining the reported
   subdirectory for display and existing launch behaviour where appropriate.
2. Keep provenance for location evidence: source, record order/time when available, and whether
   it is an initial fallback, later session context, or conflicting/unknown evidence. A session
   being live does not by itself prove that an old cwd is current. Do not invent a numeric
   confidence score or use transcript mtime/thread activity as the time of a directory change.
3. Prefer explicit session context over guesses from folder names, branch names, transcript
   storage paths, prose, or shell text. A successful agent worktree transition may supply evidence
   once its actual record format is verified. An attempted/failed `EnterWorktree`, a quoted path,
   `git -C`, or one tool's `workdir` is not automatically a session-wide move. Subagent evidence
   must not overwrite the parent's location.
4. Define deterministic precedence between sources after verifying their semantics. An older DB
   row or stale live marker must not roll back a newer confirmed transition. Where ordering cannot
   be established, retain the last known location and label the conflict/uncertainty.
5. Use canonical checkout paths and shared Git metadata to establish repository relationships.
   A branch name is display data, never an identity key. Codex worktrees can have detached HEADs;
   display `detached HEAD` and optionally a short commit when available.
6. Report the branch currently observed in the checkout as worktree metadata. Historical session
   branch metadata is separate and must not be presented as the current Git branch after a switch.
7. Worktree existence is independent of session activity. A stopped session may still have an
   openable last recorded worktree; a running session may refer to one that has disappeared.
   Never mark an entire session live just because its worktree exists.

| Situation | Expected presentation/action |
| --- | --- |
| Verified linked checkout with unambiguous recorded location | Show worktree name and branch/detached HEAD; full path in tooltip; enable Open Worktree. Describe it as the latest recorded location rather than guaranteeing every tool writes there. |
| Same checkout already open in this IDE instance | Focus Worktree Window, including when it is the invoking project; no duplicate project. |
| Historical session with an existing verified checkout | Keep it openable, with last recorded location wording and no live implication. |
| Missing/removed checkout | Show the recorded path and unavailable status; disable opening and revalidate on refresh. Never silently open the parent/main checkout instead. |
| Conflicting or insufficient location evidence | Show unknown/uncertain or last known information; do not enable an action that claims a confirmed current worktree. An explicitly labelled last-known action may be used only if that target is itself validated. |
| Main checkout or non-Git directory | Preserve normal session behaviour; do not label it as an agent-created linked worktree. |

## Implementation sequence

- [x] **1. Verify evidence formats and project-opening support.**
  Establish the location contract before changing readers. Check current Claude Code worktree
  transitions, exits, resume and transcript relocation; check Codex CLI/app-origin sessions,
  session context records, DB updates and handoffs. Use local code/docs and read-only inspection
  as needed; record agent versions and structural findings, never copy a real transcript into
  the repository. The public docs linked below describe behaviour, not a stable storage schema.
  Create small synthetic fixtures for the formats actually supported, including transitions
  without a subsequent user prompt and conflicts between sources. Record any unobservable case
  explicitly rather than promising complete tracking from launch cwd alone.
  Inspect the bundled 262 SDK for supported project-open and window-focus APIs. Candidate areas
  are `ProjectUtil`, `ProjectManager`/`ProjectManagerEx`, and `OpenProjectTask`; confirm exact
  signatures and API status instead of assuming them. Check project trust handling, nested
  worktrees, detached HEAD and project storage under agent roots. Do not depend on IntelliJ's
  built-in Worktrees panel being available for every project layout.
  - Deliverable: update this plan with the chosen sources/precedence, supported formats and
    platform approach; keep unresolved cases explicit. No silent reduction to one agent.
  - Validation: synthetic fixtures valid and existing `make check` passes. Manual SDK opening
    and storage checks: **owed** until recorded here.

- [x] **2. Add a pure worktree resolver and association model.**
  Extend or compose `GitRepository` / `ResolvedPaths` with an immutable checkout description:
  canonical root, per-worktree Git directory, common repository directory, main/linked kind,
  branch or detached commit, and availability. Add a small location/association type carrying
  the provenance from item 1; keep live Git facts separate from persisted transcript metadata.
  Prefer extending the existing read-only layout inspection when sufficient. If using Git,
  `git worktree list --porcelain -z` provides structured records; use argument lists, bounded
  execution, and at most one query per repository snapshot, never one process per session.
  Handle relative and absolute gitdir/commondir paths, subdirectories, symlinks, spaces, Unicode,
  malformed/unreadable metadata, detached HEAD, multiple roots, and submodules without false
  linked-worktree labels. Bare repositories are not openable working checkouts. Do not hard-code
  `.claude/worktrees` or `$CODEX_HOME/worktrees` as the only valid locations.
  A missing nested worktree must not resolve upward into the main checkout by accident. Retain
  enough prior identity to distinguish an unavailable checkout from an unrelated directory later
  created at the same path. Recheck identity before opening.
  - Tests: synthetic main/linked/nested layouts, unrelated repositories with equal branch names,
    relative paths, symlinks, missing paths, detached HEAD, malformed files and submodule layout.
    Use injected resolvers to prove lookups/rendering do not perform I/O per session.
  - Validation: `make check`; no manual check needed for the pure resolver.

- [x] **3. Track Claude Code location changes reliably.**
  Implement the evidence rules established in item 1 in `TranscriptParser`, `LiveSessionReader`
  and `ClaudeCodeSessionProvider`. Support startup inside a worktree, main → worktree,
  worktree A → B, exit to main, and resume. Use verified session context or successful transition
  evidence so a move is visible without requiring another user prompt where the format permits.
  Revisit the tool-result fast path carefully: retain bounded parsing and do not read all large
  tool outputs solely to discover paths. Preserve prompt counts, titles, activity and sidechain
  filtering. Validate live-marker identity as today; its status timestamp is not automatically
  the timestamp of its cwd field.
  Handle transcript relocation/duplicate discoveries under old and new project directories
  deterministically if the verified format requires it: one session row, correct transcript
  selection, no ID changes, and no stale record winning by filesystem enumeration order.
  Update `TranscriptInfoStore` and invalidate its cache version when parsed semantics change,
  even if the new model does not add a serialized field.
  - Tests: fixture-backed startup/transitions/exit/resume; successful versus failed transition;
    stale live marker; sidechain exclusion; relocated duplicate transcript when applicable;
    malformed/partial records; missing fields; cold, appended and persisted-cache parsing agree.
  - Validation: `make check`. Manual Claude transition check: **owed**.

- [x] **4. Track Codex location changes reliably.**
  Extend `CodexTranscriptParser` for verified current session-context records (investigate
  `turn_context`) while keeping initial `session_meta` as fallback. Preserve the separate
  `rolloutHeader` launch/identity semantics used by `CodexDaemon`; changing current cwd must not
  break terminal association on the shared app-server.
  Replace unconditional cwd/branch overlay in `CodexSessionProvider.metadata` with the tested
  precedence policy. A stale `state_5.sqlite` row must not undo a later context transition;
  a valid DB-only update must be considered when the rollout is unchanged. Retain read-only
  access, schema/missing-table fallback and the union of DB paths with disk-discovered rollouts.
  Keep transcript IDs authoritative and exclude internal subagent/guardian/review rollouts.
  Account for SQLite WAL updates when arranging refresh; do not create a scan loop from SHM
  changes. Keep branch display tied to the resolved checkout, including detached HEAD.
  Update `CodexTranscriptInfoStore` / cache version for new semantics and evidence metadata.
  - Tests: startup, main → worktree → main, A → B, detached HEAD, old header plus new context,
    stale DB versus newer context, DB-only location update, missing/incompatible DB, missing
    fields, internal subagents, malformed/partial records, incremental and persisted-cache parity.
    Retain the `CodexDaemonTest` identity/launch regression coverage.
  - Validation: `make check`. Manual Codex CLI and app-origin transition checks: **owed**;
    document any supported-format limits rather than inferring current cwd from a daemon PID.

- [ ] **5. Refresh associations without blocking the UI or regressing filters.**
  Integrate location evidence and Git snapshots with `SessionIndex`, `ResolvedPaths` and
  `SessionTreePanel.preparePaths`. Resolve distinct paths/repositories once per relevant refresh.
  Refresh when session location changes, on explicit Refresh, on project roots/settings changes,
  and when relevant Git metadata or checkout availability changes without a transcript append.
  Use targeted metadata watches and/or bounded background revalidation, including negative
  cache entries, rather than recursively watching every file in all worktrees. Define and record
  the automatic refresh bound; an idle agent must not leave deleted-worktree status stale forever.
  Preserve debounce/coalescing and generation guards across settings changes and disposal.
  Invalidate equality/repaint caches when association/HEAD/availability changes even if the
  `Session` list itself is otherwise equal.
  Keep sessions in related worktrees visible from the main project with Sibling worktrees enabled.
  Make its disabled state consistent for nested and external sibling worktrees using checkout
  identity, while retaining ordinary subdirectory sessions. Apply the same rules to browse and
  search, preserve selected session identity on a move, and leave grouping by cwd unless a small
  change is required to keep labels accurate. Cover projects with several repository roots.
  Audit all `Session.cwd` consumers: resume/fork/copy, restore, changed-file path resolution and
  terminal tracking. Keep current-location evidence separate wherever reusing it would change
  launch identity or misinterpret historical relative paths. Do not turn a resume replacement
  directory into an observed agent move.
  - Tests: move updates association and both browse/search filters; nested sibling filtering;
    HEAD switch and deletion without a transcript change; missing → recreated; DB WAL refresh
    without SHM-triggered scan loops; stale worker results/disposal; selection preserved; bounded
    resolver calls on a large synthetic history; existing resume/restore/terminal tests pass.
  - Validation: `make check`. Manual automatic refresh check: **owed**.

- [ ] **6. Show worktree details and uncertainty in session rows.**
  Add a compact worktree label and branch/detached HEAD to session details, with full canonical
  path, reported subdirectory when different, and last-recorded/uncertain/unavailable explanation
  in the tooltip. Avoid duplicate branch text. Keep labels literal and escape tooltip HTML.
  Show the same information for search results, and include it in accessible descriptions.
  Missing worktrees remain visible according to normal session filters with their recorded path;
  do not hide the conversation solely because its checkout vanished. Preserve the last verified
  repository association where available so related historical sessions remain discoverable.
  An association alone must not change working/waiting/unread badges. Do not add a new permanent
  toolbar or a separate worktree manager for this feature.
  - Tests: both agents, detached HEAD, duplicate worktree basenames in different repositories,
    historical/missing/uncertain states, escaped paths, browse/search consistency, accessibility,
    and no filesystem calls while rendering.
  - Validation: `make check`. Manual narrow tool-window and tooltip check: **owed**.

- [ ] **7. Add Open Worktree / Focus Worktree Window.**
  Register a session action in `Seshlog.ContextMenu`, using `SessionAction` / `sessionFrom`.
  Presentation reads cached association state; no synchronous disk/Git work in `update`.
  Hide it for sessions without a linked-worktree association; expose unavailable/uncertain
  states with a disabled action and useful explanation as appropriate to the behaviour table.
  At invocation, fetch the latest session/association, revalidate the target checkout on a worker,
  and reject stale identity or a move that occurred while validating. Never fall back to opening
  the main checkout or a remembered resume replacement after failure.
  Compare canonical checkout roots with already open projects. Focus the matching window if
  found; otherwise use the supported 262 project-open API with a new window. Open the checkout
  root, not the session's arbitrary nested cwd. Coalesce repeated clicks for the same target and
  recheck already-open state immediately before opening. Respect normal IntelliJ trust/import
  handling and the storage decision from item 1. Report cancellation/open failure without leaving
  the UI busy. Keep the source project and running agent terminal intact.
  - Tests: pure target/focus/open decision through injected effects; action registration and
    selected-session context; alias paths focus one existing project; missing target; deleted or
    moved between menu display and click; conflicting evidence; disposed source project; repeated
    click; opening failure/cancellation. No tests should launch a real agent or another IDE.
  - Validation: `make check` and `./gradlew verifyPlugin`. Manual project/focus/trust behaviour
    and source-terminal survival: **owed**.

- [ ] **8. Complete acceptance checks and user-facing documentation.**
  Update `README.md` with both supported agents, what the badge/action means, Sibling worktrees,
  new-window/focus behaviour, detached HEAD, missing worktrees and evidence limitations. Update
  the Marketplace description in `plugin.xml` only as appropriate to the delivered behaviour.
  Record the versions and results of the manual matrix below, and any remaining limits here.
  Do not describe universal mid-session detection unless the evidence tests establish it.
  - Validation: final `make check`, `./gradlew verifyPlugin`, and `make release` for the packaged
    plugin. Record successful checks and any environment failures separately. No publish or
    Marketplace upload is part of this plan.

## Evidence contract verified 2026-10-25 (item 1)

- Local installed Claude Code **2.1.296**, Codex CLI **0.164.0**; structural read-only
  sampling of 35 recent files per agent (keys/counts only). No agent was launched or history
  changed. Claude had 21 envelope-cwd changes; Codex had `session_meta` and `turn_context`
  records, but no moves in that sample. App handoff timing remains a manual check.
- Claude executable code serializes `worktree-state.worktreeSession.worktreePath` after entry,
  and `worktreeSession: null` on exit **and on rejected resume**. Null alone cannot establish
  the restored cwd: retain last known location as uncertain until a subsequent non-sidechain
  user/assistant/attachment envelope reports cwd. The ExitWorktree result includes
  `restoredCwd`, but general tool text/arguments are never session-move evidence. Failed entry
  does not set a worktree state. Top-level envelopes carry cwd independently of tool output.
  Read only envelope fields with a streaming JSON reader, skipping bodies without materializing
  them. Last accepted record in the transcript wins; preserve ordinal and explicit timestamp.
  Startup is fallback evidence; later envelopes/state are recorded session context. A live
  marker's status update is not cwd freshness, so it never overrides transcript location.
- Codex `turn_context.payload.cwd` supersedes initial `session_meta.payload.cwd` in record order.
  The separate `rolloutHeader` remains initial launch identity. A DB cwd can update a header-only
  fallback, but disagreement with later context retains that context and marks uncertainty;
  `updated_at_ms` is activity, not directory-change time. Re-read the DB on each scan even if
  rollouts are unchanged. Watch the WAL explicitly, excluding SHM. No inference from shell
  commands, tool workdir, branch names, daemon PID, or child rollouts.
- These sources establish **latest recorded** location, not every tool's target. A Codex handoff
  with only a contradictory DB row cannot be ordered reliably and stays uncertain. A Claude
  exit with only a null state cannot reveal its actual fallback cwd. Missing records are limits,
  not confirmation that the initial directory is still current. Synthetic fixtures live in
  `fixtures/locations`; they pin structure, not undocumented promises about future agents.
- SDK sources: cached **IU-262.10968.63**. Public
  `com.intellij.ide.impl.ProjectUtil.openOrImport(Path, OpenProjectTask)` and
  `focusProjectWindow(Project, Boolean)`; use the public `OpenProjectTask {}` builder with
  `forceOpenInNewFrame = true`, no projectToClose, normal trust/import handling. Root matching
  is canonicalized on a worker. ProjectStorePathCustomizer is Internal/Experimental and the
  implementation rejects non-bundled plugins except Bazel; ordinary directory projects store
  `.idea` in the checkout. Therefore **new opening under default or configured agent data roots
  is disabled**, with an explanation. Tracking remains available, and focusing an already-open
  project requires no storage write. No supported external-store bypass is used and AGENTS.md
  is unchanged. Enabling new opens there needs a narrowly scoped user decision.
- Validation: synthetic JSON/JSONL parses; baseline `make check` passed. Manual SDK opening,
  storage, Claude exit/resume/relocation and Codex CLI/app handoff checks remain **owed**.

## Implementation results

- Item 2: immutable `SessionLocation` / `Checkout`, bounded read-only layout inspection with
  gitdir backlink verification and per-snapshot memoization. Retains unavailable identities;
  supports symlinks, relative/absolute paths, submodules and detached HEAD. `ResolvedPaths`
  supports explicit revalidation and checkout-aware scoping. Synthetic layout tests pass.
  Full test task passed with `./gradlew clean test --offline --no-build-cache
  -Pkotlin.incremental=false` (401 passed, two opt-in checks skipped). Ordinary incremental
  compilation reused old Session constructor bytecode; a clean uncached rebuild resolved it.

- Item 3: Claude envelope/state tracking, metadata cache v7, deterministic relocation selection
  and stale-marker protection. Both new transition regressions failed before implementation;
  full clean test task passed (407 passed, two skipped). Persisted/appended parsing agrees at
  every fixture boundary. Null worktree state stays uncertain until actual cwd is reported.
  Manual Claude transition/exit/resume checks remain **owed**.

- Item 4: Codex turn-context tracking, metadata cache v7, DB fallback/conflict policy, explicit
  WAL watch. Launch headers and CodexDaemon matching remain unchanged. The new context regression
  failed before implementation; all 414 tests completed successfully (two opt-in checks skipped)
  in the full clean test task. CLI/app handoff checks remain **owed**.

## Manual acceptance matrix

Use `make run` with a disposable local repository and synthetic/test sessions where possible.
Run each agent-specific flow for **both Claude Code and Codex**, recording their versions and
whether the Codex session came from CLI or app. Do not modify real histories to manufacture a test.

- [ ] Start in an existing linked worktree, including a subdirectory: correct root, name, branch,
  full path and Open target; inspect/edit/build the actual checkout after opening.
- [ ] Start in main, enter/create a worktree during the session, and observe the label/filter
  update without another prompt when supported; switch A → B and exit back to main.
- [ ] Stop/resume in a worktree; verify last-recorded wording and continued openability.
- [ ] Exercise a Codex detached-HEAD worktree and a later branch/HEAD change.
- [ ] Open once, then invoke again: focus the same project window; test a symlink alias and a
  worktree already open before Seshlog's action is used.
- [ ] Keep an agent running in the source IDE terminal while opening its checkout: the source
  window and terminal survive, tab association and attention badges remain correct.
- [ ] Test an external sibling checkout and a nested `.claude/worktrees/...` checkout with
  Sibling worktrees both enabled and disabled, in browse and search.
- [ ] Remove/move a disposable checkout while its session remains listed: unavailable status,
  disabled action, no wrong-parent opening; test removal between selection and invocation.
- [ ] Test malformed/unreadable metadata and ambiguous multi-checkout tool activity: graceful
  uncertainty, no invented current worktree, no UI freeze.
- [ ] Test narrow tool-window display, keyboard action invocation, project trust/cancel/import
  behaviour and the agent-root project-storage approach established in item 1.

All manual checks are **owed** as of this plan. Automated checks alone do not establish that IDE
project opening, focus and terminal lifetime behave correctly in the sandbox IDE.

## Commands and references

```sh
make check
make run
./gradlew verifyPlugin
make release
```

If dependency downloads are unavailable, use the Gradle equivalents with `--offline`, for example
`./gradlew test --offline`. The Makefile does not currently expose a Gradle-arguments variable.
Real-data benchmarks and `make codex-check` are opt-in as documented in `AGENTS.md`; they are not
required in CI and must never print conversation content.

Official references checked during the feature discussion on 2026-10-24; re-check against the
versions being implemented when needed:

- [IntelliJ IDEA 2026.2: Use Git worktrees](https://www.jetbrains.com/help/idea/use-git-worktrees.html)
  documents opening externally created worktrees as projects and focusing/opening via the IDE.
  Its built-in worktree integration has single-repository and nested-worktree limitations; verify
  generic project opening rather than assuming that panel's action works for every agent layout.
- [Git: git-worktree](https://git-scm.com/docs/git-worktree) documents linked checkout metadata,
  `list --porcelain -z`, detached HEAD, and missing/prunable worktrees.
- [Claude Code: Worktrees](https://code.claude.com/docs/en/worktrees) documents startup,
  `EnterWorktree`, moving between worktrees, resume and transcript relocation behaviour.
- [Official OpenAI documentation: Worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)
  documents Codex worktrees, detached HEAD, local/worktree handoff, and managed-worktree storage.

These references establish product behaviour, not guaranteed JSONL fields, database freshness,
or a supported IntelliJ plugin API signature. Those must be verified in item 1 and pinned by tests.
