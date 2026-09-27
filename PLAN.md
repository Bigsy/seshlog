# Seshlog plan

From a review on 2026-09-27 at `d5aecf5` (262 tests green). Measurements come from local data:
claude 2.1.283 (66 sessions, 134 MB), codex-cli 0.157.1 (852 listed rollouts, 847 MB), opencode
1.18.31 (40 sessions), pi 0.85.1 (76 sessions). Only counts, sizes, key names and record types
were inspected, never transcript content.

**Order:** section 0, then section 1 in order, then 4.1 and 4.2, then sections 2 and 3, the rest
of 4, and 5 (5.1 after section 2).

**Conventions (AGENTS.md):** one commit per item. Each fix lands with a regression test that fails
before the change. Tick the box when that test passes under `make check`, and add a
"manual:" note when a `make run` check is still owed. Line numbers carry the symbol name: trust
the symbol, re-find the line.

## 0. Platform baseline

Target the IDE in daily use (IntelliJ IDEA 2026.2.3, reworked terminal) and drop older builds.
Checked against the cached platforms:

- The public reworked-terminal API (`com.intellij.terminal.frontend.*`) first appears in 2025.3.
- `TerminalView.getSessionDeferred()` exists only from 2026.2 (262). Before that, the shell PID
  and state come from the legacy path in `ReworkedTerminal` (`legacyRunning`, startup options).
- `TerminalToolWindowTab.KEY` exists only from 2026.2.3 (262.10968), not in 2026.2 GA (262.8665).
- The API is `@ApiStatus.Experimental`, and `TerminalToolWindowTabsManager.attachTab` changed
  signature in 261, 262 GA and 262.10968.
- 2026.2 ships JBR 25; its platform classes are Java 25 bytecode (major 69) and it bundles the
  Kotlin 2.4.0 stdlib.
- The Classic engine is still selectable in 2026.2.3 (`TerminalEngine.CLASSIC`), so classic tab
  support stays.

- [x] **0.1 Raise the minimum to 2026.2 (262).**
  - `gradle.properties`: `pluginSinceBuild = 262`, `platformVersion = 2026.2.3`, `javaVersion = 25`,
    and set `platformType` to the unified IntelliJ IDEA code so `usesUnifiedIntelliJIdea` in
    `build.gradle.kts` can go.
  - `build.gradle.kts`: raise `apiVersion` from `KOTLIN_1_9` to the newest the Kotlin plugin
    supports (2.3 with 2.3.21; the platform bundles 2.4.0).
  - CI (`.github/workflows/build.yml`, `release.yml`): `java-version: 25`.
  - Update the baseline wording in README ("2024.1 or newer"), AGENTS.md "Hard constraints", the
    `ReworkedTerminal` class comment and the `plugin.xml` change notes.
  - Test: `make check` and `./gradlew verifyPlugin` pass against 2026.2.3. Expect some platform
    test fixes now that tests run on the IDE actually in use.
  - Verified: `make check` and `verifyPlugin` against installed IU-262.10968.63 pass (2026-09-27).
    Foojay resolver updated to 1.0.0 for Gradle 9.6 compatibility.
  - Manual: still owed — `make run`, resume in a reworked tab, copy from it, restart and restore.

- [x] **0.2 Delete compatibility code the new baseline makes dead.** No behaviour change.
  - `terminal/ReworkedTerminal.kt:151` (`legacyRunning`) and the `!hasSessionApi` branches in
    `handle.shellPid`/`state` (`:103`, `:117`), plus `localProject` if nothing else uses it.
  - `terminal/TerminalTabs.kt:60` (`contentsRecursively`): call `ContentManager.getContentsRecursively()` directly.
  - `ui/SessionTreePanel.kt:302` (`invoke`): route double-click and Enter through
    `ActionUtil.performAction` (present since 2025.2), as its comment anticipates.
  - `settings/SeshlogConfigurable.kt:121`: use the current `Row.textFieldWithBrowseButton` overload.
  - `ui/SessionTreePanel.kt:278`: deprecated `com.intellij.ProjectTopics.PROJECT_ROOTS` to
    `ModuleRootListener.TOPIC`.
  - Keep the `VIEW_KEY` fallback in `ReworkedTerminal.viewOf` (2026.2 GA has no tab key) and the
    private `tabsRestoredDeferred` read in `restored` (still no public readiness API).
  - Test: existing `ReworkedTerminalTest`, `ReworkedTerminalPlatformTest`, `TerminalTabObserverTest`
    and `SeshlogToolWindowTest` pass unchanged.

