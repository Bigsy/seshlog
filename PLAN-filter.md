# Plan: search source filters and clear match labels

Prepared 2026-10-25 against `05bbf51` (`test(search): validate and harden the persistent BM25 milestone`).

This is the implementation handoff for the search-source controls discussed with the user.
The user requested this plan; this planning turn does not implement the feature. All implementation
checkboxes below start unchecked. Re-find symbols before editing: line numbers will move.

## 1. Objective, authority and boundaries

Help users find sessions by choosing which parts of the conversation can match, and show where
each displayed match came from. The current search treats user messages, assistant replies and
tool activity as one body population. A file read or command output can therefore make a session
appear relevant even when the conversation was about something else.

Read `AGENTS.md`, this file, the Phase 1 contracts in `PLAN.md`, and `docs/production-bm25.md`
before implementing. This file is the active plan for this narrowly requested follow-up. It
supersedes `PLAN.md` only where that plan requires all conversation/tool sources to participate
in every search. `PLAN.md` remains authoritative for durability, privacy, bounds, exact-query
semantics, compatibility and the separately unfinished Phase 1 acceptance gates. Do not resume
semantic search or unrelated worktree work while implementing this feature.

### Agreed product behaviour

- Add a compact **Search in** control beside the search field, containing three checkboxes:
  **Your messages**, **Assistant replies**, **Tool activity**.
- Default to Your messages **on**, Assistant replies **on**, Tool activity **off**.
- Keep session titles searchable regardless of those checkboxes.
- Label the actual displayed match: **Title**, **You**, **Assistant**, **Tool call**, or
  **Tool result**. Project-path evidence also needs its own **Project path** label.
- The selection controls matching, ranking, quoted-phrase eligibility, snippets and search
  navigation together. It is not a post-processing filter on an already ranked result list.
- Remember the selection. Show its state beside the search box, including when tool activity
  is excluded by the default.
- Clicking a body result opens the exact supporting passage, with existing source-change checks.

### Implementation decisions that close remaining ambiguities

These are explicit defaults for the implementer; do not stall to ask the user to choose them again.

1. **Keep project paths always searchable too.** This preserves existing metadata search. The
   menu note must therefore say **“Session titles and project paths are always included.”**
   The conversation's suggested shorter titles-only note is expanded to describe reality.
2. **Allow all three checkboxes to be off.** That means titles and project paths only. Never
   silently turn a checkbox back on or reinterpret an empty set as all sources.
3. **Persist app-wide**, in IDE settings. All project windows show the same preference and
   rerun their own active searches when it changes. No project-file or agent-data persistence.
4. **Keep all supported sources indexed within existing bounds.** The control restricts query
   evidence, not collection, retention or permissions. Enabling tools should reuse compatible
   postings rather than trigger a filter-specific rebuild. Explain this distinction in help.
5. **No extra relevance-weight experiment in this change.** Keep title/path/body weights 8/3/1
   and best-body aggregation. The earlier suggestion to favour user prompts over replies is a
   possible later improvement, not an additional accepted requirement. Separate source populations
   will change some BM25 statistics; measure that honestly rather than claiming identical ranks.
6. **No provider expansion.** Pi currently supplies dialogue through the default provider method;
   do not implement Pi tool parsing as a side project. Existing Claude subagent activity belongs
   under Tool activity, as explained below.

### Out of scope

Embeddings, network access, model downloads, generated session summaries, inferred topics, new
query syntax, source-specific recency tuning, transcript rewriting, a global redesign of Filters,
and hiding content from the conversation viewer. Normal IDE Find remains a full-document action.
No publishing/upload or installation into the user's daily IDE is part of this task.

## 2. Current implementation map and traps

Paths are relative to `src/main/kotlin/com/hedworth/seshlog/` unless otherwise stated.

