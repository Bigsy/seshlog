# Seshlog

https://plugins.jetbrains.com/plugin/33850-seshlog--coding-agent-session-manager

An IntelliJ plugin that lists your local Claude Code and Codex sessions, titled the way the agent
titled them, with one click to resume any of them in a terminal tab.

Restarting your IDE closes every terminal tab, and with them your running coding-agent sessions.
Getting back to one means remembering which project it was in and finding it in the agent's session
picker. Seshlog makes it a click.

![Every Claude Code session, one click to resume](docs/marketplace/01-hero.png)

## Features

- **Sessions grouped by project**, sorted by last activity, with the agent's own title, git branch,
  prompt count, and a live indicator for sessions running right now.
- **Agent filter** which defaults to the provider with the most sessions in this project. Force All,
  Claude Code, or Codex from the toolbar; the choice is remembered per project.
- **Full-text search** across your prompts and the assistant's replies, not just titles. Results are
  ranked by hit count with a snippet of the first match.
- **Preview pane** showing the last messages of the selected session without opening anything.
- **Resume or fork** into a terminal tab. If the session is already running in one of this project's
  tabs, Seshlog focuses that tab instead of starting a duplicate.
- **Restore after restart** — sessions that were live when the IDE closed are offered for resuming
  when the project reopens (Ask / Always / Never).

![Session list with preview pane](docs/marketplace/02-session-list.png)
![Search across transcript content](docs/marketplace/03-search.png)
![Fork a session from the context menu](docs/marketplace/04-fork.png)

## Requirements

- IntelliJ IDEA 2024.1 or newer (any IntelliJ Platform IDE with the bundled Terminal plugin).
- [Claude Code](https://claude.com/claude-code), [Codex](https://developers.openai.com/codex/cli/),
  or both installed, with the corresponding executable on the PATH of the shell your Terminal tool
  window uses.
- JDK 17 to build from source.

## Install

Not yet on the JetBrains Marketplace. To build and install it yourself:

```sh
./gradlew buildPlugin
```

then in the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick
`build/distributions/seshlog-<version>.zip`.

Open the tool window with **View → Tool Windows → Seshlog** (it docks on the right).

## Privacy

Seshlog is entirely local. It makes no network requests of any kind — there is no telemetry, no
analytics, and no remote service involved.

It reads the agents' data directories — `$CLAUDE_CONFIG_DIR`/`~/.claude` and
`$CODEX_HOME`/`~/.codex` — **read-only** and never writes to them. Its own state lives in the IDE's
own storage:

- parsed-transcript caches in `<IDE system dir>/seshlog/`, holding per-session metadata
  only — title, cwd, branch, timestamps, prompt count. No message content is written to it: the
  label for an untitled session is reduced to a single line of at most 80 characters at parse time,
  and the raw prompt is never retained. Safe to delete at any time;
- the content search index, which is held in memory only and never written to disk;
- settings in `seshlog.xml`, and the per-project agent filter and restore list in `workspace.xml`.

## Settings

**Settings → Tools → Seshlog**

| Setting | Default | |
| --- | --- | --- |
| Claude data directory | auto | `$CLAUDE_CONFIG_DIR`, else `~/.claude` |
| Claude executable | `claude` | Resolved via the terminal shell's PATH |
| Codex data directory | auto | `$CODEX_HOME`, else `~/.codex` |
| Codex executable | `codex` | Resolved via the terminal shell's PATH |
| Agent toolbar filter | Auto | Shows the provider with the most sessions; a forced choice is stored per project |
| Show sessions from all projects | off | Otherwise only sessions whose cwd is under the open project |
| Hide untitled sessions with fewer than *n* prompts | 1 | Filters out aborted starts; 0 shows everything |
| Sessions live at shutdown | Ask | Ask / Always restore / Never restore |
| Preview pane | on, 2 messages | Shown below the session list |

## Development

```sh
make run      # ./gradlew runIde — sandbox IDE with the plugin loaded
make check    # ./gradlew test
make release  # build + verify the plugin zip
```

`./gradlew verifyPlugin` runs the JetBrains Plugin Verifier against the recommended IDE range; CI
runs it on every push.

Both are also worth knowing:

- `SESHLOG_BENCH=1 ./gradlew test --tests '*RealDataScanBenchmark*'` times a scan of your real
  `~/.claude`. It is skipped by default and never runs in CI.
- The plugin targets Kotlin API level 1.9 because platform 2024.1 bundles the Kotlin 1.9 stdlib;
  using a 2.x-only stdlib API is a compile error rather than a runtime failure on older IDEs.

Transcript parsing is deliberately defensive — both agents' `.jsonl` schemas are internal and can
change between versions, so unknown record types are skipped, missing fields become null, and a
malformed line is logged at DEBUG and ignored. See `src/test/resources/fixtures/` for the shapes
that are covered.

## Roadmap

- **More agents.** Add an OpenCode implementation behind the existing `SessionProvider` seam.
- **Titles for the untitled.** Around 7% of sessions never get an `ai-title`; generate one from the
  first exchange and store it on the Seshlog side.
- **Housekeeping.** Delete or archive old transcripts from the UI, and show disk usage per project.
- **Rename.** A Seshlog-side title override — never a write into Claude's own files.

## Contributing

Issues and pull requests are welcome. `make check` should pass, and new parsing behaviour should
come with a fixture in `src/test/resources/fixtures/` — please make sure any fixture you add is
synthetic rather than a real transcript.

## License

[MIT](LICENSE)
