# Archived release notes

The five most recent releases are kept in `src/main/resources/META-INF/plugin.xml`.
Older entries are preserved here when that list is rotated.

## 0.5.6

- Keep active-session tracking when opening, closing, splitting or dragging terminal tabs between panes, and follow focus between panes.
- Replace the active terminal label with a bold blue title, or a bold underlined title when selected, to save space in the session list.
- Fix IntelliJ 2026.1 UI-thread errors when project roots change and when collecting restore snapshots.
- Remember restored sessions immediately, preserve restore state during quick shutdowns, and prevent late background snapshots from overwriting newer launch or stop decisions.

## 0.5.5

- Highlight the session associated with the selected IDE terminal tab and show an active terminal label. Switching tabs updates the highlight independently of the session selection and preview.

## 0.5.4

- Add Kill Session to the session context menu when a terminal tab or PID is known. Close the associated terminal tab in this project, stop its processes, and refresh the live status and PID while preserving the transcript.
- Stop lingering processes from closed terminal tabs, with forced termination if they ignore a normal stop signal. Report processes that could not be stopped.
- Ignore stale Claude Code PID files when the PID has been reused by a newer process.

## 0.5.3

- Replace the agent button row with a compact Agents menu. Select any combination, such as Claude Code and Codex together, or use Auto and All.
- Only list agents with sessions in the current project scope.
- Remember the last agent selection per project and restore it when reopened, retaining choices even when an agent temporarily has no sessions.

## 0.5.2

- Select any combination of agents with individual toggle buttons, such as Claude Code and Codex together. Selections are remembered per project; Auto and All shortcuts remain available.

## 0.5.1

- Fix duplicate Codex sessions by excluding internal guardian review transcripts. Existing session caches refresh automatically after upgrading.

## 0.5.0

- Pi support: discover, search, preview, resume and fork local sessions, with configurable executable and sessions directory. Requires Pi 0.84.2 or newer.
- Pi search and previews follow the persisted active branch, preserving original history across compaction. Pi sessions have no live badges or restart recovery.
- Search with literal terms and quoted phrases across session fields, with improved ranking and match navigation.
- Filter browsing and search by local calendar dates.
- Search and inspect tool activity from Claude Code, Codex and opencode, with limits on loaded content.
- Copy individual entries or the loaded dialogue from the conversation viewer.

## 0.4.2

- Includes session organisation, sibling-worktree support, missing-directory recovery, full conversation search navigation, and reliability improvements from 0.4.1.

## 0.4.1

- Safer terminal reuse, independent searches across project windows, and retries after transient content-read failures.
- Faster project filtering, sibling-worktree support, and remembered replacement directories for sessions whose working directory is missing.
- Session pins, local titles, hide/unhide, and preserved tree expansion and selection across refreshes.
- Full conversation view with search-match navigation, provider diagnostics, loading indicators, and retry actions.

## 0.4.0

- opencode support: list, name, search, preview, resume and fork local opencode sessions, read straight from opencode's SQLite database (read-only).
- Archived opencode sessions are hidden; a setting under Tools → Seshlog shows them.
- opencode publishes no lock or pid file, so its sessions never show as live and are not part of restore-after-restart.
- Content search and the preview pane now go through the session provider, so an agent that keeps no per-session file is a first-class citizen.
- Larger download (0.3 MB → 12 MB): the bundled SQLite driver ships native libraries for every platform.

## 0.3.0

- Codex support: list, name, search, preview, resume and fork local Codex CLI sessions.
- Agent filter: automatically show the provider with the most sessions, or force All, Claude Code or Codex per project.
- Reliable restore for Codex and fast project close/reopen cycles, including asynchronous live-session detection.
- Updated IntelliJ API compatibility and added a Marketplace/plugin-manager icon.

## 0.1.0

- Sessions grouped by project with title, branch, last activity and a live indicator, and one click to resume any of them in a terminal tab.
- Search across transcript content (user prompts and assistant replies), not just titles; results ranked by hits with a snippet.
- Preview pane: the selected session's last messages, with a "last N messages" control and a toolbar toggle.
- Fork Session: continue a session's conversation under a new session id (`--fork-session`), from the Seshlog context menu or by right-clicking a Terminal tab Seshlog knows the session of.
- Resume on a live session running in one of this project's terminal tabs becomes "Show Tab" and focuses it; tab titles follow the session title when Claude names the session later.
- Restore after restart: sessions that were live when the IDE closed are offered for resuming when the project reopens (Ask / Always / Never).
- Parsed-transcript metadata is cached in the IDE's system directory, so scans after a restart do not re-read every transcript. Metadata only — no message content.
