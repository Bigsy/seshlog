# Seshlog — working notes for agents

IntelliJ plugin (Kotlin) that lists local Claude Code, Codex, opencode and Pi sessions and resumes them
in a terminal tab. README.md is the user-facing overview; PLAN.md is the current work plan. This file
holds the conventions and constraints a new session needs before touching code.

## Build, test, run

```sh
make check    # ./gradlew test
make run      # ./gradlew runIde — sandbox IDE with the plugin loaded
make release  # test + buildPlugin + verify the zip
./gradlew verifyPlugin   # JetBrains Plugin Verifier, also run in CI
```

- If Gradle cannot reach the network in this environment, add `--offline`.
- `SESHLOG_BENCH=1 ./gradlew test --tests '*RealDataScanBenchmark*'` scans real Claude, Codex and Pi histories, printing only counts and timings.
  Skipped by default; never in CI.
- `make codex-check` (opt-in `CodexLiveCheck`, `SESHLOG_CODEX_CHECK=1`) checks, after a Codex upgrade, that
  the shared app-server still leaves the evidence `CodexDaemon` matches tabs on. Counts only; never in CI.

## Reading session-management diagnostics

Session tracking writes **always-on, local, rotating JSON Lines** under
`PathManager.getLogPath()/seshlog/`. This is the **running IDE's log directory**, not the repository,
agent data directory, or IDE system/cache directory. Logging starts when Seshlog tracking/indexing
starts. Unit tests suppress the application logger; logger tests use synthetic temporary files.

Ask which IDE reproduced the issue, or locate both sets of logs and inspect their timestamps and
`env.idePid`/`run` before choosing one. A sandbox and daily IDE can run simultaneously and have
independent files. Do not assume the most recently built plugin is installed in the daily IDE.

- **`make run` / `./gradlew runIde` sandbox:** normally
  `.intellijPlatform/sandbox/seshlog/IU-2026.2.3/log/seshlog/` in this checkout. The product/version
  segment varies with Gradle properties, installed-platform overrides and SDK upgrades. Discover
  actual files rather than hard-coding that segment:

  ```sh
  rg --files --hidden --no-ignore .intellijPlatform/sandbox -g 'sessions*.jsonl'
  ```

- **Normal installed IDE on macOS:** normally
  `~/Library/Logs/JetBrains/IntelliJIdea<version>/seshlog/` (product names can differ):

  ```sh
  rg --files --hidden --no-ignore "$HOME/Library/Logs/JetBrains" -g 'sessions*.jsonl'
  ```

  Linux normally uses `~/.cache/JetBrains/<product><version>/log/seshlog/`; Windows normally uses
  `%LOCALAPPDATA%\JetBrains\<product><version>\log\seshlog\`. Custom `idea.log.path` overrides these.

In either IDE, **Find Action → Seshlog: Open Session Diagnostics** reveals the actual current file
and writes a `report_requested` marker first. It is also in the session context menu and
**Settings → Tools → Seshlog → Session Diagnostics**. Invoking from a session or its terminal
includes the hashed session/tab reference when available. **Help → Show Log in Finder/Explorer**
locates the parent IDE log directory if the plugin action is unavailable. If there are no files,
check that this diagnostics build is loaded in that IDE and check its `idea.log` for
`Seshlog session diagnostics unavailable`; do not search or change agent transcripts to enable logs.

There are **four files of at most 5 MiB each**: `sessions.jsonl` is current, `sessions.1.jsonl`
is the newest backup, then `.2` and `.3` (oldest). They survive restart; the oldest is overwritten
on rotation. Capture all four promptly after a failure, before more testing rotates the evidence.
To inspect them without modifying them, set the directory explicitly for the IDE under test:

```sh
seshlog_diag_dir='/absolute/path/to/the/IDE/log/seshlog'
tail -n 100 "$seshlog_diag_dir/sessions.jsonl"
rg -n '"event":"(report_requested|diagnostic_gap|poll_failed|daemon_match_skipped|reconcile_rejected|tab_released|pending_expired)"' "$seshlog_diag_dir"/sessions*.jsonl
python3 - "$seshlog_diag_dir" <<'PY'
import json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
# Oldest to newest. A running writer can rotate during a read; copy the files first for a stable report.
for name in ['sessions.3.jsonl', 'sessions.2.jsonl', 'sessions.1.jsonl', 'sessions.jsonl']:
    path = directory / name
    if not path.is_file():
        continue
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue  # An interrupted write can leave a partial final record.
        print(json.dumps(record, ensure_ascii=False))