| Symbol/file | Current behaviour and required work |
| --- | --- |
| `model/ConversationMessage.kt`: `Role`, `EntryKind`, `ConversationEntry` | `searchable` is true for everything except `COVERAGE`. Tools can have either message role. Add a separate search-source classifier; do not redefine `searchable` globally. |
| `claude/ClaudeCodeSessionProvider.kt`: `conversationEntries` | Main dialogue retains its role. Subagent dialogue is explicitly converted to `TOOL_RESULT`, named `Claude subagent`. Preserve that classification. |
| `claude/ClaudeConversationEntries.kt`, `codex/CodexConversationEntries.kt`, `opencode/OpenCodeConversationEntries.kt` | Already distinguish dialogue, tool calls and results. Reuse these providers and source IDs. |
| `model/SessionProvider.kt`: default `conversationEntries`; `pi/PiSessionProvider.kt` | Pi uses dialogue entries. A checkbox is a search option, not a promise that every provider exposes that source. |
| `model/ConversationLimits.kt` | Shared parser/viewer retention limits and coverage markers. Excluded tool text can still consume the shared retention budget. Keep coverage honest. |
| `index/ContentSearchIndex.kt`: `SearchHit`, `SearchResultSnapshot`, `searchInStore`, `passages` | Passages currently discard role/kind; results only have `titleMatch` and `toolMatch`. Carry classification, options and unambiguous primary-match provenance. |
| `index/lexical/LexicalSearchStore.kt` | Schema 2 has title/path/body FTS tables. SQL takes max title and best body, then weights 8/3/1. Body associations do not identify user/assistant/tool source. Update schema, scoring, literal candidate queries and import/export together. |
| `index/lexical/LiteralEvidence.kt` | Searches all searchable entries for mandatory phrases, technical exact evidence and snippets. Every evidence path must use the selected sources. |
| `index/lexical/RetrievalQuery.kt` | Query parsing/technical and Unicode literal lanes. Source options are separate from textual query syntax. Preserve existing limits and semantics. |
| `index/lexical/PassageTarget.kt`: `validatedPassage` | Validates entry ID/range/hash, currently with no selected-source check. Extend validation for search contexts. |
| `index/ContentSearchService.kt` | Single APP worker and per-caller scopes. Accept immutable source options, include them in request/delivery identity, and keep maintenance separate. |
| `index/SearchRequestScope.kt` | One generation per caller. A single window's typing must not cancel another window's request. |
| `settings/SeshlogSettings.kt` | APP state in `seshlog.xml`. Add three booleans with the new defaults; an old settings file has no saved source choice. |
| `settings/SeshlogSettingsListener.kt` | Broad Settings Apply event. Prefer a dedicated source-selection event for this control so toggling it does not rescan providers or reload unrelated settings. |
| `ui/SessionTreePanel.kt`: header, `scheduleSearch`, `runSearch`, `renderSearchResults`, preview identity | Search reuse/delivery currently considers query, candidates and revisions, not source options. Add the control, source-aware invalidation and result context. |
| `ui/SessionCellRenderer.kt`: `renderSession`, `tooltip` | Currently says Tool match / Content match / Title/path match. Replace ambiguous labels with immutable provenance carried by each hit. No I/O or SQL in renderers. |
| `ui/SessionPreviewPanel.kt`: `showSession`, `load` | Receives hit/query and validates passage. Include source context in reuse and loading guards. |
| `ui/ConversationSearchPanel.kt`, `ui/ConversationDocument.kt` | Currently count and navigate all source matches. Restrict search highlights/counts/navigation to eligible entries while preserving full displayed context. |
| `ui/ConversationEditorTab.kt`: `ConversationEditorTabs.Controller`, private `MarkdownConversation` | Initial target is validated, but F3/navigation currently searches the entire rendered Markdown string. Build source-aware ranges from original entries and map them through `BuiltMarkdown.starts`. |
| `ui/actions/SessionActions.kt` and callers | Carry search context through Open Conversation and any action using the selected hit. Non-search actions retain their existing behaviour. |

Useful audit commands:

```sh
git status --short
rg 'SearchHit|SearchResultSnapshot|ContentSearchIndex|searchSnapshot' src
rg 'titleMatch|toolMatch|validatedPassage|sourceMatches|selectedSearchHit' src
rg 'ConversationEditorTabs.open|showEntries|showSession' src
rg 'SeshlogSettings|settingsChanged|loadState' src/test
```

Do not infer the source from snippet text, tool-name prefixes, session title, or `Role` alone.
Do not filter the provider's returned list before assigning or retaining stable source identities.

## 3. Pure domain types and source classification

Introduce immutable, IntelliJ-independent types under `index/` or `index/lexical/`. Suggested
names are illustrative; pick consistent names and use one classifier everywhere.

- `SearchSources(userMessages: Boolean, assistantReplies: Boolean, toolActivity: Boolean)`.
  Provide explicit `DEFAULT`, `ALL`, and `METADATA_ONLY` values. Equality participates in cache
  and request identity. Do not store mutable settings beans in jobs or hits.
- `SearchEntrySource`: `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`; coverage has no source.
- `SearchMatchSource`: `TITLE`, `PROJECT_PATH`, `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`.
- A request/search context containing the original query and an immutable `SearchSources`.
  Keep text in memory only. Carry the context in the result envelope and through viewer entry
  points; a hit may carry the selection itself if that makes correct propagation simpler.
