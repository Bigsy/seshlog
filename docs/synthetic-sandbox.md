# Synthetic sandbox for manual checks

The physical checks in [PLAN.md](../PLAN.md) and [docs/session-switcher.md](session-switcher.md)
need a **verified synthetic profile**: an IDE that loads this checkout's build, has its own
config/system/log directories and reads only fictional agent data. `make run` does not provide
that. Its sandbox reads your real `~/.claude`, `~/.codex`, opencode and Pi histories.

```sh
make sandbox          # create the profile if absent, then start the sandbox IDE on it
make sandbox-verify   # with that IDE running: check it is a verified synthetic profile
make sandbox-reset    # delete the profile and generate a fresh one (close the IDE first)
```

The profile lives in `~/Library/Caches/Seshlog/synthetic-sandbox` on macOS,
`${XDG_CACHE_HOME:-~/.cache}/seshlog/synthetic-sandbox` on Linux and
`%LOCALAPPDATA%\Seshlog\synthetic-sandbox` on Windows. Set `SESHLOG_SANDBOX_DIR`, or pass
`-PsyntheticSandboxDir=...`, to use another directory. The generator refuses a directory that is
inside a Git checkout, overlaps a home or agent data directory, or already holds files it did not
create. Reset deletes only a directory carrying its `.seshlog-synthetic-sandbox` marker, and
refuses while the sandbox IDE is running.

## macOS Computer Use attachment

Verified on 2026-10-29: Gradle's direct JBR launch appeared as `Main` /
`com.jetbrains.jbr.java`, but Computer Use rejected that identifier with `Invalid app`.
Wrapping the Java executable in a real `.app` fixed attachment, keyboard input and reading the
Attention Inbox. This is a launcher workaround; it does not require changing Seshlog production code.

The working instance is under `/private/tmp/seshlog-followup-sandbox`. Attach with:

```js
var sandboxIde = await cua.getApp('/private/tmp/seshlog-followup-sandbox/launcher/Seshlog Sandbox.app');
```

Check that the returned window belongs to the synthetic `harbor` project. Selecting
`com.jetbrains.intellij` reaches the daily IDE. Before physical checks, run:

```sh
./gradlew -q verifySyntheticSandbox --offline -PsyntheticSandboxDir=/private/tmp/seshlog-followup-sandbox
```

To recreate the wrapper for another profile/runtime:

1. Start the normal synthetic profile and identify its PID by its exact `-Didea.config.path`.
   Capture its argument array using Java `ProcessHandle.Info.arguments()` (not a whitespace split of
   `ps` output). Preserve all JVM options, classpath, main class and project arguments.
2. Create `<root>/launcher/Seshlog Sandbox.app/Contents/MacOS/java` as a **copy** of that IDE's
   JBR `Home/bin/java`. Under `Contents`, symlink `lib`, `conf` and `release` to the same runtime's
   `Home/lib`, `Home/conf` and `Home/release`. Do not alter the original runtime or installed IDE.
3. Write `Contents/Info.plist`: `CFBundleIdentifier=com.hedworth.seshlog.sandbox`,
   `CFBundleName` and `CFBundleDisplayName=Seshlog Sandbox`, `CFBundleExecutable=java`,
   `CFBundlePackageType=APPL`, `CFBundleVersion=1`, and `NSHighResolutionCapable=true`.
   Give concurrently used sandbox profiles distinct bundle IDs.
4. Ad-hoc sign the local development bundle with
   `codesign --force --sign - '<root>/launcher/Seshlog Sandbox.app'`. The copied executable's original
   signature does not match the new plist; without this step macOS terminated it with exit 137.
5. Stop only the verified synthetic IDE, then launch the bundled `Contents/MacOS/java` with the
   captured argument array and the synthetic environment listed below. Keep `<root>/bin` first on
   `PATH`. Do not start by simply opening the bundle: it needs those JVM/profile arguments.
6. Rerun `verifySyntheticSandbox`, attach by full `.app` path, and verify both observation and input.

