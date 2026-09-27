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
- `SESHLOG_BENCH=1 ./gradlew test --tests '*RealDataScanBenchmark*'` scans the real `~/.claude`.
  Skipped by default; never in CI.

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
- Do not widen into README roadmap items (generated titles, live opencode sessions, housekeeping)
  unless asked. "Rename" in the roadmap is the local title override in PLAN.md.
- Line references in PLAN.md carry the symbol name; trust the symbol, re-find the line.