- [ ] **0.3 Decide: link the reworked terminal API directly, or keep reflection.**
  Direct linking means `verifyPlugin` reports API breaks in CI (including EAP 263) instead of
  `ReworkedTerminal.read` silently returning null at runtime, and removes `call`/`loadApiClass`.
  It needs a plugin dependency on the `intellij.terminal.frontend` content module
  (`plugins/terminal/lib/modules/intellij.terminal.frontend.jar`) that the descriptor format
  allows, and the verifier will list experimental API usage. Spike it; if the module cannot be
  declared cleanly, keep the reflective adapter and record why here.

## 1. Fix first

- [x] **1.1 Codex subagent rollouts are listed under their parent's id.**
  `codex/CodexTranscriptParser.kt:103` (`offerSessionMeta`) prefers `payload.session_id` over
  `payload.id`. Spawned subagents (`source.subagent.thread_spawn`) carry the parent's
  `session_id`, so 46 local rollouts list as their parent: 852 rows, 803 distinct ids. Resume or
  fork on such a row opens the parent, and search merges the transcripts. Only guardian reviews are
  skipped (`codex/CodexSessionProvider.kt:74`, `scan`). Every local top-level rollout (cli, vscode,
  mcp, exec) has `session_id == id`, so keying by `id` is safe for them.
  - Fix: key by `payload.id`. Skip `thread_spawn` and `review` subagent rollouts like guardian ones
    (65 and 42 locally). Decide separately whether `source: "mcp"` rollouts (112, Codex driven by
    another agent) stay visible. Bump `CACHE_VERSION` in `codex/CodexTranscriptInfoStore.kt`.
  - Test: synthetic fixture with a parent rollout, a `thread_spawn` rollout sharing its
    `session_id`, and a `review` rollout. Scan returns only the parent, and ids are unique.

  - Verified: synthetic parent/spawn/review fixtures pass under `make check`. MCP rollouts stay visible.

- [x] **1.2 Make the benchmark numbers-only and representative.**
  `src/test/kotlin/com/hedworth/seshlog/claude/RealDataScanBenchmark.kt:23,42` prints real
  titles, folder names and search snippets into Gradle test reports. It searches through
  `conversationText`, not the `conversationEntries` path production search uses, and covers
  Claude only. Needed before 1.3 so the speed-up can be measured.
  - Fix: print counts and timings only. Drive `ContentSearchIndex` with the production extractor.
    Add Codex and Pi.
  - Test: none (skipped benchmark). Check its output contains no titles or paths.

- [ ] **1.3 Search matching is slow on large histories.**
  A warm Codex query takes 3.3 s and runs on every keystroke (first query 11.3 s).
  `index/TextQuery.kt:9,16` (`QueryTerm.ranges`, `matches`) use `indexOf`/`contains(ignoreCase = true)`.
  `index/ContentSearchIndex.kt:54` (`search`) walks each text three times (match, ranges for count
  and snippet, phrase bonus) and computes every range although the count caps at 9.
  - Fix: store a case-folded copy of each text when its entry is built, folding per `Char` so
    offsets stay aligned with the original (whole-string `lowercase()` changes length for
    characters such as `İ`). Match with plain `indexOf`, and do matching, capped counting and the
    first snippet in one pass.
  - Test: `ContentSearchIndexTest` cases proving identical hits, scores and snippet offsets for
    mixed case, phrases and a length-changing character such as `İ`. Put before/after benchmark
    timings in the commit message.