- A primary-match descriptor containing source, original entry ID/range/hash for body evidence,
  and the matching metadata field/variant for metadata evidence. It must describe the snippet
  actually displayed. Store title variant identity, not a raw copied title, in persistent data.

Classifier precedence:

| Entry | Source checkbox | Display label |
| --- | --- | --- |
| `COVERAGE` | Never searchable | No match label |
| `TOOL_CALL`, regardless of role | Tool activity | Tool call |
| `TOOL_RESULT`, regardless of role | Tool activity | Tool result |
| `DIALOGUE` + `Role.USER` | Your messages | You |
| `DIALOGUE` + `Role.ASSISTANT` | Assistant replies | Assistant |

Claude subagent dialogue is already a `TOOL_RESULT`; do not classify it as the human user's
message based on its retained role. It can show a secondary `Claude subagent` description.
For ordinary tools, the tool name can be secondary text, e.g. `Tool result · shell`.
Search labels describe provenance, not whether a claim is correct or a task succeeded.

Keep `ConversationEntry.searchable` as the structural distinction between content and coverage.
Add an eligibility predicate taking source options. Copy, changed-file extraction, completion
tracking, preview rendering and other consumers of `searchable` must not become filter-dependent.

Production search API defaults must use `SearchSources.DEFAULT`; callers needing all activity
must pass `ALL` explicitly. The existing string-only extractor adapter synthesizes assistant
dialogue and cannot test user/tool classification. Use structured `ConversationEntry` fixtures
for that purpose. Update old tool-search tests to select tools explicitly when that is their
intended scenario; do not weaken assertions just to accommodate the changed default.

## 4. Search contract

### Included fields and combinations

All queries include the provider title, local title override and project/cwd path. Every selected
body source is additionally eligible. All eight checkbox combinations are valid; combine enabled
sources by union, not intersection. A session need not contain every selected type.

Filtering by agent/project/date/hidden/worktree remains the existing session-candidate operation.
Source selection works within those candidates and applies before final top-100 selection.
Pins and grouping remain presentation overrides. The browsing list is unchanged when the query
is empty or below the existing two-character threshold.

Retain the existing 300 ms typing debounce, 4,096-character / 128-term limits, technical-token
classification, one-to-one source folding, phrase rules, score finiteness and deterministic ties.

### Every evidence path uses the same selection

- Ordinary BM25 candidates and scores use enabled populations only.
- An excluded entry cannot satisfy a mandatory phrase or the technical literal requirement.
- An excluded entry cannot grant an exact-evidence tier, provide a fallback substring candidate,
  set a match-source flag, or supply the result's snippet/navigation target.
- Multiple quoted phrases may be satisfied by different **eligible** fields/entries. No phrase
  spans entries. Titles and paths remain eligible for these rules.
- For `"database locked" timeout`, a phrase present only in excluded tool output is insufficient,
  even if an included assistant reply contains `timeout`.
- For a pure technical query, an exact tool match cannot outrank dialogue when tools are off.
  When tools are enabled, retain the existing exact-evidence protection for a genuine tool match.
- Stopword-only, punctuation-only, identifier-substring and Unicode fallback paths obey the same
  rules as normal FTS retrieval. Audit `substringCandidates` and `LiteralEvidence.verify` explicitly.
- A metadata-only result must still be returned if body reading fails, with truthful coverage and
  an explicit Title or Project path label. It must not acquire a body snippet from excluded text.

### Ranking design

Use separate FTS body populations for user dialogue, assistant dialogue and tool activity, with
tool calls/results distinguished in associations. Keep the existing independent title and path
populations. This prevents disabled tool text from affecting the active body's IDF/length
statistics, not just its final row eligibility. Do not use one mixed body FTS population followed
by a category filter while claiming excluded content has no ranking effect.

Suggested SQL/storage field names: `title`, `path`, `user_body`, `assistant_body`, `tool_body`.
The fixed table map must come from code, never interpolated UI/imported values.

For each session:

1. Find max(provider-title, local-title) relevance, as today.
2. Find the path relevance.
3. Find the best passage across the selected body populations. Use **max**, not a sum across
   source categories. Turning on more categories should not multiply the same echoed text.
4. Combine `8 * title + 3 * path + 1 * bestEligibleBody`, retaining the existing exact tier and
   last-activity/provider/session tie order.