PY
```

How to interpret the records:

- `time` is UTC. `run` distinguishes logger lifetimes/restarts; `seq` identifies emitted records
  within a run (concurrent producers can enqueue out of sequence). `env` repeats schema, plugin/IDE/
  Java/OS versions and IDE PID on every record, so rotation does not discard the environment.
- Correlate `fields.poll` across `poll_started`, `tab_snapshot`, process/descriptor evidence,
  `daemon_*`, `identity_evidence`, `shell_discovery`, reconciliation and `poll_applied`.
  `project` and `tracker` distinguish project windows. `tab` is an opaque in-memory identity;
  compare it only within one run. Tab generations explain rejection of delayed updates.
- Process records include PID, parent PID and start time; account for PID reuse. Codex inference
  considers **all machine-wide TUIs**, so an external terminal can explain `unequal_counts` or
  `ambiguous`. Marker identity mismatch, missing cwd, unreadable rollouts, descriptor timeouts,
  and index lag have separate events/reasons. `recordedCliVersion` is the **historical rollout
  writer's version**, not proof of the current TUI or daemon version. Ask for the installed agent
  version if needed; no diagnostic probe launches an agent.
- `pending_expired`, `tab_tracked`, `tab_adopted`, `tab_released`, `tab_closed`, command/resume,
  copy and restore events explain lifecycle changes. `tab_snapshot` includes missing adapters,
  shells and detached tabs. `scan_*`/`provider_scanned` show index freshness and provider health.
- `diagnostic_gap` reports lost records after queue saturation or write failure; truncated fields
  are marked. Missing evidence during a gap is not proof that an event did not happen. Logging
  is asynchronous and bounded; an abrupt IDE kill can lose the final queued records.
- Paths and session IDs are SHA-256 references (first 24 lowercase hex characters). To correlate
  a known ID from **Copy Session ID**, hash its exact UTF-8 text with no newline. Paths are lexically
  normalized before hashing; daemon matching hashes canonical cwd where it actually canonicalizes.
  Raw prompts, replies, titles, commands, environment variables and exception messages are excluded.
  These are local debugging records, not anonymization guarantees; review before sharing.

When reporting a tracking issue, cite the log directory, run, incident time, affected tab/session
reference, the first failed or changed association, and the supporting poll records. Do not infer
the cause from a lone unknown match. Read `idea.log` only when these logs identify a platform or
writer failure requiring it; it has a broader and potentially more sensitive scope.

## Hard constraints

- **Platform 2026.2 (`pluginSinceBuild = 262`), JDK 25, Kotlin API level 2.3.** The 2026.2 platform
  bundles the Kotlin 2.4 stdlib. Check the API level before using anything newer than 2.3.
- **Agent data is read-only.** Never write into `~/.claude`, `~/.codex`, `~/.pi/agent` or the opencode data dir.
  Seshlog-side state (settings, caches, any organisation metadata) lives in IDE storage only.
- **No network.** No telemetry, no remote calls of any kind.
- **No session content on disk.** The parse cache holds metadata only (title, cwd, branch,
  timestamps, prompt count). The content search index is in memory only. Keep it that way.
- **Fixtures are synthetic.** Never commit a real transcript. New parsing behaviour needs a fixture
  in `src/test/resources/fixtures/`.
- **Session readers never throw for malformed data.** Unknown record types are skipped, missing
  fields become null, bad lines are logged at DEBUG and ignored. A provider that cannot read its
  storage returns empty rather than failing the scan.

## Layout

`src/main/kotlin/com/hedworth/seshlog/`

| Package | Role |
| --- | --- |
| `model/` | `Session`, `SessionProvider` (the seam every agent implements), `AgentKind`, `Activity`, `ConversationMessage` |
| `claude/`, `codex/`, `opencode/`, `pi/` | One provider each: scan, resume/fork command, conversation text, last messages, content stamp |
| `cache/` | File-backed parse cache for per-session metadata |
| `index/` | `SessionIndex` (app service, aggregates providers), `SessionWatcher`, `ContentSearchIndex` + `ContentSearchService`, `SessionFilter` and `ActivityTransitions` (pure) |
| `terminal/` | Finding/creating terminal tabs, owned-tab registry, resume command building, shell quoting |
| `restore/` | Sessions live at shutdown → offered on reopen |
| `settings/` | `SeshlogSettings` (app), `AgentFilterState` (project), configurable UI |
| `ui/` | Tool window, tree panel, cell renderer, preview pane, actions, `WaitingSessionNotifier` |

### Persistent state pattern

- App-wide, user-level: `@Service(APP)` + `@State(storages = [Storage("seshlog.xml")])`, see
  `SeshlogSettings`.
- Per-project: `@Service(PROJECT)` + `@State(storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])`,
  see `AgentFilterState` and `RestoreState`.
- Anything keyed by session id that is per-user rather than per-project (pins, title overrides,
  hidden flags) belongs at app level, not in `workspace.xml`.

## Testing conventions

- JUnit 4. Plain classes for pure logic, `BasePlatformTestCase` for anything needing a `Project`
  or the EDT (see `ui/SeshlogToolWindowTest`).
- Keep filtering, parsing, ranking and restore planning free of IntelliJ types so they stay
  unit-testable without the platform fixture. `SessionFilter` and `RestoreCandidates` are the model.
- Every reliability fix in PLAN.md gets a regression test that fails before the change.
- Tests inject behaviour through constructor lambdas (see `ContentSearchIndex(extractor, contentStamp)`)
  rather than mocking framework classes.

## Threading

- Provider `scan` and content extraction run on background threads. Anything that touches the
  filesystem must not run inside a Swing render (`SessionTreePanel.render`, the cell renderer).
- Search results are delivered on the EDT by `ContentSearchService`; callers guard against stale
  delivery by re-checking the query.

## Working the plan

- Take items in the order PLAN.md suggests unless told otherwise. Tick a box when its regression
  test passes under `make check`. Note in the plan where a manual check in `make run` is still owed.
- One commit per plan item. No co-authorship or "Generated with" trailers.
- Keep implementation scoped to PLAN.md unless asked otherwise. Local title overrides never rename
  or rewrite agent-owned transcripts.
- Line references in PLAN.md carry the symbol name; trust the symbol, re-find the line.