This wrapper is currently a manual setup; `make sandbox` still uses the ordinary Gradle launcher.
The original runtime must remain present because the wrapper links to its libraries. A new build or
runtime requires relaunching with the current Gradle arguments and re-verification. No full-disk,
Accessibility or Screen Recording permission reset was needed.

## What is isolated

| Part | Where | How |
| --- | --- | --- |
| IDE config, system, log, plugins | `<root>/ide/{config,system,log,plugins}` | The `runSyntheticIde` Gradle task |
| Seshlog's lexical index and parse caches | Inside the IDE config/system directories | Normal Seshlog storage |
| Session diagnostics | `<root>/ide/log/seshlog/sessions*.jsonl` | Normal Seshlog diagnostics |
| Agent data | `<root>/agents/{claude,codex,opencode,pi}` | Seeded **Settings → Tools → Seshlog** data folders |
| Agent executables | `<root>/bin/{claude,codex,opencode,pi}` | Seeded executable settings: stub agents, never the real CLIs |
| Terminal environment | `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`, `SESHLOG_SANDBOX` | Set on the IDE process; `<root>/bin` is first on its `PATH` |

Settings are seeded only when the profile is created, or when `seshlog.xml` is missing, so changes
you make during testing persist. Only the main `harbor` checkout is pre-trusted: the IDE opens it on
start. Every other checkout shows the normal trust prompt.

## Verifying the profile

Start the IDE with `make sandbox` and wait until `harbor` is open, with the Seshlog tool window
listing sessions. Then run `make sandbox-verify`. It prints a report, saves it to
`<root>/verification.txt` and exits non-zero on any failure. It checks the following:

- The running IDE process uses the sandbox's config, log, plugins and system directories.
- Every seeded data folder and executable setting still points inside the sandbox.
- This IDE process's diagnostics show the loaded Seshlog version, its build, Java and OS.
- Every `provider_scanned` record in this run hashed synthetic storage, and every provider is READY.
- The installed plugin jar matches `build/libs/seshlog-<version>.jar`, was installed before the
  IDE started, and no file under `src/main` changed after it was built.
- The checkout's commit and number of uncommitted changes, for the record.

Record the report with your manual results. It needs **Record session diagnostics** turned on, which
is the default. If you change sources, run `make sandbox` again before re-verifying.

## What is inside

Everything is fictional and regenerated identically (apart from dates) by `make sandbox-reset`.
Times are relative to when the profile was created, so date filters have sessions today, this week,
this month and older. `<root>/manifest.json` lists every session's agent, ID, title, directory
and purpose.

### Checkouts and directories

| Path under `<root>/workspace` | Kind | Used for |
| --- | --- | --- |
| `harbor` | Main Git checkout, `main` (opened on start) | Most sessions, worktree source |
| `harbor/services/api` | Subdirectory of the main checkout | Nested-directory sessions |
| `harbor-retry` | Linked worktree, `feature/retry-backoff`, untrusted and unopened | Resume/Open Worktree, trust prompt |
| `harbor-hotfix` | Linked worktree, detached HEAD | Detached branch label |
| `harbor-spike` | Removed linked worktree (`spike/old-parser`) | Missing checkout |
| `lantern` | Second Git repository, `main` | Second project window |
| `notes`, `notes/drafts` | Plain directories | Non-Git and nested non-Git sessions |
| `archive` | Plain directory | The oversized history only |
| `gone-scratch` | Never created | Missing plain directory |

A Codex session also records the synthetic Codex data directory as its working directory, for the
protected-agent-root case.

### Scenario sessions

28 scenario sessions sit alongside 220 filler sessions. Filler titles contain
`(filler NNN)`, and every third filler is untitled, so it shows its first prompt. The total exceeds the switcher's 200-row limit, and
lists need scrolling.

