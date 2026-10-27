# Quick session/worktree switcher

Implemented scope, 2026-10-27: metadata navigation across the shared SessionIndex, independent of
tool-window project/provider/date/hidden-view filters. Locally hidden sessions are always excluded.
The Find Action/Keymap entry is **Seshlog: Switch Session / Worktree** (`Seshlog.SwitchSession`).
The default shortcut is **⌘⌥S** on macOS and **Ctrl+Alt+Shift+P** on Windows/Linux; users can change it
in Keymap settings. It requires an open project. No provider parsers or search persistence changed.

## Behavior and implementation

- Empty query sorts by last session activity, with deterministic agent/id ties. All whitespace-separated
  words must appear in local/provider titles, project names, cwd/checkout paths or recorded/observed
  branches, ignoring case. This is literal metadata search, without BM25, transcript reads or HTTP.
  For non-empty queries, title word prefixes and title substrings rank ahead of project/branch matches,
  which rank ahead of path-only matches. For queries spanning fields, the weakest word match then overall
  match quality determine the order. Ordered title phrases are preferred within equal match quality;
  recency breaks ties. The query limit is 4,096 characters. Filtering precedes the 200-row display limit.
- `SessionSwitcherPanel` captures immutable metadata and app-wide hidden/unread state. It subscribes
  to index/organisation/attention events and checks cached terminal ownership/open projects every
  1.5 seconds while open. Filtering uses one bounded worker with 100 ms debounce. Popup generations
  reject older query/metadata results, cancellation stops superseded filtering, and disposal removes
  subscriptions, timers and queued work. Separate popups have separate lifetimes.
- `SwitcherResults` preserves surviving match order during refresh, appending new matches. At the
  200-row limit, current ranking determines membership while the selected matching row is protected;
  higher-ranked arrivals evict lower-priority unselected rows. Selection
  uses provider/id, not row index. Removing the selected row clears selection and disables actions.
  A changed query selects its first result; same-query refresh preserves the user's selection. Reopening
  with an empty query uses fresh recency order. The viewport is retained
  on refresh. Pins and unread do not change ranking; there is no separate navigation-history store.
- Rows show literal titles, agent, project/checkout path, branch, activity, unread and availability.
  Activity/unread stay beside the title so long paths do not push them out of view. Accessible names
  include the full row and action state. Selecting/filtering never acknowledges a completion; existing
  terminal visibility/receipt handling still owns acknowledgement.
  Pi/opencode rows use the index's already-resolved checkout snapshot for branch/path search and
  presentation, without inventing agent location provenance or enabling worktree opening from it.
  Nested non-Git sessions include the most specific containing open project's names and aliases;
  known checkouts still require an exact checkout-root match for project names.
  Explicit continuation links are collapsed before hidden filtering, using the main list's policy;
  independently live/associated predecessors remain visible and missing links/cycles stay usable.
- Enter focuses an associated terminal, including another open project's window. It captures the
  terminal's registry generation and shell, verifies process identity on a worker using existing
  inspection, then checks the session source/location/checkout and tab again before focusing. The
  terminal activation callback checks generation again, rejecting same-tab/same-id reuse.
  Updated location source/record/timestamp alone is not a new target; directory, checkout/source-file
  identity, evidence presence and uncertainty still guard navigation.
- Enter on a stopped row focuses **Resume**, without launching. Resume is also reachable by Tab or
  its platform button mnemonic. The explicit action rescans on a worker, validates the recorded
  directory and Git identity, and rechecks live/owned/hidden state before launching. Linked worktrees
  reuse `WorktreeOpening`, including protected-root checks, trust/import, window coalescing and
  validation after opening. The existing fork flow shares this launch validation; its diagnostics
  retain their original event names. Resume adds `worktree_resume_launched`.
  `requireFreshScan` requires a completed, published scan with READY health for the selected provider,
  including the rescan after opening. Timeout, failure, discarded results and unavailable providers
  block launch. Claude live-marker directory enumeration and individual marker inspection failures
  also mark provider health as ERROR, preserving readable history while preventing Resume.
  A genuinely absent optional marker directory
  remains valid; a non-directory or dangling symlink does not. Busy worktree requests explicitly
  return rejection and leave the popup open for
  retry; distinct resumes are never silently treated as the same request. Opening remains serialized
  per checkout, and rejected requests are not automatically queued.
- Non-linked directories use the most specific containing project directory, requiring the same
  checkout root when Git identity is known, and falling back to the invoking project's
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

Original implementation (`df82fa0`): `make check` passed, followed by `make release`: **552 tests,
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

## Review hardening, 2026-10-27

