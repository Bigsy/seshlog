# Current work plan

Updated 2026-10-29. This is the authoritative execution checklist.
Before editing, inspect `git status --short`, preserve user work, and re-find symbols rather than
trusting historical line numbers. Take the open items below in order unless the user directs otherwise.

## Current status and next work

| Area | Status | Evidence / next action |
| --- | --- | --- |
| Persistent SQLite FTS5 BM25 | Implemented and automatically tested; release acceptance open | [Production report](docs/production-bm25.md); finish responsiveness, relevance/resource and manual checks below. |
| Source filters and match provenance | Implemented and automatically tested; release acceptance open | [Source-filter report](docs/search-source-filters.md); all physical UI checks remain unverified. |
| External-server semantic search | Agreed, unimplemented | Start items 5–13 only after Phase 1 acceptance; [detailed design](docs/semantic-search-design.md). |
| Managed local inference | Deferred until separately requested after server-backed evaluation | [Later-phase scope](docs/semantic-search-design.md#deferred-managed-local-inference). |
| Quick session/worktree switcher | Implemented and automatically validated; physical UI checks open | [Implementation and validation](docs/session-switcher.md); item below. |
| Synthetic sandbox profile | Implemented; verified end to end on macOS | [Guide](docs/synthetic-sandbox.md): `make sandbox`, then `make sandbox-verify`, for every physical check. |
| Other feature ideas | Local backlog only | Ignored `PLAN-BACKLOG.md` in this checkout. |

### Worktree follow-up (user priority, 2026-10-29)

The switcher now follows project/sibling-worktree scope and sorts newest first; queries filter
rather than rank. Delete Worktree is implemented (2ff6b44), with optional hiding and safe branch
cleanup. Earlier switcher descriptions below are completion history, not the current contract.

- [x] Harden deletion: protect containing agent-data folders and shared Git metadata, refresh
  evidence again in the queued removal worker, reject changed checkout/branch/window/terminal/live
  session state, and require a completed scan even for empty session sets. Add failing regressions,
  run `make check`, and commit this focused fix. Completed: 612 tests, zero failures/errors,
  10 opt-in skips. Three new regressions failed before the fix and passed afterward.
- [x] Add **Review Worktree Changes…** on session and folder rows: choose a local base ref (HEAD
  initially), list checkout-wide tracked and untracked changes, and open native IntelliJ diffs.
  Include committed changes when comparing with an earlier base. Read Git/files on workers,
  bound content, reject replaced/missing checkouts and stale UI delivery, and never attribute
  checkout-wide edits to one session. No fetch, staging, checkout, or remote integration.
  `make check`: 615 tests, zero failures/errors, 10 opt-in skips; physical checks remain open.
- [ ] Run applicable physical acceptance on a newly verified synthetic sandbox; record actual
  results in `docs/worktree-follow-up.md`. Include deletion confirmation/cancellation, dirty/locked
  refusal, hide/keep sessions, review base selection/diffs, cross-window focus/fork, trust cancellation,
  keyboard navigation, and light/dark/narrow layouts. Unperformed checks stay open.
- [ ] Carry forward original worktree acceptance: Claude new/existing-window forks and independent
  sessions; recorded location transitions/exit/resume; detached HEAD and branch changes; symlink
  aliases; sibling filters; missing/replaced paths; repeated clicks and source-terminal survival.
  Codex open/fork checks were user-confirmed on 2026-10-25; this is not new acceptance evidence.

- [x] **Fork into New Worktree…** (approved 2026-10-29): choose a local starting ref, new branch
  and destination; create without force or copying dirty/ignored files; open through trust/import
  and fork in the mapped subdirectory. Preserve created worktrees on cancellation/failure and
  report partial success. Validate source/destination identity and protected storage before effects.
  `make check`: 619 tests, zero failures/errors, 10 opt-in skips. Claude/Codex supported;
  physical checks remain open. Other providers retain their existing same-directory forks.
- [ ] **Attention inbox** (approved 2026-10-29): show unread sessions across projects with bounded
  assistant reply previews, conversation/terminal/worktree navigation and receipt-safe acknowledgement.
  Keep previews in memory, use existing activity evidence, and reject stale delivery.

All three features are now approved for this pass. Outstanding Phase 1 search acceptance below remains scheduled; Phase 2 stays gated.

### 0. Quick session/worktree switcher (priority override, 2026-10-27)

User direction: implement this before the remaining search acceptance work. All unchecked search
acceptance rows below remain owed; the search performance follow-up stays closed.

- [x] Implement and validate one focused switcher item:
  - Register **Seshlog: Switch Session / Worktree** in Find Action/Keymap, without a default shortcut
    that could conflict with an existing keymap. Use a compact keyboard-accessible popup.
  - Search local/provider titles, project names, recorded paths and observed branches, across all
    indexed projects/providers, excluding locally hidden sessions. Empty query uses recent activity.
    Do not read conversations or mutate tool-window filters. Show agent, path, activity/unread and
    checkout availability from immutable snapshots.
  - Filter on a bounded worker with per-popup generations; reject stale delivery, retain selection
    by provider/id and retain existing order across refresh. Dispose work/listeners with the popup.
  - Enter focuses a validated associated terminal and its project. Stopped rows expose explicit
    Resume; linked worktrees reuse verified opening and revalidation after trust/import. Missing,
    uncertain and externally running sessions explain unavailable actions. Recheck session/source,
    tab generation/process evidence and checkout identity before delayed effects.
  - Add pure race/policy tests and platform UI/action tests. Run `make check`, Plugin Verifier and
    package checks; document actual results and remaining manual checks in
    `docs/session-switcher.md`. Commit this item once.
  Completed 2026-10-27 in the focused commit **Add metadata session and worktree switcher**:
  `make check` / `make release` passed **552 tests, zero failures/errors, 10 opt-in skips**.
  Offline Plugin Verifier reports compatibility on IU-262.10968.63 and IU-263.7307.44.
  ZIP checks, artifact hash, warning counts and limitations are in the implementation report.
- [ ] Manual switcher acceptance in a verified synthetic profile (`make sandbox`, `make sandbox-verify`): shortcut/Find Action,
  rapid typing and refreshing, keyboard selection/scroll, cross-window focus with both terminal
  engines, explicit Resume (including unopened worktree/trust cancellation), hidden sessions,
  missing/replaced/uncertain paths, unread acknowledgement, accessibility and light/dark/narrow UI.
  Record IDE/build and platform; automated tests and Plugin Verifier do not pass these checks.
  The user confirmed the shortcut opens the popup in the macOS synthetic sandbox; the remaining
  checks, including button navigation after the follow-up below, remain open.

- [x] Switcher review hardening (one focused follow-up commit): fail closed on unsuccessful or
  unhealthy action rescans, including after worktree opening; resolve nested project destinations
  without crossing checkout boundaries; tolerate location evidence timestamps/record updates;
  use existing resolved Git metadata for every provider's branch search; report busy worktree
  requests and retain the popup for retry; collapse continuations before hiding/filtering; admit
  recent arrivals at the 200-row limit while retaining selection and surviving relative order.
  Add permanent regressions for all seven findings, run `make check`, package/compatibility checks,
  and update `docs/session-switcher.md`. Existing physical/search checks remain open.
  Completed 2026-10-27 in **Harden switcher validation and metadata navigation**: 564 tests,
  zero failures/errors, 10 opt-in skips; `make release` and offline Plugin Verifier passed on
  IU-262.10968.63 / IU-263.7307.44 with unchanged warning counts. Report records artifact hash
  and the explicit retry behavior for busy worktrees; no physical acceptance rows were passed.

- [x] Switcher edge-case follow-up (one focused commit): distinguish absent Claude live-marker
  directories from failed enumeration and propagate incomplete liveness evidence to provider health,
  so action rescans cannot authorize Resume. Attach the most specific containing project's names
  to nested non-Git sessions without crossing known checkout boundaries. Add permanent synthetic
  regressions, run `make check` and package/compatibility checks, and record the results in
  `docs/session-switcher.md`. Physical switcher/search acceptance remains outstanding.
  Completed 2026-10-27 in **Reject incomplete Claude liveness and match nested project names**:
  `make check` / `make release` passed 568 tests, zero failures/errors, 10 opt-in skips; offline
  Plugin Verifier reports Compatible on IU-262.10968.63 / IU-263.7307.44 with unchanged warnings.
  ZIP integrity/content inspection passed; the report records the artifact hash and remaining limits.

- [x] Individual Claude marker read failures (one focused commit): propagate file-read failures,
  including the identity re-read, as incomplete provider health so Resume fails closed. Preserve
  tolerant malformed-data handling and readable history/markers. Add permanent synthetic regressions
  for first-read and revalidation failures, recovery and provider-to-action eligibility; run
  `make check`, package and compatibility checks and update `docs/session-switcher.md`.
  Physical switcher/search acceptance stays open; the performance follow-up stays closed.
  Completed 2026-10-27 in **Reject incomplete individual Claude marker reads**: `make check` and
  `make release` passed 571 tests, zero failures/errors, 10 opt-in skips. Offline Plugin Verifier
  reports Compatible on IU-262.10968.63 / IU-263.7307.44 with unchanged warnings; ZIP integrity
  and nested-JAR content checks passed. Artifact hash and limits are recorded in the report.

- [x] Improve switcher search ranking and add default shortcuts: prefer title word prefixes and
  substrings over project/branch and path matches, use recency to break ties, and select the first
  result when the query changes while preserving selection during same-query refresh. Register
  ⌘⌥S on macOS and Ctrl+Alt+Shift+P on Windows/Linux; keep them configurable in Keymap settings.
  Cover relevance, cross-field matching, selection and shortcut registration with synthetic tests.
  Run `make check`; manual shortcut and switcher acceptance in the synthetic sandbox remains owed.
  Completed 2026-10-27: the full offline test suite passed 581 tests, zero failures/errors and 10
  opt-in skips. Plugin Verifier reports Compatible on IU-262.10968.63 / IU-263.7307.44 with
  unchanged API warning counts. The previously running sandbox needs restarting to load these changes.

- [x] Make switcher action buttons keyboard accessible when macOS Tab traversal skips buttons:
  allow Left/Right and Shift+Tab/Tab between enabled Resume and Open Worktree buttons, and return
  to platform traversal at the ends. Cover focus routing, disabled buttons and accidental launches;
  the full offline test suite passes (582 tests, 10 opt-in skips). Physical keyboard acceptance in
  the synthetic sandbox remains owed after restarting it to load the fix.

- [x] Improve quick-switcher result readability: distinguish agents, branches and paths with
  theme-aware colours, bold literal case-insensitive query words in visible titles and metadata,
  and retain accessible full text and readable selection colours. Use native text fragments;
  inspected synthetic light/dark palette renders at 760 and 420 pixels. The full offline test
  suite passed 584 tests, zero failures/errors, 10 opt-in skips; native-fragment tests cover
  matching across fields, overlapping words, literal markup, Unicode offsets, renderer reuse
  and selection contrast. The temporary preview harness was removed. Physical theme/accessibility
  acceptance remains open; restart the synthetic sandbox to load the new renderer.

- [x] Mark the selected switcher row with a theme-aware outline instead of a filled background,
  preserving field colours and bold matches with identical text insets. Update the renderer
  assertions; synthetic light/dark palette renders inspected at 760 and 420 pixels. The full
  offline test suite passed 584 tests, zero failures/errors, 10 opt-in skips. The temporary preview
  harness was removed; physical theme/accessibility acceptance remains open after sandbox restart.

### Switcher Marketplace card (user priority, 2026-10-27)

- [x] Add a fifth Marketplace card for the session/worktree switcher and show it in the README.
  Preserve the existing design and first three UI captures; refresh the search card's UI for
  source selection/provenance and update pagination. Extend the opt-in synthetic renderer and
  rebuild instructions. The opt-in capture and full offline test suite passed (584 tests,
  zero failures/errors, 10 opt-in skips). All five 1920×1200 PNGs and README media links validated;
  full-size and thumbnail renders inspected. Pixel comparison confines changes in cards 1–3 to
  the footer counts. No Marketplace upload was performed.

### README cleanup (user priority, 2026-10-27)

- [x] Rewrite the README as a short user-facing introduction with install steps, everyday
  workflows, shortcuts, worktrees and plain-language privacy. Keep the screenshot gallery and
  move detailed instructions, limitations and diagnostics into a linked usage guide.
  Instructions checked against current UI labels, keymap and click handlers. All 18 relative
  links/anchors validated and the screenshot gallery verified unchanged. README is about 78%
  shorter; detailed user instructions now live in `docs/usage.md`. Documentation-only change;
  no runtime tests needed.

### Search settings layout cleanup (user priority, 2026-10-27)

- [x] Simplify the Search settings section: a concise status/empty state, one refresh action,
  maintenance actions in a menu, expandable storage/privacy details and wrapped action
  feedback. Remove Import/Export from settings per user direction; retain local-storage disclosures
  and existing maintenance semantics. Inspect synthetic renders,
  run `make check` and compatibility checks, and record remaining physical UI checks.
  This does not reopen the closed performance follow-up or complete search/switcher acceptance.
  Completed in **Simplify local search settings**: `make check` passed 571 tests, zero failures/errors,
  10 opt-in skips. Inspected synthetic wide and 420-pixel narrow renders, including expanded details
  and wrapped completion feedback. No new behavioral tests for this presentation change; the temporary
  renderer was removed. Physical settings/menu/keyboard/theme checks remain owed. Compatibility and
  packaging will also be checked with the following diagnostics preference change.

### Optional local session diagnostics (user priority, 2026-10-27)

- [x] Add an app-wide, persisted **Record session diagnostics** checkbox, enabled by default for
  existing installations. Applying it off stops new event recording and suppresses queued writes
  that have not started; already-written logs remain available. Opening logs while disabled must
  not restart logging or create a file. Make local-only/no-upload behavior explicit in settings
  and documentation. Cover preference persistence, disabled writes, queued work and re-enabling;
  run `make check`, package and compatibility checks. Preserve outstanding physical acceptance.
  Completed in **Make local session diagnostics optional**: `make check` / `make release` passed
  575 tests, zero failures/errors, 10 opt-in skips. Offline Plugin Verifier reports Compatible on
  IU-262.10968.63 / IU-263.7307.44 with unchanged warnings; ZIP/content checks passed. This also
  validates the preceding Search settings cleanup. [Report and manual checks](docs/settings-cleanup.md).

### Synthetic sandbox profile (user priority, 2026-10-28)

- [x] Make the verified synthetic profile that every physical check requires repeatable. `make sandbox`
  generates fictional Git checkouts/worktrees and histories for all four agents (28 scenario and 220
  filler sessions), seeds Seshlog data-folder and stub-executable settings, then runs a dedicated
  `runSyntheticIde` with its own config/system/log/plugins directories. Node.js stub agents stand in
  for the real CLIs and record working/waiting turns, titles and forks in each agent's format.
  `make sandbox-verify` checks the running IDE's directories, settings, loaded jar and that every
  `provider_scanned` record hashed synthetic storage. Root safety refuses Git checkouts, home/agent
  directories and unknown non-empty directories; reset deletes only a marked sandbox.
  Completed 2026-10-28: `make check` / `make release` passed 593 tests, zero failures/errors and 10
  opt-in skips; 9 new synthetic tests cover provider scans, Git layout, every documented example
  query against the production index, settings/stubs, reuse, root safety, stub round trips and the
  verifier. No sandbox class or resource is in the plugin ZIP. A real `runSyntheticIde` on macOS
  (IU-262.10968.63, JBR 25.0.4, aarch64) reported **VERIFIED**: synthetic storage for all four
  providers at the generated counts and the jar matching `build/libs`. No physical check was
  attempted or passed; stub launches are POSIX-only, so Windows Resume checks need another setup.

### Performance decision: small wins only

User direction, 2026-10-27: take small, measured optimizations, then drop the broader performance
item. Conversation (user + assistant, tools off) is the normal workload. The historical **warm p95
<100 ms at 10,000 sessions** target remains a comparison point, not a release or next-feature blocker.
This is a prioritization decision, not a claim that the target passed. Keep actual UI responsiveness,
cancellation, coverage and resource behavior in the manual checks below; reopen profiling if those
checks reveal a practical problem. Do not change exact/phrase/source semantics to improve timings.

- [x] Validate the small literal-verification change: fold each eligible field once per call and
  find only the first range needed for evidence; retain original Unicode offsets and source rules.
  Run the unchanged synthetic scale/long-tool benchmark and `make check`, record results in the
  [source-filter report](docs/search-source-filters.md), then close this follow-up without a larger
  SQL/index redesign or a requirement to force every benchmark below 100 ms.

Completed 2026-10-27: `make check` passed (537 tests, 0 failures/errors, 10 opt-in skips), as did
the unchanged synthetic scale/long-tool benchmark and focused search evaluation. At 10k, default
median query time changed from 85.97–88.30 ms to 60.35–61.79 ms; p95 remains 103.06–106.51 ms.
The long-tool All activity p95 changed from 36.47 ms to 10.14 ms. All warm source-read/tokenization
and unchanged reopen/import reuse assertions passed. [Full comparison and limitations](docs/search-source-filters.md#small-literal-verification-optimization-2026-10-27).
This follow-up is closed; no larger performance project is scheduled.

### 1. Complete Phase 1 acceptance

- [ ] Finish both physical checklists below in a verified synthetic sandbox profile (`make sandbox`,
  `make sandbox-verify`; [guide](docs/synthetic-sandbox.md)). Record the verification report, date
  and result. No row is passed by this consolidation.
- [ ] Validate supported macOS/Windows/Linux behavior; Plugin Verifier compatibility alone does not
  establish operating-system or UI acceptance.
- [ ] Profile allocation/native SQLite scratch and long-history/tool stress on the reference machine
  and at least one ordinary lower-memory supported machine. Prove the resource budgets below;
  whole-test-JVM heap/RSS samples do not isolate search memory.
- [ ] Broaden synthetic relevance realism and manually review labels/ambiguous cases. The repeated
  200-session scaffold's perfect scores are contract evidence, not representative history quality.
  Preserve frozen historical fixtures/reports; split by scenario family, label before tuning and
  record evaluation iterations. New fixtures must be synthetic, not edited private transcripts.
- [ ] Meet the relevance gates: all technical/phrase acceptance tests pass; no aggregate MRR@5 or
  recall@5 regression against the preserved literal baseline; clear natural-language improvement;
  at least 90% technical hit@1 and 90% positive recall@5. Review every lost technical top-one case.
  Report hit@1, recall@5, MRR@5, nDCG@10 where applicable, negatives and coverage by category.
- [x] After changes, run `make check`, Plugin Verifier and `make release`; inspect the ZIP for test,
  fixture, transcript, database, model or helper leakage. Record actual warnings and artifact hash.
  A verified artifact does not authorize publication or installation into the daily IDE.
  Completed for the 0.8.1 build on 2026-10-27; [validation record](docs/search-source-filters.md#081-build-and-compatibility-validation-2026-10-27).
- [ ] Mark Phase 1 release-accepted only after the responsiveness/relevance/resource/platform checks
  and applicable physical checks pass. This closes the former PLAN.md items 3–4 and source-filter
  acceptance; Phase 2 is independent and is not required to ship BM25.

Earlier sandbox attempts did not establish a verified synthetic profile: one failed on the wrong
JDK, the JDK-25 retry used repository-default paths, and UI attachment failed. `make run` reads real
agent data, so use `make sandbox` and record a passing `make sandbox-verify` report before testing;
its example searches are asserted by `SyntheticSandboxTest`. Do not alter agent data to create examples.

### Phase 1 physical checks: persistent BM25

- [ ] Clean/default profile: search works without model files, native binaries or network.
- [ ] Natural-language query finds a session despite one or more missing ordinary query words.
- [ ] Quoted and mixed quoted/unquoted queries obey the search contract below; no cross-message phrase matches.
- [ ] Exact symbol, filename, error code, path and Unicode queries navigate/highlight correctly.
- [ ] Title override, metadata-only session, tool-only result and large/partial history work honestly.
- [ ] Project/date/provider/hidden/worktree filters and pinned/grouped rows retain expected behavior.
- [ ] Rapid typing, clearing, filter changes, file updates and two windows never deliver stale results.
- [ ] Keyboard navigation, selection and scroll survive refresh; preview/full viewer opens the hit.
- [ ] Empty results distinguish complete no-match from partial/error coverage.
- [ ] No EDT stalls or excessive memory/repeated extraction at reference scale.
- [ ] Restart/plugin upgrade/Invalidate Caches reuse the durable lexical index; unchanged sources are not re-tokenized.
- [ ] Append/rewrite/delete/title changes update the correct SQLite rows without stale hits.
- [ ] Lexical cache status/refresh/rebuild/clear/export/import work with semantics disabled.
- [ ] Export/import into a fresh profile with moved roots reuses validated rows; absent sources remain unresolved.
- [ ] Locked/full/corrupt/newer-schema lexical DB degrades safely with truthful coverage and no silent deletion.
- [ ] Multiple windows/processes, cancellation, migrations and snapshot writes remain consistent.
- [ ] Lexical terms/positions are disclosed as sensitive local data; no raw transcript copies/query history or silent sync.

### Phase 1 physical checks: source controls and navigation

- [ ] Fresh/old-settings profile shows Conversation: user and assistant on, tools off.
- [ ] All eight source combinations work; Titles & paths is explicit, valid and remembered.
- [ ] Title override, provider title and path matches have truthful labels and excerpts.
- [ ] User/assistant/tool-call/tool-result labels match the opened source; Claude subagent results
  appear only with Tool activity enabled, and Pi does not imply missing tool support is an error.
- [ ] Rapid typing/toggling/clearing never displays a stale-source hit or snippet.
- [ ] Two windows synchronize the preference and keep independent query text/search lifecycle.
- [ ] Selection, pins, project grouping and scroll remain usable after a toggle.
- [ ] Keyboard menu use, accessible labels, dark/light themes and narrow layouts work.
- [ ] Exact/quoted queries obey selected sources; excluded tools cannot rescue a required phrase.
- [ ] Preview and full conversation open the displayed match. Next/previous navigation excludes
  disabled sources while normal Find and full displayed context remain available.
- [ ] Opening outside search, copying and completion/unread behaviour remain correct.
- [ ] Older index produces a clear rebuild status and correct temporary search; rebuild/restart
  and new-format export/import retain classifications and saved preference behaves independently.
- [ ] Partial/no-match/index-error states are distinguishable; tool exclusion alone is not partial.
- [ ] Search/toggling remains responsive at the tested scale; no EDT I/O and no source-filter
  setting change causes a full provider scan or unchanged-source rebuild.

## Search contracts to preserve

These are implemented behavior and accepted constraints, not new implementation tasks. See
[README](README.md), the [production report](docs/production-bm25.md), the
[source-filter report](docs/search-source-filters.md) and regression tests for evidence.

### Query, source selection and ranking

- Natural-language terms are relevance signals; matching every unquoted word is not required.
  Quoted phrases are mandatory case-insensitive literal substrings within one eligible field/entry;
  multiple phrases may match different entries, but none spans entries. In `"database locked" timeout`,
  the phrase is required and `timeout` boosts relevance. Preserve unmatched-quote/parser behavior.
- Preserve technical full-query evidence, punctuation, Unicode offsets, filenames/paths, C++/C#,
  error codes and identifier substrings. Do not use token phrases as proof of literal phrases.
  Split forms may aid discovery without multiplying equivalent contributions; no blind stemming.
  Stopwords never remove phrase/technical/exact evidence; all-stopword queries retain literal fallback.
- Keep the two-character minimum, 300 ms debounce and 4,096 UTF-16-character / 128 compiled-term
  limits. Reject overlong queries clearly rather than dropping terms. No hidden FTS/operator syntax;
  compile parameterized MATCH expressions, and keep scores finite and out of the user-facing UI.
- `SearchSources.DEFAULT` is Conversation: user and assistant on, tools off. All eight combinations
  are valid; all-off is Titles & paths. Provider/local titles and paths always remain eligible.
  Apply selection to candidates, population statistics, scores, exact/phrase/substring fallback,
  snippets and navigation before the final top-100 presentation limit.
  Keep session-scope filters before truncation, and never impose an early candidate cap that
  loses valid exact/phrase hits to improve latency.
- Classify by entry kind before role: coverage never matches; tool calls/results always belong to
  Tool activity, including USER-role results and Claude subagent dialogue. Pi's dialogue-only reader
  stays unchanged. Source eligibility must not change copy/completion/changed-file behavior.
- All supported lexical sources remain indexed within shared retention limits. Toggling sources
  neither rescans providers nor re-tokenizes unchanged sources. Preferences live app-wide in
  `seshlog.xml`, survive unrelated Settings Apply and all-false serialization, and synchronize
  windows through the dedicated event; imports/exports never carry the preference.
- Schema 3, classification/normalization 1: separate title/path/user/assistant/tool FTS populations.
  SQL uses max(provider/local title), path and max enabled body passage, weighted **8/3/1**.
  Disabled text cannot affect active BM25 statistics or scores; echoed body categories do not sum.
  Keep MATCH/BM25 cursors materialized and scoring in SQLite, with no duplicate RAM postings index.
- Genuine protected full-query evidence precedes ordinary scores. Preserve score/activity/provider/
  session ties; primary-evidence ties use Title, Project path, You, Assistant, Tool call, Tool result,
  then stable entry/range. Pins/grouping remain explicit presentation overrides.
- The displayed label, excerpt, source category, entry/range/hash or metadata variant must agree.
  Prefer protected full-query evidence when selecting the explanation, otherwise the largest
  weighted contribution. A partial metadata winner cannot suppress protected body verification.
  A displayed snippet need not contain every mandatory phrase; never imply otherwise.

### Delivery, coverage and navigation

- Use immutable captured query/source options and provider-aware session keys. Include options,
  query, candidates, corpus/content/settings revisions and per-caller generation in reuse/delivery
  guards. A source change invalidates old work immediately and reruns each window independently;
  typing/clearing/closing one window must not cancel another. Clear stale hits while searching.
- No source reads, SQL, hashing or process work on EDT/render paths. Render immutable snapshots;
  deliver on EDT after rechecking identity. Dispose subscriptions and queued callbacks normally.
- Metadata-only evidence survives failed body reads, without claiming the body was indexed/validated.
  Intentional category exclusion is not partial coverage; real shared-retention loss still is.
  Keep complete no-match, partial coverage and index failure distinguishable even with zero hits.
- Validate original entry ID, category, UTF-16 range, hash and revision before opening a body hit;
  changed evidence gets refresh/unavailable, never an unrelated jump. Expand eligible collapsed tools.
  Metadata-primary hits explain the matched title/path and have no fabricated conversation target.
- Preview follows current context; opened full editors retain their captured query/options.
  Seshlog highlights/counts/F3 use eligible original-entry ranges mapped into displayed text,
  excluding headings/dates/tool labels/coverage. Next/previous starts from the opened passage,
  even without a literal navigation match. Full context and ordinary IDE Find remain available;
  copy, read-only editors, latest-reply positioning and unread/completion behavior remain intact.

### Persistence, failure isolation and bounds

- Store only contentless lexical terms/positions/frequencies/lengths, versions and source references
  durably under `${IDE_CONFIG}/seshlog/search/lexical.sqlite`. No raw transcript copies, snippets or
  query history on disk. Terms/order expose sensitive content; exports are not anonymized or encrypted.
  No automatic Settings Sync/upload, project/agent-root DBs or transient-cache-only persistence.
- Reuse unchanged compatible rows after restart/plugin upgrade/Invalidate Caches. Metadata changes
  do not re-extract bodies. Atomically replace changed source postings/associations/category moves;
  missing roots are not mass-deletion evidence. Eviction releases buffers, not durable postings.
- Preserve schema-2 and future/unknown databases. Known v2 requires explicit local Rebuild with
  recovery under the process lease; temporary search must still obey current source filters.
  Never infer roles from old postings, silently downgrade/reset a store or erase recoverable data.
- One bounded writer with short transactions, busy/cancellation limits and coordinated cross-process
  maintenance; no source reads or HTTP inside writes. Close statements/connections on unload.
  Lexical DB failures preserve usable data or use bounded temporary SQLite/literal fallback with
  truthful status. Refresh/rebuild/clear/status/export/import must work without semantics.
- Export consistent private snapshots to explicit destinations; import is untrusted, read-only
  validation followed by bounded transactional merge preserving existing good sources. Validate
  exact schema/versions/field-category associations/integrity/bounds; never execute imported SQL,
  triggers/views/extensions or follow imported paths. No credentials or authorization in exports.
  Unknown/old snapshots require rebuild/re-export in the original IDE, without changing the active DB.
- Rebind discovered local provider IDs/relative locators and validate stamps/fingerprints, including
  moved roots; absent sources remain unresolved. Imports do not reconstruct original conversations.
  Hashing is bounded/cancellable (16 MB across at most 2,000 files); unverifiable imports need local
  extraction. Adapt dense import batches to token/character bounds rather than rejecting valid exports.
  Preserve the 256 passages × 350 tokens round-trip/reopen/idempotence regression for every category.
- Preserve the lifetime process lease and safe temporary fallback for competing IDEs; DELETE/FULL
  journaling, in-memory scratch and bounded SQL progress callbacks remain current implementation.
  Lexical working memory target: **128 MiB**, including source/snippet/results/metadata/SQLite caches;
  start with an **8 MiB** page cache and account for native scratch/connection count.
  Persistent policy: **256 MiB**, **100,000 shared body passages**, **50,000 sources**. Include recovery,
  journal and maintenance headroom; the current main-file ceiling starts at **112 MiB**, reduced by
  recovery files. Rebuild preserves one recovery copy; Clear removes owned recovery files too.

## Phase 2: external embedding server (pending)

The agreed direction remains: disabled-by-default experimental semantics using a user-supplied
EmbeddingGemma 300M OpenAI-compatible server, reviewed destination/data-sharing settings and
PasswordSafe credentials. BM25 remains independent. Persist reusable vectors/input hashes/profile
metadata in a **separate SQLite store**, load relevant vectors into bounded memory and support
portable export/import in the first release. No raw text/query persistence, implicit telemetry,
model download/server management, remote search service, generated answers or automatic sync.
An endpoint/test credential alone never authorizes private-history uploads during development.

The [design reference](docs/semantic-search-design.md) retains the complete protocol, transport,
authorization, cache, scheduling, relevance/resource, failure/privacy and manual acceptance
requirements. It also specifies tests and done conditions for each item below. All remain pending;
its manual requirements are unverified. Keep the existing item numbers for historical continuity.

- [ ] **5. HTTP client:** bounded embedding requests and synthetic loopback protocol/failure tests.
- [ ] **6. Settings:** reviewed destination, PasswordSafe credentials and draft-only synthetic testing.
- [ ] **7. Vector store:** separate durable cache, safe migrations, limits and portable snapshots.
- [ ] **8. Indexing:** bounded app-wide work, incremental/restart/import reuse and scope/authorization lifecycle.
- [ ] **9. Ranking:** qualified semantic eligibility, frozen abstention and fusion with exact-query protection.
- [ ] **10. UI:** lexical-first/refined delivery, honest status/cache controls and validated navigation.
- [ ] **11. Hardening:** full failure/privacy/lifecycle matrix, bounded resources and no content/key leakage.
- [ ] **12. Validation:** real-server synthetic evidence, supported-platform checks and accurate disclosures.
- [ ] **13. Completion:** all acceptance gates, recorded commits/metrics/artifact and honest remaining limits.

At completion, users can configure/test/review/enable the server, find a semantic-only supporting
passage, pause document uploads, disable all new traffic and reclaim active semantic memory.
Unchanged valid documents survive restart/import without re-embedding. Server failure leaves BM25
usable. Disable retains disk cache; explicit purge removes only owned local embeddings and cannot
retract data already sent. Do not mark Phase 2 complete with failing relevance/platform/manual gates.

## Validation commands and completion records

```sh
make check
make lexical-bench
./gradlew verifyPlugin --offline
make release
make sandbox          # then, with it running: make sandbox-verify

git diff --check
git status --short
```

For a focused synthetic source-scale rerun:

```sh
SESHLOG_LEXICAL_BENCH=1 ./gradlew test --offline \
  --tests '*SourceLexicalSearchBenchmark.classifiedScaleAndReuse' --rerun -i
```

Use offline Gradle when dependency access is unavailable. Keep real-history/model/server suites
opt-in; normal tests require none. `make sqlite-bench` and the local-model `make semantic-bench`
remain historical experiments, documented in their reports. Their flags never authorize HTTP uploads.
See the [usage guide](docs/usage.md#troubleshooting) for local diagnostics. No transcript edits to enable logging.

Record completed item commits, actual commands, metrics, platform versions and remaining manual
checks in the relevant report, with a short status update here. Tick only after required regressions
pass under `make check`; a release checkbox also needs its stated acceptance evidence. Use one
focused commit per work item without co-authorship/generated-by trailers. Do not automatically
publish/upload, install into the daily IDE or start deferred/backlog work.

### Recorded evidence (historical, not new validation)

- BM25 implementation: `6d504cd` (evaluation), `8544261` (query/ranking), `442e4ab` (durable store),
  `41ac887` (service/UI); [production report](docs/production-bm25.md) records later hardening,
  schema-2 evaluation and its acceptance limits. Current schema is 3.
- Source controls/provenance: `b3a041b`, `5004ebc`, `1bebda9`, `6356e97`, `9fe89c4`;
  review fixes `5d12013` and `fcd0d57`. [Source-filter report](docs/search-source-filters.md)
  holds frozen fixture identities, metrics, warnings, artifact hashes and remaining checks.
- Historical search validation: 2026-10-27, `make check`, **537 tests, zero failures/errors,
  10 opt-in skips**. The small literal-verification optimization and its synthetic before/after
  benchmark are recorded in the source-filter report.
- Historical search package validation: 2026-10-27, `make release` and `./gradlew verifyPlugin --offline`;
  0.8.1 passed 537 tests (10 opt-in skips), ZIP integrity/content inspection and compatibility on
  IU-262.10968.63 / IU-263.7307.44 with the existing API warnings. Artifact SHA-256 and details are
  in the source-filter report. This build does not complete the remaining manual/platform checks.
- [SQLite experiment](docs/sqlite-bm25-benchmark.md) and [local semantic prototype](docs/semantic-search-prototype.md)
  remain historical measurements with their original caveats and reproducible commands.
- Historical worktree work and unfinished checks remain in Git at `e598a18:PLAN.md`; the earlier
  local-inference plan is `34363dc:PLAN.md`. This consolidation does not complete that work or
  resume it. The completed source-filter handoff is preserved at `fcd0d57:PLAN-filter.md`.