| Agent | Title (or first prompt) | Directory | Scenario |
| --- | --- | --- | --- |
| Claude Code | Retry backoff with jitter for the uploader | harbor-retry | Unopened worktree; natural-language search |
| Claude Code | Tests fail intermittently with "database is locked"… | harbor | Quoted phrase plus boost word |
| Claude Code | Review the cache eviction change | harbor | Claude subagent result |
| Claude Code | Fix the lantern build on CI | lantern | Error code only in a tool result |
| Claude Code | Café menu translations | notes | Accents, CJK and emoji |
| Claude Code | release-checklist-0.9 | harbor | Claude `/rename` beats the AI title |
| Claude Code | Worktree cleanup after the spike | harbor | Uncertain recorded directory |
| Claude Code | Spike: hand-written parser for config files | harbor-spike | Missing checkout |
| Claude Code | Paginate the orders endpoint | harbor/services/api | Nested directory |
| Claude Code | Port the colour parser from C++ and C# | lantern | Symbols, codes, paths |
| Claude Code | Connection pool sizing | lantern | A phrase split across two messages |
| Claude Code | Bulk import performance log | archive | 1,100 turns: partial coverage |
| Claude Code | Metadata-only: onboarding checklist | notes | Title and directory, no conversation |
| Claude Code | Search index migration (part 1 / part 2) | harbor | Continued session pair |
| Codex | Lantern schema migration | lantern | Thread name, filename search |
| Codex | The build fails after bumping sqlite-jdbc… | harbor | Error only in tool output |
| Codex | Add retry metrics… | harbor-retry | Second agent in the worktree |
| Codex | Bisect the flaky checksum test… | harbor-hotfix | Detached HEAD |
| Codex | Scratch experiment: benchmark three JSON parsers. | gone-scratch | Directory never existed |
| Codex | Explain what is in this config directory. | Codex data dir | Protected agent root |
| opencode | Draft onboarding notes for new contributors | notes | opencode title |
| opencode | Trace the slow startup | harbor | opencode tool output |
| opencode | (archived and child sessions) | harbor | Hidden unless archived sessions are shown; children never |
| Pi | Lantern dark theme contrast | lantern | Pi session name, dialogue only |
| Pi | Outline a blog post about local-first search. | notes/drafts | Nested plain directory |
| Pi | Summarise yesterday's retry changes… | harbor | Pi in the main checkout |

### Example searches

`SyntheticSandboxTest` asserts each of these against the generated histories with the production
index, so a different result in the sandbox IDE is a real finding. Use the **Search in** sources
shown. Conversation is the default; **All** means Tool activity on as well.

| Query | Search in | Expected first result |
| --- | --- | --- |
| `uploader keeps retrying storage forever` | Conversation | Retry backoff… (two words never appear) |
| `"database is locked" timeout` | Conversation | The database-is-locked session; not Lantern schema migration |
| `"connection pool"` | Conversation | Connection pool sizing |
| `"connection pool exhausted"` | Conversation | No results: the phrase spans two messages |
| `ColourParser.parseHex`, `C++`, `C#`, `E0277` | Conversation | Port the colour parser… |
| `src/main/kotlin/com/lantern/colour/ColourParser.kt` | Conversation | Port the colour parser… |
| `V7__theme_preference.sql` | Conversation | Lantern schema migration |
| `build.gradle.kts` | Conversation | The sqlite-jdbc build failure |
| `Größe`, `東京`, `"🚀 Launch day"` | Conversation | Café menu translations |
| `ERR_LANTERN_TOKENS_0xBEEF` | Conversation / All | None / Fix the lantern build on CI (Tool result) |
| `Could not resolve org.xerial` | All | The sqlite-jdbc build failure (Tool result) |
| `quokka` | Conversation / All | None / Review the cache eviction change (subagent) |
| `ClasspathScanner.scanAll` | Conversation / All | None / Trace the slow startup |
| `WCAG contrast` | Conversation | Lantern dark theme contrast |
| `importer-baseline-alpha` | Conversation | Bulk import performance log |
| `importer-final-omega` | Conversation | None, with partial coverage: beyond the retention limit |
| `onboarding checklist` | Titles & paths | Metadata-only: onboarding checklist |

With every project in scope, the oversized `archive` history makes coverage partial for every
query. Filter to one project, for example `lantern`, to see a complete no-match.

## Stub agents