The seven review findings are addressed in one follow-up item, **Harden switcher validation and
metadata navigation**. Permanent synthetic coverage adds failed/timed-out/discarded scans and
provider health, pre/post-open refresh failures, nested project selection and checkout boundaries,
benign location evidence updates, Pi/opencode branch metadata, explicit busy-request rejection and
retry, continuation hiding/live predecessors/broken links/cycles, and full-list arrival/selection.
The initial run caught stale incremental UI-test bytecode after the row constructor changed; tests
were recompiled with `./gradlew compileTestKotlin --offline -Pkotlin.incremental=false --rerun`.
Final full-suite, package and verifier results follow; earlier results above are historical.

`make check` passed **564 tests, zero failures/errors, 10 opt-in skips**: 12 new regressions beyond
the original 552-test suite. `make release` passed, as did ZIP integrity and nested-JAR checks for
fixtures, test classes, transcripts, databases and models. The development version remains 0.8.1;
the updated ZIP is **13,280,399 bytes**, SHA-256
`f6f94d055077e30d26cf9561695c19c10a0b19e8242523d4a480284260c43ed9`.

`./gradlew verifyPlugin --offline` passed with Verifier 1.411 on IU-262.10968.63 and IU-263.7307.44.
Both verdicts are **Compatible**. Warning counts are unchanged from the table above: 2 scheduled
for removal each, 16/19 deprecated and 65/58 experimental usages. The optional IDE layout warnings
remain; the clean Kotlin compilation also reports existing deprecations, unnecessary null assertions
and the `WatchRootRegistration` generated-copy visibility warning. `git diff --check` passed.

A successful scan is not an atomic lock against an independent external CLI starting afterwards;
the fix removes the known failure path that accepted stale state after an unsuccessful rescan.
Physical UI/platform acceptance is still owed. No daily IDE installation or publication occurred.

## Edge-case follow-up, 2026-10-27

**Reject incomplete Claude liveness and match nested project names** addresses the two remaining
review findings. The provider propagates live-marker enumeration failure to the existing fresh-scan
gate; the reader no longer treats access errors as a successful empty scan. Project-name matching
uses already-canonical metadata, with no filesystem work added to popup capture or rendering.

Permanent synthetic regressions cover denied enumeration, iteration failure, absent/empty marker
directories, a dangling directory symlink, provider health and Resume eligibility after enumeration
failure/recovery, custom names/aliases on nested non-Git sessions, path-prefix collisions and known
checkout boundaries. The provider and project-name regressions both failed before the implementation
change. Individual malformed marker parsing remains tolerant, as before; this change specifically
closes failed directory enumeration. External CLI startup is still not atomically locked by a scan.

`make check` and `make release` passed **568 tests, zero failures/errors, 10 opt-in skips** (four new
tests). ZIP integrity and nested-JAR content checks passed with no test/fixture/transcript/database/
model leakage. The 0.8.1 ZIP is **13,283,298 bytes**, SHA-256
`5324a9674855f1abc3b1401e72321c995c4114f82e3e69ad8077b783ed9595d7`.

`./gradlew verifyPlugin --offline` passed with Verifier 1.411: **Compatible** on IU-262.10968.63 and
IU-263.7307.44. Warning counts remain 2 scheduled for removal each, 16/19 deprecated and 65/58
experimental usages; the optional IDE layout warnings remain. `git diff --check` passed.
Physical UI acceptance remains outstanding, along with all existing search acceptance checks;
the performance follow-up stays closed. No daily-IDE installation or publication was performed.

## Individual marker read failures, 2026-10-27

**Reject incomplete individual Claude marker reads** extends the incomplete-liveness safeguard to
individual files. Initial reads, identity re-reads and metadata inspection report failures through
the same provider-health gate. Successfully read history and other live markers remain available;
Resume is blocked until a healthy refresh. Missing files during a scan are ordinary marker removal,
while dangling symlinks remain incomplete evidence. Malformed JSON retains the existing tolerant
parsing behavior. The public parse/identity helpers still return null/false on unreadable markers.

Permanent synthetic tests cover denied initial and identity reads, preservation of another live
marker, provider-to-action rejection and recovery, ordinary removal and a broken marker symlink.
The provider regression failed against the previous implementation before this change. No agent-owned
data was modified. A completed scan still cannot lock out a later independent external CLI startup.

`make check` and `make release` passed **571 tests, zero failures/errors, 10 opt-in skips** (three
new tests). ZIP integrity and nested-JAR content checks passed without test/fixture/transcript/
database/model leakage. The 0.8.1 ZIP is **13,284,417 bytes**, SHA-256
`0b3822dff173a2dcf44d16b9d2de7c28c84a6790ca79aaa2a7724b06c020245c`.

`./gradlew verifyPlugin --offline` passed with Verifier 1.411: **Compatible** on IU-262.10968.63 and
IU-263.7307.44. Warning counts remain 2 scheduled for removal each, 16/19 deprecated and 65/58
experimental usages; optional IDE layout warnings remain. `git diff --check` passed.
Physical UI and search acceptance remain outstanding, and the search performance follow-up remains
closed. No daily-IDE installation or publication was performed.

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