- [x] **1.4 Every scan redraws the list twice and reloads the preview.**
  `ui/SessionTreePanel.kt:261` (`sessionsUpdated` listener) clears `requestedPaths`, so each scan
  renders at once and again when `preparePaths` (`:527`) finishes. Each render replaces the whole
  tree in `rebuildTree` (`:543`, `treeModel.setRoot`) and calls `preview.showSession` from `render`
  (`:522`). With a query active, both passes rerun the full content search instead.
  `ResolvedPaths.resolve` re-resolves every cwd and its git repository each time. This runs in
  every open project on every scan.
  - Fix: resolve only paths not resolved yet (refresh all on Refresh or a roots change). Skip
    `rebuildTree` when the grouped result equals the last one. Reload the preview only when the
    selected session's identity or `lastActivityAt` changed. Rerun a search only when the
    candidates changed.
  - Test: `SeshlogToolWindowTest`: deliver the same `sessionsUpdated` twice and assert the tree
    root instance and preview load count are unchanged; a changed session rebuilds once.
  - Manual: still owed — scroll position and an open context menu survive a background rescan.

- [x] **1.5 Continuous file changes postpone rescans indefinitely.**
  `index/SessionWatcher.kt:80` (`schedule`) cancels and re-arms a 1.5 s alarm on every VFS event
  with no maximum wait. With several agents writing, or opencode streaming into `opencode.db-wal`,
  no rescan runs, so activity badges, unread markers, waiting notifications and new-session
  adoption stall. Events under paths the scan ignores (`subagents/`, `tool-results/`, `memory/`)
  re-arm it too.
  - Fix: add a maximum wait (about 5 s from the first pending event), as a small pure policy class
    with an injected clock. Drop events for ignored subpaths (revisit if 4.6 reads subagent files).
  - Test: unit test of the policy: events every 500 ms for 10 s fire at least twice; a single
    event fires once after the debounce.

- [x] **1.6 Reusing an idle tab can type a resume command into another agent.**
  `terminal/TerminalTabs.kt:92` (`findIdleTab`) returns any tab whose title matches and whose
  state is IDLE, whoever owns it, and a tab still reads IDLE just after Seshlog sent it a command.
  Restoring two sessions with the same title (`restore/SessionRestoreManager.kt:230`, `restore`),
  or a quick double Resume, sends the second `cd … && <agent> --resume` into the first agent's
  prompt. `OwnedTerminalTabs.track` then evicts the first session, which drops out of restore state.
  - Fix: `findIdleTab` skips tabs registered to any session. Mark a tab "command pending" from
    `execute` until an agent process is observed or about 10 s pass, and treat pending as not
    idle in `resume` and `RestoreTabReadiness.ready`.
  - Test: fake `TerminalHandle`s, two sessions with the same title: resuming both uses two tabs
    and the first registration survives.

- [x] **1.7 Claude prompts queued while the agent is busy are invisible.**
  Claude Code 2.1.x saves them as `type: "attachment"` records with
  `attachment.type == "queued_command"`, never as `user` records. The parsers read only
  `user`/`assistant` (`claude/TranscriptParser.kt:108`, `claude/ConversationMessages.kt:20`,
  `claude/ClaudeConversationEntries.kt:15`), so these prompts are missing from prompt counts,
  preview, the viewer and search (33 locally). Local shapes: `commandMode: "prompt"` with
  `origin.kind: "human"` (33), `commandMode: "task-notification"` with no origin (48), and
  `origin.kind: "peer"` with `isMeta: true` (3).
  - Fix: treat `commandMode == "prompt"` as a user prompt unless `origin.kind` is present and not
    `human`, or `isMeta` is set. Keep file order. Accept `prompt` as a string or a content array.
    Bump `CACHE_VERSION` in `claude/TranscriptInfoStore.kt`. Cover `TranscriptTailReader` and the
    last-assistant path as well.
  - Test: synthetic fixture with one human queued prompt, one task notification and one peer
    message. Prompt count, `lastMessages` and conversation entries include only the human prompt.

- [x] **1.8 A local rename only shows in the tree.**
  The preview header (`ui/SessionPreviewPanel.kt:116`, `showSession`), the tooltip
  (`ui/SessionCellRenderer.kt:85`, `tooltip`), terminal tab titles (`terminal/TerminalTabs.kt:141,147,175`,
  `resume`/`fork`; `terminal/TabRegistry.kt:86`, `sync` retitles) and restore notifications
  (`restore/SessionRestoreManager.kt:201,245,249`) use `session.title`, not
  `SessionOrganisation.title(session)`.
  - Fix: show the local title everywhere. `findIdleTab` matches a restored tab by either title.
    The tooltip keeps the agent's title as a second line when they differ.
  - Test: rename a session, then assert the preview header, the `TabRegistry.sync` retitle and the
    fork tab title use the local title.
  - Manual: still owed — rename a running session and watch its terminal tab title update.

