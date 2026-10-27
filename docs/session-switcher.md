# Quick session/worktree switcher

Implemented scope, 2026-10-27: metadata navigation across the shared SessionIndex, independent of
tool-window project/provider/date/hidden-view filters. Locally hidden sessions are always excluded.
The Find Action/Keymap entry is **Seshlog: Switch Session / Worktree** (`Seshlog.SwitchSession`), with
no default shortcut. It requires an open project. No provider parsers or search persistence changed.

## Behavior and implementation

- Empty query sorts by last session activity, with deterministic agent/id ties. All whitespace-separated
  words must appear in local/provider titles, project names, cwd/checkout paths or recorded/observed
  branches, ignoring case. This is literal metadata search, without BM25, transcript reads or HTTP.
  The query limit is 4,096 characters. Filtering precedes the 200-row display limit.
- `SessionSwitcherPanel` captures immutable metadata and app-wide hidden/unread state. It subscribes
  to index/organisation/attention events and checks cached terminal ownership/open projects every
  1.5 seconds while open. Filtering uses one bounded worker with 100 ms debounce. Popup generations
  reject older query/metadata results, cancellation stops superseded filtering, and disposal removes
  subscriptions, timers and queued work. Separate popups have separate lifetimes.
- `SwitcherResults` preserves existing match order during refresh, appending new matches. Selection
  uses provider/id, not row index. Removing the selected row clears selection and disables actions.
  A new query can choose the first result. Reopening uses fresh recency order. The viewport is retained
  on refresh. Pins and unread do not change ranking; there is no separate navigation-history store.
- Rows show literal titles, agent, project/checkout path, branch, activity, unread and availability.
  Activity/unread stay beside the title so long paths do not push them out of view. Accessible names
  include the full row and action state. Selecting/filtering never acknowledges a completion; existing
  terminal visibility/receipt handling still owns acknowledgement.
- Enter focuses an associated terminal, including another open project's window. It captures the
  terminal's registry generation and shell, verifies process identity on a worker using existing
  inspection, then checks the session source/location/checkout and tab again before focusing. The
  terminal activation callback checks generation again, rejecting same-tab/same-id reuse.
- Enter on a stopped row focuses **Resume**, without launching. Resume is also reachable by Tab or
  its platform button mnemonic. The explicit action rescans on a worker, validates the recorded
  directory and Git identity, and rechecks live/owned/hidden state before launching. Linked worktrees
  reuse `WorktreeOpening`, including protected-root checks, trust/import, window coalescing and
  validation after opening. The existing fork flow shares this launch validation; its diagnostics
  retain their original event names. Resume adds `worktree_resume_launched`.
- Non-linked directories use an already-open matching root, falling back to the invoking project's
  terminal with the recorded cwd. **Open Worktree** remains limited to verified linked checkouts.
  Uncertain/missing checkouts disable launches; absent Git/location evidence is labelled unverified
  and directory availability is checked on explicit Resume. A missing checkout can still focus an
  existing verified terminal. A positively replaced Git checkout rejects the old target.
- External/ambiguous/uninspectable terminals are labelled or rejected, with no automatic resume
  fallback. Providers without machine-wide liveness evidence cannot identify every external agent;
  absence of a known association is labelled **no tracked terminal**, not proof of process exit.
  Resume is always explicit. The older tab registry is id-only: duplicate ids across providers are conservatively
  refused for navigation. Directory replacement/recovery selection remains in the tool-window flow;
  this popup never silently substitutes a saved replacement directory.

## Automated validation

`make check` passed, followed by `make release` on the final implementation: **552 tests,
zero failures/errors, 10 opt-in skips** (15 additional regression tests). Synthetic tests cover metadata fields and hidden filtering,
recency/limits/provider keys, stable selection, cold scans, stale delivery/disposal, session/source/
checkout replacement, tab generation reuse, stopped Enter, literal accessible rendering, preserved
filters and worktree launch guards after opening. Existing worktree cancellation, directory and
destination-close tests continue to cover the shared open/launch flow.

The development package retains version **0.8.1**, since-build **262**, JDK **25** and Kotlin API
**2.3**. ZIP integrity and nested plugin-JAR inspection passed: the registered switcher/classes are
present; no test classes, fixtures, JSONL transcripts, databases or model files were packaged.
Only the plugin, searchable-options and existing SQLite JDBC JARs are included.
`build/distributions/seshlog-0.8.1.zip`: **13,269,000 bytes**, SHA-256
`74d464469f050c8c01d118b6eff129484d3f61eae3ee0daa864ffb1d14f44da6`.
This is a local development artifact; no publication or daily-IDE installation was performed.

`./gradlew verifyPlugin --offline` passed with Plugin Verifier **1.411**:

| IDE build | Verdict | Scheduled for removal | Deprecated | Experimental |
| --- | --- | --- | --- | --- |
| IU-262.10968.63 | Compatible | 2 | 16 | 65 |
| IU-263.7307.44 | Compatible | 2 | 19 | 58 |

The two additional deprecated usages are `Disposer.isDisposed` in the navigation and delayed-focus
guards, consistent with the existing terminal registry checks. Scheduled-for-removal and experimental
counts are unchanged. The verifier also prints missing optional IDE layout-component warnings for
the downloaded IDE distributions; both plugin verdicts are Compatible. The Kotlin compile reports
the same deprecated `Disposer.isDisposed` API family. `git diff --check` passed.

Implementation commit subject: **Add metadata session and worktree switcher** (one plan item).

## Manual acceptance still owed

No physical UI checks have been claimed. Use a verified synthetic `make run` profile; record its
IDE/build, JDK, OS/architecture, config/system/log locations and loaded plugin. Never create examples
by editing real agent transcripts. Check:

- Find Action and a user-assigned shortcut from editor, terminal and tool windows, including dumb mode.
- Rapid typing/clearing while scans arrive, two windows, hidden/title edits, new/deleted results,
  keyboard selection and scrolling past the first page, closing during filtering/validation.
- Cross-window terminal focus in classic and reworked terminal engines, split panes, detached/closed
  tabs, an in-agent session switch and ambiguous external terminals. Confirm unread clears only on
  actual terminal viewing.
- Explicit Resume in existing and unopened linked worktrees, trust/import cancellation, source or
  destination window closure, and a session becoming live while a dialog is open.
- Missing, uncertain and replaced checkouts/subdirectories, protected agent roots, plain directories,
  main repositories and providers without location provenance.
- Narrow/resized popup, light/dark themes, keyboard-only button access and screen-reader names on
  supported macOS, Windows and Linux. Plugin Verifier does not establish these UI/OS results.

All previously outstanding search responsiveness/relevance/resource/platform checks remain in
PLAN.md. The closed search performance follow-up has not been reopened.