Resume, fork and new-session actions launch `<root>/bin/<agent>`, a small Node.js program, instead
of the real CLI. Each stub prints a banner naming the session and never contacts a model or the
network. It refuses to start a new session outside the sandbox root. A stub behaves like its
agent where Seshlog looks:

- **Claude Code:** writes a live marker in `agents/claude/sessions/<pid>.json` with busy/idle
  status, and removes it on exit. A fork copies the history under a new ID and records the launch directory.
- **Codex:** defaults to direct-writer mode and emits a synthetic thread-ID terminal title. The optional shared-daemon mode below holds descriptors in a separate process. It brackets each turn with
  `task_started` / `task_complete`. A fork writes a new rollout with `forked_from_id` and the explicit `--cd` destination (or launch directory).
- **opencode:** adds messages to `opencode.db`; needs Node.js 22.5 or newer for `node:sqlite`.
- **Pi:** appends v3 tree entries; a fork writes a new file with `parentSession`.

Type a line to record a turn: the stub marks the session working, waits three seconds and records a
synthetic reply, so a running session goes from working to waiting. `/title <name>` records the
agent's own title. `/delay <seconds>` changes the turn length, and `/exit` or Ctrl+D quits.

Use the stubs from any terminal for the remaining scenarios. `$ROOT` below is the sandbox root;
the sandbox IDE's terminals have it as `$SESHLOG_SANDBOX`.

- **External running session:** in Terminal.app, run
  `cd "$ROOT/workspace/harbor" && "$ROOT/bin/claude" --resume <id>`, with the ID from
  `manifest.json` or **Copy Session ID**.
- **Unread and notifications:** type a prompt in a tracked stub tab, then switch away before the
  reply.
- **Append, rename and title changes:** prompts and `/title` in any stub; file updates arrive
  through the normal watchers.
- **Rewrite or delete:** edit or delete a file under `<root>/agents`; it is synthetic.
- **Missing or replaced checkout:** `git -C "$ROOT/workspace/harbor" worktree remove --force ../harbor-retry`,
  then optionally `git -C "$ROOT/workspace/harbor" worktree add -b replaced/retry ../harbor-retry`.

A shell startup file can put the real CLIs back ahead of `<root>/bin` on `PATH`. Type the stub's full path, or use Seshlog's
actions, which always use the configured stub.

## Optional shared Codex daemon and switch scenarios

The daemon and all histories remain inside the marked synthetic profile. The default direct-writer
mode remains available for legacy checks. Start the optional daemon explicitly from a terminal:

```sh
"$SESHLOG_SANDBOX/bin/codex" app-server --synthetic-daemon
```

Wait for `SESHLOG_DAEMON_READY`, then in other sandbox terminal tabs run:

```sh
SESHLOG_STUB_CODEX_DAEMON=1 "$SESHLOG_SANDBOX/bin/codex"
```

The shared process holds every visited rollout until it exits; TUIs have no persistent rollout
writer. It uses a private local Unix socket, never a model or network server. Ctrl+C stops it,
closes its descriptors and removes its socket. Stop its TUIs first. After a forced kill, remove only
this profile's `.codex-daemon.sock` before restarting it.

- `/new` and `/clear` create a new thread and emit an OSC terminal title.
- `/resume <id>` and `/picker <id>` switch to an existing synthetic thread.
- `/new delayed` emits the new ID while its rollout has a `.pending` suffix; `/publish` makes it
  visible to the production provider. Copy must remain unresolved before publication.
- `/titles off` removes the ID from the terminal title; `/titles on` restores it. In shared mode,
  title-disabled TUIs intentionally remain unresolved. Direct-writer mode can still identify them.
- `--synthetic-start-paused` prints `SESHLOG_START_WAIT` and waits for `/start` before creating a
  thread. This lets an older TUI do `/new` while a newer TUI is already running.
- `/barrier <label>` acknowledges all preceding commands with `SESHLOG_BARRIER`. Integration tests
  use these readiness messages, rather than sleeps, before scanning or inspecting.