## 2. Tracking and restore hardening

- [x] **2.1 "Restore" acts on a stale plan.**
  `restore/SessionRestoreManager.kt:180` (`offerRestore`) captures `plan` when the notification
  appears. Clicking Restore later (the notification stays in the Notifications tool window)
  resumes sessions started manually in the meantime, and `restore` (`:230`) terminates orphans by
  PID through `ProcessTree.System.terminate`, which by then may be an unrelated process.
  - Fix: re-plan on click. Capture orphan `ProcessHandle`s at plan time and terminate through
    them (their identity includes start time).
  - Test: a session live at click time is not resumed; an orphan whose handle is no longer alive
    is not terminated.

- [x] **2.2 An `lsof` failure reads as "no open files".**
  `terminal/ProcessTranscripts.kt:28` (`lsofFiles`) returns empty on timeout, on a non-zero exit
  (lsof exits 1 whenever any listed PID has gone, discarding output for the rest) and on oversize
  output. `terminal/SessionProcess.kt:61` (`identifyTree`) then falls back to process arguments,
  so after `codex resume OLD` and `/new` the tab can flip back to OLD and Copy targets the wrong
  session. A first line over the 64 KB `isCliTranscript` cap has the same effect. Nothing is logged.
  - Fix: a three-state result (files, none, unknown); unknown keeps the current association. Parse
    output on exit 1. Log failures at DEBUG.
  - Test: `ProcessTranscriptsTest`/`SessionProcessTest`: unknown evidence keeps the current
    session; exit-1 output with valid lines still parses.

- [x] **2.3 Kill then quick Resume detaches the new tab.**
  `ObservedAgents` entries survive `OwnedTerminalTabs.track` (`terminal/OwnedTerminalTabs.kt:199`),
  so the next tick sees the old process as exited and `release`s the new tab (`:184`) until
  discovery re-adopts it 2 to 4 s later. Meanwhile "Show Tab" reverts to "Resume".
  - Fix: `track` clears the session's observed agent.
  - Test: tracking after an observed exit does not release the new tab.

- [ ] **2.4 An Error in a process tick stops tracking for the project.**
  `terminal/OwnedTerminalTabs.kt:129` (`refreshRunning`) sets `checkingProcesses` before the pooled
  block and clears it only on the normal path.
  - Fix: clear it in `finally`.
  - Test: with the tick body behind an injectable inspector, a throwing inspector does not block
    the next tick.

- [x] **2.5 `endedSessions` is read off the EDT.** `terminal/OwnedTerminalTabs.kt:33` is a
  `WeakHashMap` written on the EDT and read on a background thread by
  `ForkTerminalTabSessionAction.update` (`ui/actions/SessionActions.kt:132`, via `lastSessionFor`).
  `WeakHashMap.get` also purges entries. Synchronise it.
  - Test: concurrent reads and writes do not throw.

- [x] **2.6 `SessionAttentionState.getState` can throw while saving.** `settings/SessionAttentionState.kt:20`
  copies a `LinkedHashMap` the EDT mutates, from the save thread. Keep an immutable snapshot that
  mutations replace. Also prune unread entries for sessions no longer in the index, so
  `seshlog.xml` stops growing.
  - Test: `getState` during `viewed` does not throw; a scan without the session prunes it.

- [ ] **2.7 Concurrent `SessionWatcher.start` calls can leak watch roots.** `index/SessionWatcher.kt:51`
  reads and replaces `watchRequests` without a lock.
  - Test: two concurrent starts leave exactly the last set of roots watched.

## 3. Performance

