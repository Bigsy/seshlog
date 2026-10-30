# Using Seshlog

Open **View → Tool Windows → Seshlog** to browse your saved sessions. Configure agent executables,
arguments and data folders in **Settings → Tools → Seshlog**. Both Classic and Reworked terminals
are supported.

## Sessions and terminals

Use **New Session** in the toolbar or on a project row to start an agent. Right-click an existing
session to resume it, fork its conversation, open its history, pin it, rename it locally or hide it.
Local names and organisation stay in Seshlog; the agent's saved conversation is unchanged.
Double-click a session, or press Enter in the session list, to resume it or focus its tracked terminal.

**Kill Session** stops its processes and closes its terminal tab in the current project. The saved
conversation remains available. Seshlog can offer to restore sessions that were running when the
IDE closed; Pi sessions are not restored.

A session can appear in the list before Seshlog identifies its terminal. Activity and terminal
actions depend on that association. Missing or ambiguous process information can leave a terminal
unassociated, especially for new Pi and opencode sessions.

Codex terminals share a background server, so nothing in their processes shows which conversation
a terminal moves to after `/new`, `/clear`, `/resume` or the `codex resume` picker. The terminal
title is the only place Codex reports it. When Seshlog starts, resumes or forks a Codex session, it
adds `thread-id` to Codex's default title items with a command-line setting
(`-c tui.terminal_title=[...]`). Your `~/.codex/config.toml` and other terminals are unchanged. A
`tui.terminal_title` setting of your own in **Additional arguments** takes precedence.

Codex you start yourself in a terminal, and every Codex terminal on Windows, needs the setting in
the `[tui]` section of `~/.codex/config.toml` instead. It changes the title in every terminal:

```toml
[tui]
terminal_title = ["activity", "thread-name", "project-name", "thread-id"]
```

Start Codex again in open terminals afterwards. Without the thread ID in the title, copying from a
Codex terminal that switched conversations reports an unknown session instead of guessing.

## Search

The tool window searches titles, project paths and conversation text. The **Filters** menu controls
agents, dates, hidden sessions and sibling worktrees. Project and date filters apply to search too.

The toolbar's **Sort** menu offers **Best match** (the search default) and **Recently active**.
Recently active uses the last session activity, including later replies, rather than creation time.
The selected sort chooses the first 100 matching sessions across the full filtered scope.
Best match becomes available when you enter a search; browsing uses Recently active.

Search starts as a flat list, with folder, branch and full working path on each row. Toggle
**Group by folder** in the toolbar to use the folder headings. Browsing starts grouped. Seshlog
remembers search sort and the two grouping choices separately for each project in IDE workspace
settings. The quick switcher keeps its existing newest-first filtering behavior.

Pins keep priority: pinned sessions appear first in flat views; grouped views put folders containing
pins first, then pinned sessions within each folder. During search this applies within the selected
100 matches; pinning does not pull an otherwise excluded match into the limit. Folder groups follow
their best match or most recently active matching session, according to the selected sort. Grouping
can therefore put a weaker match above a stronger match from another folder; use flat search for
global ordering. Right-click a flat session for its normal session/worktree actions; **New Session**
uses that session's working folder. Folder-only actions remain on folder headings.

Use **Search in** beside the search field to choose **Your messages**, **Assistant replies** and
**Tool activity**. **Conversation**, the default, includes your messages and assistant replies.
Titles, local names and project paths are always searched. Turn off all three conversation sources
for **Titles & paths**. Your choice is shared across project windows and remembered after restart.

Ordinary words help rank results by relevance. Put text in `"quoted phrases"` to require an exact
match within a title, path or single message. Different quoted phrases can match different messages
in the same session. Matching query text is bold in session titles, locations and excerpts. Agent
labels are blue, branches green, and paths use a quieter colour. Each result labels its matching
excerpt: **Title**, **Project path**, **You**, **Assistant**, **Tool call** or **Tool result**.

Select a result to preview it, or choose **Open Conversation** for a read-only editor tab.
**F3 / Shift+F3** move between matches in the selected sources. Other messages remain visible as
context. An opened conversation tab keeps the query and source selection it was opened with;
normal IDE Find searches the whole document. Markdown rendering uses the IDE's Markdown plugin.

Clear the search to return to recent messages. Click the preview header to collapse or expand it;
the choice is remembered.

Large conversations have search and viewing limits, with partial coverage shown in the UI.
Images and thinking are excluded. Tool text is available for Claude Code, Codex and opencode,
including Claude subagent results. Pi provides dialogue only. Shared size limits can omit later
messages even when tool activity is excluded from matching.