Compute scores and aggregation in SQLite. Preserve the MATERIALIZED MATCH/BM25 cursor pattern;
do not replace SQLite with a full RAM postings copy. Source-population statistics remain global
over indexed sessions, as current corpus statistics do; this feature does not promise per-project
IDF recomputation. Verify that adding/changing only disabled-category text, with unchanged
eligible text and metadata, cannot change the active ranking/score.

### Primary evidence and labels

Return one primary explanation and snippet per session; do not duplicate session rows by source.
Keep source provenance through SQL winner selection and literal verification, rather than guessing
it later from `snippet != null` or the session's agent kind.

Use a deterministic policy:

- Prefer genuine full-query exact evidence responsible for a protected exact match when present.
- Otherwise use the largest weighted field contribution; for body evidence retain the actual
  winning passage. For ties use a fixed documented order: Title, Project path, You, Assistant,
  Tool call, Tool result, then stable entry/range identity.
- Literal-only candidates use verified eligible source evidence. A nonblank snippet must contain
  a real source match; normalized token neighbours must not masquerade as literal evidence.
- If a query has mandatory phrases, the session must satisfy all of them, but one displayed
  passage need not contain every phrase. Do not imply that the full query occurred in that snippet.

Primary Title/Project path matches show a short excerpt of the actual matched metadata, with no
fabricated entry ID or conversation range. Local-title matches still display `Title`; a tooltip
can distinguish the local override and provider title. If a hidden provider title matched while
the displayed row uses a local override, the excerpt must make the matched title visible.
For body matches, the label, snippet, entry ID, original range and hash all refer to the same entry.

Replace `toolMatch` as the source of truth. A compatibility property can derive it from the new
source enum during refactoring, but production rendering must not fall back to ambiguous labels.
Similarly audit `titleMatch`: title participation and primary displayed provenance are different.

## 5. UI and saved preference

### Control

Add a compact button immediately beside the search field, labelled `Search in: <summary>` or an
equally clear compact equivalent. Its menu contains, in this order:

```text
[x] Your messages
[x] Assistant replies
[ ] Tool activity

Session titles and project paths are always included.
Reset to conversation
```

Use platform/Swing checkbox menu components with checked states, accessible names and normal
keyboard activation. Let users change multiple entries without losing their search text or tree
selection unnecessarily. This control is separate from agent/date/project filters.

Summary text is a deterministic function of the saved selection:

| User | Assistant | Tools | Visible summary |
| --- | --- | --- | --- |
| On | On | Off | Conversation |
| On | Off | Off | Your messages |
| Off | On | Off | Assistant replies |
| Off | Off | On | Tool activity |
| On | Off | On | You + tools |
| Off | On | On | Assistant + tools |
| On | On | On | All activity |
| Off | Off | Off | Titles & paths |

The tooltip/accessibility description expands the exact included sources and always-included
metadata. Avoid labels such as “Only your messages” because metadata remains eligible. The default
Conversation state stays visible; tools being excluded must not be a hidden assumption.

Changing a checkbox applies immediately and schedules the active query promptly. Cancel/coalesce
old work; do not wait for a new keystroke. With a blank/one-character query, save/update the control
without starting a content search. Clearing the query preserves the source choice. Reset selects
the stated default and does not clear query text or other filters.

Show clear source labels in result rows, tooltips and accessible descriptions. Do not communicate
source solely through colour/icons. Keep long snippets/tool names bounded and handle narrow tool
windows, selected rows, dark/light themes and keyboard-only use.

Empty-state wording should identify the selection, e.g. “No matches in the selected sources.”
Continue distinguishing that from limited coverage or index failure. Do not run a hidden second
all-source search to advertise excluded hits. No-match UI may offer the existing Search in control
or a deliberate Include tool activity action, but never silently enable it.

### Persistence and cross-window behaviour

Suggested fields on `SeshlogSettings.State`:

```kotlin
var searchUserMessages: Boolean = true
var searchAssistantReplies: Boolean = true
var searchToolActivity: Boolean = false
```

Use APP settings in `seshlog.xml`, not `workspace.xml` or organisation metadata keyed by session.
An old settings file gets these defaults; a saved all-false selection must survive serialization
and reload. Audit Settings Apply/Reset so an unrelated setting change cannot overwrite the choice.
Do not add preference fields to index exports or import them from another IDE.

Publish a dedicated settings/source-selection event when the effective value changes. Open
windows update their controls and independently rerun active requests. Avoid recursive event
loops and no-op events. Source toggles must not call `SessionIndex.refresh`, change provider roots,
re-tokenize unchanged sources, clear a durable cache, or reset pinned/selected sessions.