- [ ] **3.1 Batch and back off process polling.**
  `terminal/OwnedTerminalTabs.kt:124` (`processClock`, 2 s) runs per project even with the IDE in
  the background. Each tick walks the full process table once per owned session and again per
  tab, spawns one `lsof` per Codex tab (`terminal/ProcessTranscripts.kt:28`), and walks process
  ancestry on the EDT (`terminal/TabRegistry.kt:66`, `sync`). With 20 Codex tabs that is about 40
  table walks and 20 sequential `lsof` runs (1 s timeout each) per tick.
  - Fix: one process snapshot per tick, shared; one `lsof` for all PIDs; ancestry off the EDT;
    slow to about 10 s while no IDE frame is active and after several unchanged ticks.
  - Test: a tick with a fake process source and N tabs calls the snapshot and `lsof` runners once each.

- [ ] **3.2 Stop initialising the Terminal tool window in every project.**
  `ToolWindow.contentManager` creates the tool window's content, which runs
  `TerminalToolWindowFactory` and restores its tabs. `terminal/TerminalTabs.kt:55` (`contents`),
  `terminal/OwnedTerminalTabs.kt:41` and the focus path in `TerminalTabObserver` reach it at
  startup and on focus changes, so every opened project builds its Terminal tool window. Each
  project also installs its own global `permanentFocusOwner` listener (`terminal/TerminalTabObserver.kt:50`).
  - Fix: use `getContentManagerIfCreated()` wherever Seshlog only inspects tabs; keep
    `contentManager` for launch and restore (`TerminalTabs.kt:157`). One app-level focus
    listener that dispatches to the focused project.
  - Test: starting `OwnedTerminalTabs` in a project whose Terminal tool window was never opened
    leaves its content uncreated.
  - Manual: open a project without opening Terminal; no restored shells start.

- [ ] **3.3 Cap the content search index.**
  Only per-session caps exist (`model/ConversationLimits.kt`). The first query extracts every
  candidate and the index lives for the IDE session: 212 MB of heap for local Codex data, with
  354 of 803 sessions at the 500K-character per-session cap. `ConversationLimits.read` (`:49`)
  reads one character at a time.
  - Fix: a global character budget with least-recently-used eviction in `ContentSearchIndex`;
    read in buffered chunks.
  - Test: with a small budget, older entries are evicted and re-extracted on demand, and results
    are unchanged.

- [ ] **3.4 Parse only the appended part of a changed transcript.**
  `cache/FileBackedParseCache.kt:63` (`get`) re-reads a changed transcript from the start (35 to
  65 ms for a 20 MB file, on every scan while an agent writes), and `persist` (`:79`) rewrites the
  whole cache file each scan (the Codex cache is 481 KB).
  - Fix: transcripts are append-only, so keep the byte offset and resumable parser state per
    entry and parse only new lines, with a full parse when the file shrank or its first line
    changed. Persist on a timer and at shutdown. Metadata only, as now.
  - Test: parse, append lines, parse again: same info as a full parse; truncation triggers a
    full parse.

## 4. Features

- [ ] **4.1 Show attention outside the tool window.**
  Unread and working counts appear only in the Seshlog panel. Badge the Seshlog tool window icon
  when anything is unread (`BadgeIconSupplier` or `ExecutionUtil.getLiveIndicator`), and add an
  optional status bar widget ("2 working · 1 unread") that runs Next Session Needing Attention on
  click. Counts follow the project's filters, like the project rows (`index/SessionAttention.kt`, `summary`).
  - Test: a pure count function over sessions, running ids and unread ids; widget text from counts.
  - Manual: the badge shows with the tool window hidden and clears when the session is viewed.

- [ ] **4.2 New session action.**
  Start a fresh Claude Code, Codex, opencode or Pi session in the project directory from the
  toolbar or a project row, with the configured executable and additional arguments. Seshlog owns
  the tab from the start (a pending association that resolves when the transcript appears), so
  these sessions skip the process and `lsof` discovery heuristics.
  - Test: command building per agent; a pending association resolves to the first new session
    seen in that tab.

- [ ] **4.3 Open a conversation in a read-only editor tab.**
  `ui/ConversationDialog.kt` is a non-modal dialog around a plain `JBTextArea`. Open the
  conversation as an in-memory, read-only `LightVirtualFile` (Markdown) in an editor tab instead:
  editor find, several conversations side by side, splits, and Markdown rendering when the
  Markdown plugin is enabled. Still no session content on disk. Carry over match navigation and
  the unread "viewed" signal.
  - Test: opening a fixture builds a read-only light file with the expected Markdown; viewing the
    last reply clears unread.
  - Manual: behaviour with the Markdown plugin disabled.