## Quick session / worktree switcher

Press **⌘⌥S** on macOS or **Ctrl+Alt+Shift+P** on Windows/Linux, or use
**Find Action → Seshlog: Switch Session / Worktree**. An open project is required.

The popup searches titles, projects, paths and branches across all indexed agents. It follows the
tool window's project scope: with **All Projects** off, it shows only this project's sessions, plus
sibling worktrees when that filter is on. Agent and date filters don't apply.
All typed words must match somewhere in those fields. Results are always newest activity first, so
the most recently used session or worktree is at the top. It doesn't search conversation text or
change your tool-window filters. Hidden sessions stay hidden. Up to 200 results are displayed.

Use **Up/Down** to select a row. **Enter** focuses a tracked terminal and its project window.
For a stopped session, Enter focuses **Resume** without launching it. **Left/Right** or
**Shift+Tab/Tab** move between enabled **Open Worktree** and **Resume** buttons.
Opening a worktree alone doesn't start an agent.

Missing or uncertain directories disable actions that need them. Running sessions without an
associated IDE terminal are labelled and cannot be resumed from the popup. Selecting a row alone
doesn't clear its unread badge. See the [switcher reference](session-switcher.md) for detailed
navigation behaviour.

## Worktrees

Claude Code and Codex sessions can show their recorded worktree, observed branch or detached HEAD.
Hover for the full path and location details. **Open Worktree in IntelliJ** opens the checkout in
a new window, or focuses the window already using it. Your original project stays open.

**Open Worktree and Fork Session** opens that window and starts a separate conversation with the
original history in a terminal there. The original session keeps running. Both conversations use
the same existing worktree files. The terminal starts in the session's recorded subdirectory.
Cancelled project opens or changed directories stop the launch.

The **Sibling worktrees** filter includes sessions in related checkouts. Worktree locations reflect
the latest supported location recorded by the agent; a working directory mentioned in a message or
tool command isn't enough to confirm a move. Missing or uncertain checkouts stay visible with
opening disabled. Use **Refresh** to check again.

Seshlog blocks opening new projects inside configured agent data folders because IntelliJ would
write project files there. It can still focus a project already open in one of those folders.

### Forking into a new worktree

For Claude Code or Codex, choose **Fork into New Worktree…** on a session. Choose a local starting
ref, a new branch name and an absent destination folder outside existing checkouts. The parent
folder must already exist. The session's recorded subdirectory must exist in the chosen commit.

Seshlog creates committed files at that ref, opens a new IntelliJ window through the normal trust
flow and launches a native conversation fork. It leaves dirty and ignored files in the source
checkout; it does not install dependencies or copy local configuration. Git checkout hooks are
suppressed for this operation. If opening is cancelled or launch fails, the branch/worktree stay
available at the chosen path. No automatic deletion or rollback occurs. Extra agent arguments
still apply; avoid conflicting directory/worktree flags. opencode/Pi retain ordinary forks only.

### Reviewing a checkout

Choose **Review Worktree Changes…** from a session or folder row. The default base is `HEAD`,
showing the current tracked changes plus untracked files. Enter a local branch, tag or commit and
press **Refresh** to include committed differences from that base. This is a direct comparison
with the selected commit, not an automatic merge-base comparison. Ignored files are excluded.

Select a file and press Enter or **Show Diff** for IntelliJ's native diff viewer. Changes belong
to the whole checkout, including work by other agents and you. Renames appear as deletion/addition;
binary files, symlinks, submodules and files over 8 MiB require the IDE's Git tools. Lists are limited
to 5,000 files and bounded Git output; limits/errors are shown explicitly. Nothing is fetched or
modified, and file previews remain in memory.

### Deleting a worktree

Right-click a linked worktree's folder in the session list and choose **Delete Worktree…**. Seshlog
runs `git worktree remove`, which deletes the worktree's whole folder, even when you right-click a
subfolder. Git refuses if the worktree has uncommitted or untracked changes or is locked; Seshlog
never forces it. Ignored files are removed with the folder, so preserve any ignored local files you need first. Committed work stays on the branch.

The confirmation shows the folder and branch. **Hide its sessions** (on by default) hides every
session recorded in that worktree; turn on **Show hidden** in the filters to see them again. **Also delete
branch** runs `git branch -d`, which keeps a branch that isn't fully merged. Conversations are
never deleted: they stay readable, and Resume asks for a replacement folder.