## 6. Durable index and snapshot changes

### Schema and identity

Schema 2 cannot reliably determine message role from stored body postings or arbitrary provider
source IDs. Do not label old rows by guessing. Introduce the next schema version (expected 3;
inspect HEAD first) with source category/kind on body associations and separate body populations.
Retain source stamps, portable fingerprints, metadata revision, partial state, entry IDs, original
UTF-16 offsets and entry hashes. Preserve metadata variant identity where required for labels.

Store all available source populations under the same source revision. The entire replacement,
including category moves, must be atomic. Changing entry kind/role with a changed content stamp
removes its old-category postings. Unchanged stamps reuse all populations after restart.

Keep source classification/versioning explicit. Unknown imported categories fail validation;
they must not be treated as user dialogue. Verify allowed field/category combinations, including
tool-call versus tool-result metadata and metadata rows without body entry references.

Update every lifecycle operation for all new fields: insert, replace, delete, prune, clear,
count/hasRoom, scoring, substring vocabulary tables, export/import, failure fallback and reopen.
The existing 100,000-passage cap is **shared across all body categories**, not per table. Preserve
50,000 sources, SQLite disk/recovery limits, bounded scratch, source-buffer budget and leases.

### Transition from existing databases

Use the current non-destructive incompatible-schema model rather than pretending v2 is compatible:

- Recognize a v2 live index and explain that this search feature requires a local index rebuild.
- Keep the old file intact and provide correctly filtered temporary indexing until the user uses
  the existing explicit Rebuild action. Never query unclassified v2 body rows as current evidence.
- Rebuild retains the existing recovery copy under the process lease; do not invent a second
  uncontrolled backup scheme or silently bypass the recovery/disk-budget rules.
- Preserve future/unknown schemas too. Do not emit a misleading “upgrade Seshlog” remedy for the
  specifically recognized older schema when the required action is to rebuild the local index.
- Use safe reason categories for status text; do not expose database exceptions containing text.

This is a disclosed incompatible-format transition, so one local re-extraction on explicit
rebuild is expected. It does not weaken the requirement that subsequent compatible restarts reuse
unchanged postings. If the implementer instead proposes a genuine migration, it must preserve
the old store and locally validate/reclassify every source; it must meet the same bounds and tests.
Do not attempt to reconstruct roles or transcripts from contentless terms.

### Portable export/import

- New exports include only allowed derived indexes, source metadata and category identity.
  No raw text, snippets, queries, filter preference, credentials or semantic authorization.
- Matching new-format imports preserve all categories and require existing local-source validation
  before use. Toggling source options after import must work without a second import/reindex.
- Reject v2/unknown snapshots with a clear safe explanation and leave the active store intact.
  A user can rebuild and re-export from the original IDE. Do not traverse imported paths.
- Extend exact schema/field/category validation for all new FTS and association tables.
- Preserve adaptive import batching: more than 64k tokens / 4M characters in a tentative batch
  causes a smaller document batch, not rejection of a valid export or unbounded allocation.
- Retain the 256 valid passages × 350 tokens regression; extend round trips across user, assistant,
  tool-call and tool-result rows, with existing-key preservation, cancellation and reopen checks.

## 7. Request identity, caches, cancellation and coverage

Capture source options when starting a request. Include them in search deduplication, any result
cache key, delivered envelope, tree stale-result guards and preview context identity. Do not have
the worker consult mutable global settings midway through a search.

On source changes:

1. Update the app preference and each window's displayed state.
2. Cancel/invalidate each affected caller's old generation immediately, including debounce work.
3. Stop displaying old hits/snippets as if they belonged to the new selection; show the existing
   searching state while preserving the selected provider/session key for restoration if it survives.
4. Run a new request with the same query and candidate scope and the new immutable options.
5. Recheck options, query, candidate/index revisions and generation on EDT delivery.

Keep `SearchRequestScope` caller independence. Shared preference changes affect all windows
intentionally; merely typing/clearing/closing in window A must not cancel window B. Dispose event
subscriptions and queued callbacks normally. All source reads, hashing, FTS work and category
validation remain off EDT; renderers consume immutable snapshots.

Raw source buffers may be shared across selections because they contain the same bounded retained
entries. Do not cache a filtered entry list under only a source stamp. Pure SQL scores/hits that
depend on selected categories must not reuse a result from another selection.

