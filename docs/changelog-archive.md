# Archived release notes

The five most recent releases are kept in `src/main/resources/META-INF/plugin.xml`.
Older entries are preserved here when that list is rotated.

## 0.6.10

- Show Claude Code and Codex session worktrees with checkout details, the observed Git branch or detached HEAD, and the full recorded path in tooltips.
- Follow recorded working-directory changes as sessions move between checkouts. Project filtering and the Sibling worktrees option use the updated location in both browsing and search.
- Open a session's verified worktree in a new IntelliJ window, or focus the window already using it. The original project window and its agent terminals stay open.
- Keep missing or uncertain worktrees visible while disabling opening. New project opening inside protected agent data directories remains disabled; an already-open project there can still be focused.

## 0.6.9

- Track Codex terminals again from codex-cli 0.158, which runs sessions through a shared background server rather than the terminal's own process. Seshlog matches each Codex terminal to the session that server has open for the same directory, so new Codex sessions attach to their tab, relaunching Codex in a tab attaches it again, and copying a reply or plan works in tabs started with a plain codex command.
- Attach only when that match is unambiguous across every Codex terminal on the machine, including those outside the IDE. Several Codex tabs in one directory are told apart by when each started its session, so they no longer all stay unattached; two started within seconds of each other still do rather than risk copying from the wrong one. A resumed Codex tab follows a new session started inside Codex once Codex has closed the previous one.
- Own a forked session's tab from the moment it opens, as for a new session, so it attaches to the fork, offers Fork and Copy, and is restored after a restart.
- Resume a session in the tab its agent last exited in, even after the session was renamed, and bring a reused tab to the front instead of opening another.
- Recognise Codex terminal transcripts written by the new server, including sessions run with --no-daemon.

## 0.6.8

- Show a resumed Claude Code session as one row for its latest continuation in the session list, search and attention views. Earlier transcripts stay searchable and open from Earlier Sessions in the context menu. Branch and working directory follow the resumed session, and forks and still-running earlier sessions keep their own rows.

## 0.6.7

- Collapse the message preview to its header row by clicking the header. Collapsed or expanded is remembered across restarts, and a collapsed preview does not read transcripts.

## 0.6.6

- Keep the toolbar on one row with overflow for narrow windows, move filters into a menu, and give search its own full-width row.
- Make session rows easier to scan with titles above agent, time and branch details. Keep activity and unread badges aligned on the right, truncate long text to fit, and show full details in tooltips.

## 0.6.4

- Pick up new and updated sessions as agents write them. The agent data directories, and folders created later such as Codex's daily folder, are now covered by the IDE's file watcher, so fresh Claude Code and Codex sessions appear, show activity and attach to their terminal without a manual refresh.
- Copy from a terminal straight after an agent's first reply. When a running agent's session has not been indexed yet, Seshlog rescans once before reporting that no session is known.
- Detach a terminal tab from its session when the agent process exits, so an ended Claude Code session is no longer highlighted while Codex starts in the same tab. Copy and Fork from that tab keep using its last session until another agent is identified.

## 0.6.2

- Keep terminal tracking when switching from Claude Code to a fresh Codex session in the same tab. On macOS and Linux, identify the session from the transcript held open for writing by the Codex process.
- Recheck the invoking terminal's current agent in the background before copying a reply or plan, so a shortcut does not use the previous agent's session while the new session is still unidentified.
- Exclude Codex subagent and approval transcripts from terminal discovery. Follow the current CLI transcript when resume arguments refer to an older session, and leave ambiguous matches unresolved.

## 0.6.1

- Improve terminal session tracking across split panes and tab moves by remembering the exact reworked terminal view on the IntelliJ IDEA 2026.2 terminal platform.
- Retry session discovery every two seconds as terminals become ready. Preserve known associations when process information is temporarily unavailable, and recognise agents that replace their shell process.
- Discover manually resumed agents from their executable and explicit session arguments, including codex resume with a session ID. Follow session changes in reused terminals without letting stale background results overwrite newer associations.
- Resolve copy actions from the invoking terminal and the innermost split pane, and retry missed Claude Code session adoption before copying.

## 0.6.0

- Add Seshlog: Copy Last Assistant Message for Claude Code, Codex, opencode and Pi. Copy the latest persisted assistant reply with its Markdown, code blocks and line breaks, skipping prompts, tools and thinking.
- Add Seshlog: Copy Latest Plan for explicit Claude Code plan records and Codex Plan Mode blocks. Recorded plan text takes precedence over a linked file in the same record; file-only references copy the file's current contents. opencode and Pi plan copying are not yet supported.
- Use both actions from an associated terminal tab, a historical session in the list or the conversation viewer. Both appear in the session context menu. Shortcuts are unassigned by default; Tools → Seshlog explains how to configure them in Settings → Keymap.
- Read fresh content in the background, preserve the invocation's source session and discard superseded requests. Missing, unreadable or oversized content leaves the clipboard unchanged with brief feedback.
- Resolve terminal shortcuts from the invoking terminal view, including the reworked terminal, without falling back to an unrelated selected session.
- Keep session associations while terminals are dragged between split panes. Temporary detachment no longer loses copying, active highlighting, Show Tab or restore tracking; closing a tab still clears its association.

## 0.5.9

- Show what a running session is doing: working, waiting for your input with how long, or interrupted. Claude Code reports this itself; Codex, opencode and Pi states come from the transcript or database and appear while a Seshlog-owned tab still runs the agent.
- Refresh terminal activity badges every two seconds using matching session processes, so exiting an agent or running an unrelated command clears its badge. Reusing a terminal tab replaces its previous session association, and waiting labels resize as elapsed time grows.
- Notify when a running session stops working and waits for input, unless its terminal tab is on screen. Tools → Seshlog → Notifications turns it off.
- Add an Additional arguments field per agent under Tools → Seshlog. It is appended as typed to resume and fork commands, including Copy Resume Command and restore after restart, so a default model or permission mode can follow every resumed session.

## 0.5.8

- Select a search result to view its full loaded conversation in the bottom pane, with matching terms and phrases highlighted. Search respects the current date, agent and project filters.
- Use Previous/Next match or F3/Shift+F3 while focused in the conversation pane to move through occurrences, automatically scrolling and wrapping around. Matching tool entries expand automatically.
- Clear search to return to the recent-message preview. Prevent pending preview loads from displaying an outdated selection or query.
- Existing transcript size limits continue to apply; partial content and search coverage are indicated in the conversation pane.

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