Deleting is unavailable for the main checkout, while a session in the worktree is running or has a
terminal tab, while an IntelliJ window has the worktree open, and for worktrees inside agent data
folders, worktrees containing configured agent data, or shared Git metadata overlapping agent data. Seshlog refreshes sessions and checks all of this again before
running git. It can't always see an agent running in a terminal outside IntelliJ, so check that
nothing else is using the worktree first.

## Attention and copying

**Seshlog: Attention Inbox** in Find Action or the tool-window toolbar lists unread sessions across
all projects, excluding locally hidden sessions. Select one to preview its latest assistant reply.
The list shows the newest 200 unread sessions and reports the total; previews show up to 6,000
characters from recent messages and stay in memory. This view does not change the session filters.

Previewing alone does not clear unread state. **Mark Read** acknowledges only the completion whose
preview was loaded, preserving a newer reply that arrived meanwhile. **Open Conversation** uses the
normal conversation viewer; **Show Terminal** validates the associated tab before focusing it.
**Open Worktree** reuses the normal checkout validation and trust flow. An unread reply is not a
claim that the agent succeeded or that it needs a decision.

An **unread** badge means a tracked agent finished a turn you haven't viewed. It clears when you
view the associated terminal in the active IDE window, read the latest reply in the preview or
conversation viewer, or start another turn. Selecting a row with the preview hidden doesn't clear
it. A completion can include an error; unread doesn't mean the turn succeeded.

Tracking starts while Seshlog is running, so old history isn't marked unread on first discovery.
Completions can be missed when process or activity information is unavailable. A partial view
doesn't clear unread when Seshlog cannot verify the latest reply.

Project rows show working and unread counts for the current filters. **Next Session Needing
Attention** jumps to the next unread session. An optional **Seshlog attention** status-bar widget
shows counts and opens the next session needing attention.

**Copy Last Assistant Message** works with all four agents. **Copy Latest Plan** supports explicit
Claude Code and Codex plans. A Claude plan stored as a file reference uses that file's current
contents; see the [format notes](clipboard-format-evidence.md).

Assign shortcuts to the **Seshlog:** copy and attention actions in **Settings → Keymap**. Copy
actions work from the session list, conversation viewer or an associated terminal. After an agent
exits, copying from its terminal uses the last known session until another agent is identified.

**Files Changed** shows recognised operations in the available transcript. Complete Write calls
can be compared with the current file; edits and patches show the recorded operation. The list
can be partial and doesn't reconstruct historical file contents.

## Local data and privacy

Seshlog makes no network requests and has no telemetry or analytics. It reads agent data without
rewriting saved sessions. Settings and caches live in IDE storage.

Search saves words, positions and source references in the IDE config folder at
`seshlog/search/lexical.sqlite`. It keeps no raw transcript copies, snippets or query history on
disk. The index is unencrypted and can reveal conversation content. It survives IDE restarts and
Invalidate Caches, and is excluded from automatic Settings Sync and settings export.

All supported sources, including tool activity, are indexed within shared size limits.
**Search in** changes which sources can match and how matches are ranked and navigated; it doesn't
change what is collected or retained.

Manage the index in **Settings → Tools → Seshlog → Search**:

- **Refresh** checks for changes and reuses saved data.
- **Manage index…** contains Retry, Rebuild and Clear. Rebuild and Clear trigger local reindexing
  on the next search. If an upgrade asks for a rebuild, use this menu.
- **Storage and privacy** shows what is saved and lets you open the index folder or copy its path.

Import and Export are no longer offered. If saved storage is unavailable, search can use a
temporary local index and shows a coverage message. Another IDE using the same profile must close
its saved index before maintenance can proceed.

Local session diagnostics are enabled by default. Uncheck **Record session diagnostics** and Apply
to stop new recording. Existing logs remain available, and opening them doesn't turn recording
back on. A write already in progress may finish.

## Troubleshooting

If sessions don't appear, check the configured data folders and try **Refresh**. If launching fails,
check the executable and arguments in **Settings → Tools → Seshlog**. Custom Pi session folders
must be configured there too.

For a terminal that loses its session association:

1. Run **Find Action → Seshlog: Open Session Diagnostics** soon after the problem. It marks the
   incident when recording is enabled and reveals the running IDE's log folder.
2. Copy `sessions.jsonl` and its three rotated backups. Together they retain at most 20 MiB.
3. Note the incident time, agent version and whether it happened in your normal IDE or a sandbox.
   Each IDE has separate logs.

These logs stay on this computer. Paths and session IDs are hashed; conversation text, titles,
raw commands and environment variables are excluded. Review logs before sharing them.
If the diagnostics action is unavailable, **Help → Show Log in Finder/Explorer** opens the IDE's
log folder; look for its `seshlog` subfolder.
