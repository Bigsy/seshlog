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
  status, and removes it on exit. A fork copies the history under a new ID.
- **Codex:** keeps the rollout open for writing, like the CLI, and brackets each turn with
  `task_started` / `task_complete`. A fork writes a new rollout with `forked_from_id`.
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

## Limits

- Stub agents run on macOS and Linux. On Windows the profile and verification work, but launched
  stubs do not start, so Resume checks there need a separate arrangement.
- Codex's daemon matching considers every Codex TUI on the machine, so a real Codex running
  elsewhere can still affect Codex tab association.
- The profile does not create old-schema, locked, full or corrupt lexical databases, or a
  lower-memory machine. Those checks need their own deliberate setup.
- Seeded settings make the data folders explicit, so **Settings → Tools → Seshlog** shows the
  sandbox paths rather than the automatic defaults.
- Typing a real CLI into a sandbox terminal still runs it. The environment keeps Claude Code, Codex
  and Pi inside the sandbox root; opencode has no such variable.