`CodexSyntheticDaemonTest` drives the stale-resume/mixed-title, delayed-start, two-tab exchange and
unindexed-replacement scenarios through real stubs and the production provider, process inspector,
reconciler and Copy readers. It verifies retained daemon descriptors and absent TUI descriptors.
The verifier checks that all three installed helper files match this checkout. These helpers and their
fixtures are test resources only and must never appear in the plugin ZIP.

## Synthetic diff review delivery

Start the independent, test-only Codex 0.166.0-shaped queue before launching fictional Codex TUIs:

```sh
"$SESHLOG_SANDBOX/bin/codex" app-server --synthetic-review
```

Wait for `SESHLOG_REVIEW_SERVER_READY`. The helper exposes a private Unix WebSocket through the
synthetic Codex home's usual control-socket path. It never calls a model or a network service.
Existing sandbox Codex stubs register their exact session ID, process and checkout automatically.
The queue helper must be able to inspect those processes; a process sandbox that denies signal 0
will correctly make targets unavailable. Run the helper in the same local process context as the IDE.

In a fictional Codex terminal, use `/review hold`, `/review accept`, `/review refuse` or
`/review lost`. Hold queues without consuming; accept releases it when idle; refuse returns a
known rejection; lost stores the message and drops the receipt to exercise delivery unknown.
No automatic retry should follow. `SESHLOG_REVIEW_RECEIVED` and `SESHLOG_REVIEW_DONE` prove consumption.
The helper's `review-receipts.jsonl` records fictional IDs, byte counts and outcomes. Synthetic
queued text is kept only under this disposable profile's `.review-queue` until consumed.

Change files in the fictional `workspace/harbor` checkout, open a session's **Review Changes and
Comment…**, then use the **Commit** review tab and native diff editor tabs. Check comments on both
sides and across files, the exact preview, queue receipts and retained drafts after failures.
Use two TUIs and `/new` or `/resume` to prove a review never follows a changed terminal identity.
`ReviewSyntheticEndToEndTest` drives these helpers through the production socket adapter and
provider; it checks one addressed batch, holding/release, refusal, lost receipts and two-TUI swaps.

Ctrl+C stops the helper and removes only its own socket/symlink. Stop it before deleting a profile.
`review-queue.cjs`, `stub-agent.cjs`, `codex-daemon.cjs` and the live compatibility script are test
resources and must not ship. See [review evidence and limitations](diff-review.md).

## Testing the production Codex terminal wrapper

The plugin's `/terminal/codex` resource is a production launcher and must ship; the synthetic
`stub-agent.cjs` and `codex-daemon.cjs` remain test-only. The stub understands the launcher's `-c`
title override, including a later explicit override, without changing synthetic configuration.

For a physical manual-typing check, give the sandbox IDE a synthetic `ZDOTDIR` containing only a
`.zshrc` that sets `PATH="$SESHLOG_SANDBOX/bin:/usr/bin:/bin"`. This ensures a real agent cannot be
selected after personal startup files reorder PATH. The platform should prepend the Seshlog
wrapper after that file. Before running `codex`, inspect `command -v codex` and `PATH`: the wrapper
must be first and the marked profile's `bin` next. Use `SESHLOG_STUB_TITLES=0 codex` to prove the
wrapper enables the title; full-path stub launches with the same environment should omit it.
Shared-daemon mode also needs `SESHLOG_STUB_CODEX_DAEMON=1` and a ready synthetic daemon.
Run the usual sandbox verification before all physical checks.

## Limits

- Stub agents run on macOS and Linux. On Windows the profile and verification work, but launched
  stubs do not start, so Resume checks there need a separate arrangement.
- Codex identity uses the captured terminal thread-ID title or a direct TUI writer. Shared daemon
  files, timing, launch arguments and external real Codex processes cannot assign a thread to a tab.
- The profile does not create old-schema, locked, full or corrupt lexical databases, or a
  lower-memory machine. Those checks need their own deliberate setup.
- Seeded settings make the data folders explicit, so **Settings → Tools → Seshlog** shows the
  sandbox paths rather than the automatic defaults.
- Typing a real CLI into a sandbox terminal still runs it. The environment keeps Claude Code, Codex
  and Pi inside the sandbox root; opencode has no such variable.