- [ ] **4.4 Render Markdown in the preview.**
  `ui/SessionPreviewPanel.kt:176` (`toHtml`) wraps each message in `<pre>`. Render assistant
  Markdown (headings, lists, code blocks) with the bundled `org.intellij.markdown` library,
  escaping raw HTML.
  - Test: a fixture reply with a list and a code block renders the expected HTML; raw `<script>`
    is escaped.

- [ ] **4.5 Tidy the header.**
  `ui/SessionTreePanel.kt:222` (header in `init`): the search hint and "Limited coverage" are
  permanent labels beside 8 toolbar actions and 3 plain `JButton`s. Move the hint into the search
  field's empty text and tooltip, and show coverage in the status line only when a search is
  partial. Fold Agents, date, Show Hidden and Sibling Worktrees into one action-based filter
  dropdown. Replace the `JOptionPane` custom range in `chooseDateFilter` (`:83`) with a validating
  `DialogWrapper`.
  - Test: existing filter tests in `SeshlogToolWindowTest` drive the new actions.
  - Manual: the header at a narrow tool window width.

- [ ] **4.6 Claude subagent transcripts and continuations.**
  Subagent conversations now live in `<session>/subagents/agent-*.jsonl` (38 locally) and are not
  searchable; the parsers' `isSidechain` filters no longer match anything at top level.
  `continued-in` records point to a successor session (2 locally). Search subagent text as tool
  activity of the parent, and link a session to its continuation in the tooltip and viewer.
  - Test: a fixture session with a subagent file and a `continued-in` record.

- [ ] **4.7 Files a session changed.**
  List the files touched by Edit/Write/MultiEdit (Claude), apply_patch (Codex) and the opencode
  equivalents, which tool search already parses, with Open and Compare with Current actions.
  Paths only, extracted on demand, nothing written to disk.
  - Test: fixtures per agent yield the expected paths.

- [ ] **4.8 Read Codex metadata from `state_5.sqlite`.**
  codex-cli 0.157 keeps a `threads` table (rollout_path, source, cwd, title, name, archived,
  git_branch, updated_at_ms). One query could replace parsing about 1,040 rollouts (cold scan
  3.5 s). Fall back to rollout parsing when the table or a column is missing; read-only, like
  `OpenCodeDatabase`.
  - Test: a fixture database built from SQL like `opencode_fixture.sql`; a missing table falls
    back to rollouts.

## 5. Code health

- [ ] **5.1 Make terminal tracking a pure reconciler.**
  `OwnedTerminalTabs.refreshRunning` reconciles four stores (`TabRegistry`, `ObservedAgents`,
  `endedSessions`, `running`) written from three paths (index sync, process tick, copy resolver),
  each with its own staleness checks and no per-tab generation. Extract a pure function (tab
  snapshot, process evidence and sessions in; register, release, adopt and retitle operations
  out) applied on the EDT. About half of the last ten commits were tracking fixes; this gives
  them one tested place. Do it after section 2 so those tests carry over.

- [ ] **5.2 Extract a list view model from `SessionTreePanel`.**
  662 lines mixing filter state, menus, search orchestration and tree building. Move filtering,
  grouping and the empty-state choice into a pure class in the style of `SessionFilter`
  (sessions, filters, resolved paths and organisation in; groups out). This turns the
  "unchanged" check in 1.4 into plain equality.

- [ ] **5.3 Replace inline fully qualified names with imports.**
  61 in `SessionTreePanel.kt`, more in `SessionActions.kt`, the providers, `OpenCodeDatabase.kt`
  and `SessionRestoreManager.kt`. Mechanical, no behaviour change.

- [ ] **5.4 Docs.**
  - README links to AGENTS.md and PLAN.md, but both are gitignored (AGENTS.md in `.gitignore`,
    PLAN.md in the global ignore), so the links are dead on GitHub. Track AGENTS.md or drop
    the links.
  - AGENTS.md "Working the plan" refers to README roadmap items that no longer exist.
  - The Marketplace description in `plugin.xml` lags the README: add activity badges, waiting
    notifications, unread markers and attention navigation, pin/rename/hide and date filters.