Coverage describes available evidence for the current query selection, separately from intentional
exclusion. A category turned off is not a missing/unindexed category. Existing provider truncation
can still make selected text incomplete: for example, early tool logs consume the shared 500k
retention budget and later user turns were never read. Keep that warning; do not mark it complete
merely because tools are excluded. Do not change shared extraction limits or parse repeatedly per
selection to work around it in this feature.

Metadata-only searches should not claim missing body evidence is required to answer the query.
They may reuse or update metadata independently; do not mark an unindexed/stale body as freshly
indexed just because metadata-only search succeeded. Retain source lifecycle/availability state
separately from selected-source search coverage where necessary. Document any conservative
partial reporting when provider limits cannot identify which category was omitted.

## 8. Preview, full conversation and navigation

The conversation remains visible as context, including excluded categories. Only the matches,
highlights, counts and Seshlog next/previous navigation belong to the selected search sources.
This distinction avoids turning a search filter into a lossy conversation viewer.

- A body hit opens its primary eligible entry/range after background ID/hash/range validation.
  Also validate the entry's category against the captured selection and expected match provenance.
- A changed/missing/reclassified entry shows the existing refresh/unavailable message. Do not
  jump to the first unrelated occurrence or quietly choose an excluded source.
- Eligible tool hits still expand collapsed tool activity. Excluded tools stay visible as context
  but are not counted, highlighted or traversed by this search's navigation.
- Update `ConversationDocument.sourceMatches` and display-match mapping using the central
  eligibility predicate; preserve original entry indices when revealing a source match.
- Replace whole-Markdown query matching in `ConversationEditorTabs.Controller` with eligible
  original-entry matches mapped through `BuiltMarkdown.starts`. Headers, tool labels, dates,
  separators and coverage notices are not body search evidence.
- Metadata-primary hits initially show “Matched session title” / “Matched project path” and
  avoid a fake conversation target. If eligible body matches also exist, explicit next/previous
  navigation can visit them; none may come from excluded sources.
- A preview follows the current tree search context and invalidates on source changes even when
  query/session are identical. An already opened full editor tab keeps the query/source context
  it was opened with; a later global filter change does not silently move its caret or change its
  navigation. Display that captured context unobtrusively. A newly opened tab gets the new context.
- Opening a conversation outside a search retains existing behaviour. Normal IDE Find can still
  search the whole displayed document and does not change the saved Search in preference.

Copy actions, read-only editor semantics, latest-reply positioning and completion/unread tracking
must keep their existing behaviour. Avoid using the new source predicate for those operations.

## 9. Tests and relevance evaluation

Use synthetic fixtures only. Add a small readable fixture under `src/test/resources/fixtures/search/`
with real role/kind distinctions, including tools whose recorded role is USER and Claude subagent
entries. The current evaluation helper creates all fixture messages as assistant dialogue; it is
not sufficient to validate source filtering without extending the fixture adapter/schema.

### Pure/domain and retrieval acceptance

- [ ] All classifier combinations, including coverage and subagent tool-result precedence.
- [ ] All eight selections, empty corpus, provider-ID collisions and metadata-only sessions.
- [ ] Same marker only in user / only in assistant / only in tool-call / only in tool-result:
  included exactly when its category is enabled; default has no tool-only result.
- [ ] Always-included provider/local titles and paths; all checkboxes off searches those only.
- [ ] Excluded text cannot alter BM25 score/order, exact tier, substring candidates, snippets,
  result limits or phrase eligibility. Add/change a large disabled tool log with unchanged
  eligible text/metadata and assert the selected result's score, order and evidence stay stable.
- [ ] Best-body max across enabled categories; duplicated echoed text does not sum category scores.
- [ ] Mandatory phrase only in excluded text fails; optional word in included text cannot rescue it.
- [ ] Multiple phrases split across two included categories succeed; disabling the only source
  satisfying one makes the query fail. Entry-crossing phrase still fails.
- [ ] Quoted phrase in title/path remains eligible with all body sources off.
- [ ] Technical identifiers, C++, C#, paths, stopword-only text, Unicode and identifier-substring
  fallback all obey selection. More than 100 excluded high-scoring entries cannot hide a valid hit.
- [ ] Primary label/snippet/entry/range agree for every source. Distinguish matching provider title
  from a displayed local override; distinguish path from title without fabricated body targets.
- [ ] No finite-score/tie-order regressions and no false hits from coverage labels or tool names
  that exist only in rendered headings, rather than actual searchable source text.

### Persistence and lifecycle acceptance

- [ ] New defaults load from old settings; all eight saved combinations round-trip, including
  all false. Unrelated Settings Apply/Reset preserves the chosen values.
