# Seshlog

Find and resume your local **Claude Code, Codex, opencode and Pi** sessions from an IntelliJ tool window.

[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33850-seshlog--coding-agent-session-manager)

<table>
  <tr>
    <td><a href="docs/marketplace/01-hero.png"><img src="docs/marketplace/01-hero.png" alt="Seshlog session browser" width="360"></a></td>
    <td><a href="docs/marketplace/02-session-list.png"><img src="docs/marketplace/02-session-list.png" alt="Session list and message preview" width="360"></a></td>
  </tr>
  <tr>
    <td><a href="docs/marketplace/03-search.png"><img src="docs/marketplace/03-search.png" alt="Search across conversations" width="360"></a></td>
    <td><a href="docs/marketplace/04-fork.png"><img src="docs/marketplace/04-fork.png" alt="Fork a session from the context menu" width="360"></a></td>
  </tr>
</table>

Click a screenshot to view it full size.

## What it does

- Groups sessions by project, with titles, branches and recent activity.
- Resumes or forks a session in an IDE terminal; focuses its tab if it is already running.
- Searches titles, project paths and conversations, with highlighted matches and a message preview.
- Filters by agent, project and date. Pin, rename or hide sessions to keep the list manageable.
- Shows activity for tracked sessions, notifies you when they need input, and offers to restore
  eligible sessions after an IDE restart.
- Copies the latest assistant reply, or the latest explicit Claude Code or Codex plan, without
  selecting terminal output.

Works with both Classic and Reworked terminals.

## Get started

You need an IntelliJ Platform IDE **2024.1 or newer** with the bundled Terminal plugin, plus at least
one supported agent installed and available on your terminal shell's `PATH`. Pi requires **0.84.2 or newer**.

Open **View → Tool Windows → Seshlog** to browse your sessions.
Use **Settings → Tools → Seshlog** to change data directories, executable paths, per-agent command
arguments, notifications and restart behaviour.

To build and install from source, use JDK 17 and run:

```sh
./gradlew buildPlugin
```

Then choose **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select
`build/distributions/seshlog-<version>.zip`.

## Using Seshlog

The **Agents** menu selects which agents to show. Project and date filters apply to both browsing
and search; you can include sibling worktrees or show sessions from all projects.

Search matches words in any order. Use `"quoted phrases"` for exact phrases. Select a result to read
its conversation, then use **F3 / Shift+F3** in the viewer to jump between matches. Clear the search
to return to the recent-message preview.

Right-click a session to resume, fork, pin, rename, hide or copy from it. **Kill Session** stops its
processes and closes its terminal tab in the current project; the saved conversation remains available.

For keyboard copying, assign **Seshlog: Copy Last Assistant Message** and **Seshlog: Copy Latest Plan**
under **Settings → Keymap**. They work from the session list, conversation viewer or a terminal
associated with a known session. Shortcuts are unassigned by default.

### Limitations

- Activity detection depends on the agent and whether Seshlog can identify its running process.
  Fresh manually started Codex sessions cannot always be associated with a terminal.
- Large conversations have search and viewer limits; partial coverage is shown in the UI.
  Images and thinking are excluded. Tool text is searchable for Claude Code, Codex and opencode.
- Pi uses its saved active branch and includes user/assistant text only. Custom session directories
  must be configured in Settings. Pi sessions are not restored after a restart.
- Plan copying supports explicit Claude Code and Codex plans only. A Claude plan stored solely as
  a file reference copies that file's current contents. See the [format notes](docs/clipboard-format-evidence.md)
  for details.

## Privacy

Seshlog runs locally and makes **no network requests**. There is no telemetry or analytics.

Agent transcripts are read-only. Seshlog keeps settings and metadata caches in IDE storage;
conversation content and the search index stay in memory. Reading opencode's SQLite database can
update its shared-memory index (`opencode.db-shm`), but does not modify stored sessions.

## Development

```sh
make run      # Launch a sandbox IDE with the plugin loaded
make check    # Run tests
make release  # Test, build and check the plugin zip
./gradlew verifyPlugin  # Check IDE compatibility
```

Issues and pull requests are welcome. New parsing behaviour needs a synthetic fixture in
`src/test/resources/fixtures/`; never commit real transcripts.
See [AGENTS.md](AGENTS.md) for development conventions and [PLAN.md](PLAN.md) for current work.

## License

[MIT](LICENSE)