- [ ] Source toggles reuse a compatible index; no re-tokenization or provider rescan solely due
  to toggling. Any original-source reads needed for snippet/exact verification are counted separately.
- [ ] Reopen reuses category metadata/postings; metadata-only renames do not extract bodies.
- [ ] A changed source moving between categories removes old evidence atomically; failed/cancelled
  replacement retains the previous consistent source revision without publishing it as current.
- [ ] Known schema-2 DB and future-schema DB are preserved; temporary fallback obeys new filters;
  explicit Rebuild produces the new format and preserves the existing recovery guarantees.
- [ ] New-format round-trip preserves source kinds, scores, source validation and navigation;
  imported preference does not exist. Reject legacy/malformed category snapshots without mutation.
- [ ] Adaptive dense-passage import, idempotence, existing-source precedence, rollback, full/busy
  storage, process leases, shared total passage cap, disk/recovery accounting and purge remain valid.
- [ ] Filtered-out categories are not reported as failures; real shared-retention loss still is.
  Zero-hit envelopes distinguish complete selected-source no-match from partial/error states.

### Service/UI acceptance

- [ ] Changing options with unchanged query/candidates causes a new request. An old all-source
  result delivered late cannot reappear under Conversation or Your messages.
- [ ] Two windows synchronize saved options but preserve independent text-query cancellation.
  Closing either window leaves the other usable; late callbacks after disposal are ignored.
- [ ] Clear/short query, reset, rapid toggles and a source update during search preserve correct
  state and do not trigger unnecessary scans or reindexing.
- [ ] Control summaries, checked states, labels, tooltips and accessible descriptions are correct;
  keyboard activation works. Selected row/group/pin behaviour survives refreshed results.
- [ ] Preview and full-editor initial navigation use the eligible primary source; source changes
  never use stale offsets. F3/previous/next/counts/highlights exclude disabled categories.
- [ ] Title/path-only hits do not auto-jump into excluded body text. Full context remains visible;
  normal Find and copying still work. An already opened tab keeps its captured search context.

Extend existing tests where appropriate: `ContentSearchIndexTest`, `LexicalRankingTest`,
`DurableLexicalIndexTest`, `LexicalSnapshotImportTest`, `SearchStorageBoundsTest`,
`ContentSearchServiceTest`, `SearchRequestScopeTest`, `SeshlogSettingsTest`, `SessionFilterActionsTest`,
`SessionListViewModelTest`, `RankedPassageTest`, `SessionPreviewPanelTest`, `ConversationDocumentTest`,
`ConversationEditorTabTest`, `ConversationCopyTest`, and `SeshlogToolWindowTest`. Use constructor
injection/pure helpers, JUnit 4, and `BasePlatformTestCase` only where platform/EDT state is needed.

### Relevance and performance evidence

Freeze new synthetic source-labelled cases before tuning. Include misleading large tool output,
an assistant-only solution term after a vague user prompt, task-focused user requests, repeated
assistant/tool echoes, technical errors found only in a tool result, and correct metadata-only
matches. Label relevance for the selected source configuration; an intentionally excluded correct
tool result is not a recall bug under Conversation.

Compare default Conversation, Your messages and All activity on the same retained text, recording
expected inclusion and primary evidence in addition to hit@1/recall@5/MRR and no-answer cases.
Keep the original frozen corpus/report unchanged as historical evidence. Document new schema,
fixture hash and expected ranking differences from separated FTS populations. Never tune labels
or remove difficult cases merely to recover the previous generated scaffold's perfect scores.

Run the synthetic 1k/10k benchmark for default and All activity, plus a bounded long-tool case.
Record query p50/p95, source reads versus tokenizations, filter-toggle costs, category/shared counts,
restart/import reuse and memory/disk costs. Keep the existing warm p95 <100 ms reference target;
report failures rather than quietly relaxing it. Test all-off metadata mode separately. Do not
claim the lower-memory/real-history/manual gates in `PLAN.md` have passed from this benchmark.

## 10. Ordered implementation work items

Use one focused commit per completed work item, without co-authorship/generated-by trailers.
Inspect user changes first and preserve them. Do not check a box just because code exists: its
regressions must pass under `make check`. Keep manual evidence and pending checks explicit.

### 0. Freeze source semantics and fixtures

- [x] Add immutable source options, classification/provenance types and the shared eligibility
  predicate. Freeze the selected-source query cases and primary-match expectations.
- Encode defaults, all-off behaviour, metadata inclusion and tools-with-user-role explicitly.
- Done when pure tests pass and `make check` passes; no UI behaviour switch is required yet.

Implemented: immutable source/context/provenance types and a kind-first eligibility predicate.
`source-contract.json` freezes role/kind cases, including USER-role tools and Claude subagents.
`SearchSourcesTest` covers every role/kind and all eight selections. `make check` passed 2026-10-26.

### 1. Implement classified durable storage and filtered retrieval

- [x] Add the next schema, separate body populations and kind-preserving associations.
- Thread immutable options through index APIs; implement SQL aggregation, exact/phrase/substring
  restrictions and accurate primary-match provenance. Keep all-source indexing reusable.
- Complete old-schema handling, status reasons, new export/import validation and lifecycle updates.
- Add category isolation, dense import, migration/fallback and source-race regression tests.
- Done when default/all/metadata-only retrieval and persistence contracts pass under `make check`.

Implemented schema 3/classification 1, independent user/assistant/tool populations, a shared
100,000-passage ceiling, SQL best-body max and weighted primary-field provenance. All literal
lanes use captured options. Metadata-only queries do not extract or validate body revisions;
failed body reads still permit truthful metadata evidence. Known v2/future formats are preserved
with temporary filtered fallback. Dense imports cover 256 × 350-token rows in every source kind,
reopen/idempotence, malformed categories and rollback. Disabled-category score/evidence isolation,
all eight combinations, phrases, exact/Unicode/substring lanes and provider collisions are tested.
`make check` passed 2026-10-26 (510 tests, 8 opt-in skips). No manual UI acceptance claimed.

### 2. Add saved controls and source-aware asynchronous delivery

- [ ] Add app settings, a targeted notification event, the Search in menu and visible summaries.
- Extend service requests/result envelopes, deduplication and delivery guards with source options.
- Test immediate toggles, empty queries, all-false persistence, two windows and stale callbacks.
- Done when platform tests and `make check` pass; record remaining manual UI checks.

### 3. Render clear labels and preserve filtered navigation

- [ ] Replace ambiguous row/tooltip/accessibility labels with primary provenance and matching text.
- Carry context through preview and Open Conversation; implement eligible source ranges for
  highlights/counts/F3 in both viewers, while preserving full context and normal Find.
- Add source-change, metadata-only, collapsed tool and captured-editor-context regressions.
- Done when navigation/UI tests and `make check` pass; no filesystem work is introduced on EDT.

### 4. Evaluate, document and validate the feature

- [ ] Run the source-labelled evaluation and bounded default/all/long-tool benchmarks; record
  fixture/schema versions, measured results and limitations.
- Update README's search section, tool/subagent claims and data-retention explanation. Explain
  defaults, titles/paths always included, source labels, all-off mode, scope of F3 versus normal
  Find, app-wide persistence and the disclosed old-index rebuild transition.
- Keep `PLAN.md`'s main search description consistent by referencing this scoped follow-up;
  do not mark its pre-existing manual/platform/low-memory gates complete without evidence.
- Run required checks and inspect the plugin ZIP for test/fixture/private-data leakage.
- Done when automated tests and the manual acceptance below pass, or explicitly hand off the
  remaining manual checks without claiming release completion. Do not publish/upload.

Commands (adjust test names to actual new classes):

```sh
make check
./gradlew test --offline --tests '*ContentSearchIndexTest' --tests '*LexicalRankingTest' \
  --tests '*LexicalSnapshotImportTest' --tests '*DurableLexicalIndexTest'
make lexical-bench
./gradlew verifyPlugin --offline
make release
make run
git diff --check
git status --short
```

Normal tests must not require a model, server, credentials, real histories or network. Do not add
real-data flags to these commands. Use offline Gradle when necessary; record verifier IDE versions
and existing warnings accurately. Do not install the build into the daily IDE without a request.

## 11. Manual acceptance checklist

Use synthetic sessions/profile data, following existing project testing conventions. Verify the
intended sandbox/profile actually loaded before interacting; a launcher argument alone is not
proof. Do not modify agent-owned data to make examples or enable diagnostics.

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

## 12. Completion report required from the implementer

Report the actual default/menu behaviour, classification and provenance model, schema transition,
test counts/commands, relevance/resource results and manual rows completed versus still owed.
Mention any intentional ranking change caused by separate source populations. Identify each
implementation commit and leave a clean, reviewable diff. Do not conflate completing this feature
with completing all of Phase 1 or implementing Phase 2.

Implementation evidence: pending.
