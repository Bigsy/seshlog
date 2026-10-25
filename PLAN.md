# Plan: BM25 default search and optional local EmbeddingGemma

Prepared 2026-10-25. This is the authoritative implementation plan for the next work.
It replaces the worktree plan at the user's request. That plan, including unfinished manual
checks, remains in Git at `e598a18:PLAN.md`; replacing it does not mark those checks complete.
Do not resume unrelated worktree work as part of this search feature.

## 1. Start here — instructions for a fresh session

1. Read `AGENTS.md`, this entire file, and `docs/semantic-search-prototype.md`.
2. Run `git status --short` and inspect existing changes before editing. Preserve user work.
3. Read the production entry points in section 4 and the test-only prototype. The prototype is
   evidence and reference code, not a production implementation to copy wholesale.
4. Implement the numbered work items in section 12, in order. Each item specifies its output,
   tests and completion condition. BM25 can ship before the semantic feature is finished.
5. Keep this file current: record decisions, measurements, passing commands and outstanding
   manual checks under the relevant item. Check a box only after its tests pass under `make check`.
6. Make one focused commit per completed work item. No co-authorship or generated-by trailers.
7. Do not ask the user to reconfirm the agreed direction. Resolve routine implementation choices
   within this plan. If evidence invalidates a stated default, record the evidence and replacement
   here before changing it. Do not quietly relax privacy, compatibility or release gates.
8. Do not claim the semantic feature is complete because a benchmark or fake worker passes.
   The final endpoint includes real local inference, setup/settings, lifecycle, useful navigation,
   resource controls, failure fallback and manual acceptance on the supported platform matrix.

### What the user has agreed

- Make BM25 the default session-search ranking method.
- Add EmbeddingGemma as an optional, experimental setting, disabled by default.
- Explain the extra model download, RAM, CPU/battery use and indexing time in settings.
- Keep session processing local. The user explicitly authorized network access for development
  and model setup; the old blanket no-network instruction was clarified in `AGENTS.md`.
- Preserve reliable exact phrase and technical identifier searches.
- Ship the inexpensive BM25 improvement first; expose semantic search only after its operational
  and relevance problems are addressed. The final target is both features, not BM25 alone.
- This planning request does not ask for a new implementation during the planning turn.

### Out of scope

Cloud inference, telemetry, transcript modification, syncing embeddings, persisted content/vector
indexes, generated answers or summaries, a chat/RAG assistant, reranker models, arbitrary embedding
model selection, changing providers' extraction limits, and unrelated worktree/session tracking.
Do not introduce Lucene, a vector database, Python, Ollama or an external account as a mandatory
plugin dependency. The default search must work without a model or native helper installed.

## 2. Evidence already available — do not turn it into promises

The prototype was committed as `e598a18` and lives only under `src/test/`. `make check` passed:
462 tests reported, zero failures/errors, four skipped, including the opt-in benchmarks.
The relevant artifacts are:

- `src/test/kotlin/com/hedworth/seshlog/semantic/SemanticPrototype.kt`
- `src/test/kotlin/com/hedworth/seshlog/semantic/LlamaEmbedding.kt`
- `src/test/kotlin/com/hedworth/seshlog/semantic/SemanticSearchBenchmark.kt`
- `src/test/kotlin/com/hedworth/seshlog/semantic/SemanticPrototypeTest.kt`
- `src/test/resources/fixtures/semantic/retrieval.json`
- `docs/semantic-search-prototype.md` — reproduction commands and complete measurement caveats.

The dataset contains 30 synthetic sessions, 90 passages and 44 queries: 24 paraphrases, 12
literal/identifier queries, four quoted queries, and four unrelated/no-answer queries. Expected
labels were authored before inference. It is small and deliberately clear, not independently
held-out evidence of real-history relevance.

| Method | Correct first result, 40 positive queries | Relevant in first five |
| --- | ---: | ---: |
| Current literal AND search | 15/40 | 16/40 |
| Prototype BM25 | 36/40 | 39/40 |
| EmbeddingGemma, 768 dimensions | 40/40 | 40/40 |
| Literal + semantic fusion, 768 dimensions | 39/40 | 40/40 |
| EmbeddingGemma, 256 dimensions | 37/40 | 40/40 |
| EmbeddingGemma, 128 dimensions | 33/40 | 40/40 |

Paraphrases alone: literal 0/24 first, BM25 21/24, semantic 24/24. Much of the improvement comes
from better lexical matching, not necessarily from the model. The tested hybrid fused the current
literal index with semantic rankings; **BM25 + semantic fusion has not yet been measured**.
Semantic-only returned results for all four unrelated queries. Hybrid returned results for the
two unquoted unrelated queries. No acceptance threshold was calibrated.

The local CPU benchmark used an Apple M1 Max, 64 GB RAM, four inference threads and llama.cpp
0.4.0/build 10809/commit `5266f24da`. The model was **EmbeddingGemma 300M Q8_0**, not Gemma 2:

- GGUF: `embeddinggemma-300M-Q8_0.gguf`, 333,590,944 bytes (~334 MB decimal).
- Distribution: `ggml-org/embeddinggemma-300M-GGUF` at revision
  `0f741b5a6585bd53aeb15cd1372c56f2a0f65e12`.
- SHA-256: `b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63`.
- Existing local copy, if still present: `build/semantic-models/embeddinggemma-300M-Q8_0.gguf`.
  It is ignored and is not part of the plugin or repository. Do not assume it survives a clean.
- Query prefix: `task: search result | query: `.
- Document format: `title: {title or none} | text: {passage}`.
- Output: 768 float values, L2-normalized. Truncation requires normalization again.

A bounded sample selected ten recent sessions each from Claude, Codex, Pi and opencode. It embedded
536 passages from 40 sessions in 43.69 seconds, with ~1.35 GB native peak RSS. Comparison against
those vectors took ~1 ms. The vectors alone occupied 1,646,592 bytes. This is not total memory.
32 sessions were clipped at 16 passages; ten had provider-reported partial coverage. No real-data
quality labels were collected. Do not extrapolate these timings to complete histories as facts.

A fresh process for one query took ~1.0–1.1 seconds, including model load/warmup. There is no
measurement of a resident worker's query latency yet. Native RSS excludes JVM memory. The first
2,048-token batch configuration peaked at ~3 GB; using 512 tokens reduced it to ~1.35 GB.
The 320-byte prototype passages are conservative and short; they are not an established optimal
chunk size. Smaller output vectors do not reduce the cost of running the model.

## 3. Constraints that must survive implementation

- IntelliJ Platform 2026.2/build 262, JDK 25, Kotlin API level 2.3. Inspect the SDK/dependencies
  before choosing APIs. Do not infer compatibility from the Kotlin 2.4 stdlib bundled by the IDE.
- Agent roots are read-only, including `~/.claude`, `~/.codex`, `~/.pi/agent` and opencode storage.
  Never create a model/cache/temp file there. Respect configured roots and existing provider APIs.
- Text, token postings, document frequencies, passage hashes, query caches, embeddings and their
  associations remain in memory. Do not write a Lucene/vector index or dump prompts into temp files.
  Content hashes/embeddings are content-derived data, not harmless metadata persistence exceptions.
- Models and native binaries are reusable assets and may live in Seshlog-managed IDE storage.
  Preferences and asset metadata belong in IDE settings/storage. Existing transcript parse caches
  remain metadata-only; do not expand them to include search content.
- Model/runtime downloads are explicit user actions. No automatic download on plugin install,
  IDE startup, opening settings, typing a query, opening a project, or a background update.
- No session text, query text, IDs, paths or embeddings are sent to external services. Network
  access during setup carries only public artifact requests, not local search information.
- Runtime inference must work with external network access unavailable. Use local process pipes;
  do not add a localhost HTTP server, arbitrary remote endpoint or listening socket.
- No text or vectors in command-line arguments, environment variables, diagnostics, exception
  messages or process output logs. Audit the existing raw-query DEBUG log in `ContentSearchService`.
- All extraction, tokenization, scoring, model I/O, hashing and downloads run off the EDT.
  Rendering consumes immutable snapshots. Never perform filesystem access in a Swing renderer.
- Keep ranking and lifecycle decisions pure/testable. Inject extractors, stamps, executors,
  clocks, downloader/worker factories and resource probes rather than mocking IntelliJ classes.
- JUnit 4 for pure logic; `BasePlatformTestCase` for project/EDT/service tests. New fixtures are
  synthetic and committed under `src/test/resources/fixtures/`. No real transcript fixture copies.
- Preserve unreadable/malformed-provider handling, shared extraction limits, filters, continuation
  handling, subagent coverage, pins/hidden state, session actions and terminal tracking.

## 4. Existing code map and traps

Paths below are relative to `src/main/kotlin/com/hedworth/seshlog/`. Trust symbols, not line numbers.

| Location / symbol | Current behavior and implication |
| --- | --- |
| `index/ContentSearchIndex.kt`: `SearchHit`, `search`, `entryFor`, `retainOnly` | Literal AND across titles/cwd/messages, substring phrases, capped occurrence score, title bonus, recency ties. Stores original and folded text under a 32,000,000-character budget. `score` is `Int`; BM25/RRF need non-integer scoring or an explicit rank representation. |
| `index/TextQuery.kt`: `parse`, `ranges`, `foldCase`, `HINT` | Shared with conversation navigation. One-to-one UTF-16 folding keeps source offsets valid. Hint says all words must match. Do not break viewer highlights by turning this into a retrieval-only tokenizer. |
| `index/ContentSearchService.kt`: `search`, `dispose` | APP service with one bounded executor and per-caller cancellation. Refreshes content via provider stamps. Logs the raw query at DEBUG; remove that. An empty list currently also represents failure. |
| `index/SearchRequestScope.kt`: `begin`, `cancel`, `dispose` | Each caller has its own generation. Preserve cross-window independence; do not use a single global query generation to cancel all callers. |
| `index/SessionIndex.kt`, `model/SessionProvider.kt` | Resolve provider, indexed sessions, `conversationEntries` and `contentStamp`. Reuse these; do not build a separate private-transcript parser. |
| `model/ConversationMessage.kt`: `ConversationEntry` | Has `sourceId`, `kind`, `truncated`, `searchable`, text and tool metadata. Keep source identity through chunking/ranking/navigation. Coverage entries are not content. |
| `model/ConversationLimits.kt` | Per entry 32,000 chars; per session 500,000 chars/2,000 entries; records over 1,000,000 chars skipped; source scan bounded to 16,000,000 chars. Shared with viewers. Preserve these limits and their coverage notices. |
| `settings/SessionOrganisation.kt`: `metadata`, `title` | User title overrides, pins, hidden state. Title changes affect search even when a transcript stamp is unchanged. Organisation currently keys by raw session ID; do not migrate unrelated storage in this work. |
| `ui/SessionTreePanel.kt`: `runSearch`, `baseFilter`, `renderSearchResults` | Two-character minimum, 300 ms debounce, project/date/provider/hidden/worktree filters. Deduplicates by query + candidate list; add index/settings/model revision invalidation. `searchPartial` currently derives only from hits, so zero-hit partial results need an envelope. |
| `ui/SessionTreeModel.kt`: `groupRanked` | Preserves hit order inside project groups, with pins affecting group/session presentation. Do not accidentally sort search rows by recent activity again. |
| `ui/SessionCellRenderer.kt` | Displays hit snippets/highlights; add match-kind presentation without embedding filesystem work. |
| `ui/SessionPreviewPanel.kt`: `showSession`, `load` | Currently receives session + query, not a winning passage. Its reuse guard must include hit/source/index revision when navigation targets change. |
| `ui/ConversationSearchPanel.kt`, `ConversationDocument.kt` | Literal source matches and offsets; collapsed tool expansion via `reveal`. A semantic hit can have zero literal matches. |
| `ui/ConversationEditorTab.kt`, `ui/actions/SessionActions.kt` | Audit the result-to-full-conversation navigation route; carry the winning entry/range rather than always searching query words. |
| `settings/SeshlogSettings.kt` | APP persistent state in `seshlog.xml`. Put semantic preferences here, never in project `workspace.xml`. |
| `settings/SeshlogConfigurable.kt`: `createPanel`, `apply` | Settings UI; apply currently refreshes `SessionIndex` and fires `SeshlogSettingsListener`. Avoid rescanning everything just because a semantic status changed. |
| `settings/SeshlogSettingsListener.kt`: `TOPIC`, `fire` | Broadcasts applied preference changes to open windows. Keep progress/status changes separate from preference changes. |
| `diagnostics/SessionDiagnostics.kt` and associated field sanitizers | Reuse local bounded diagnostics patterns; report reason codes/counts/timings without content. Review field allowlists before adding events. |

Existing tests to extend: `ContentSearchIndexTest`, `ContentSearchServiceTest`, `SearchRequestScopeTest`,
`TextQueryTest`, `SessionListViewModelTest`, `WorktreeScopeTest`, `SessionPreviewPanelTest`,
`ConversationDocumentTest`, `ConversationEditorTabTest`, `SessionFilterActionsTest`,
`SeshlogToolWindowTest`. Find other `SearchHit` constructors/usages with `rg` before changing its shape.

## 5. Search contract — implement this before tuning scores

### Default lexical behavior

- Unquoted natural-language terms are relevance signals. Matching every word is no longer required.
  Return candidates with meaningful lexical evidence, rank the best evidence first, omit zero scores.
- Quoted phrases are mandatory, case-insensitive literal substring constraints in a single field
  or source entry, using existing folding rules. They cannot span unrelated messages. Multiple
  phrases must all match somewhere in the session; they may match different fields/entries.
- For `"database locked" timeout`, the phrase is required; `timeout` boosts relevance but is not
  a second mandatory filter. For phrase-only queries, use exact evidence and stable ranking.
- Retain deterministic handling of unmatched quotes consistent with current parser tests. Do not
  introduce a hidden query language, operators, wildcards, or implicit Boolean syntax.
- Index provider titles, local title overrides, cwd/project paths and searchable conversation/tool
  entries. Preserve metadata-only hits when transcript content is absent or temporarily unreadable.
- Exact full-query matches in titles, identifiers, file names and paths get strong relevance.
  Tokenization must not lose `SearchRequestScope.kt`, `foo_bar`, `camelCase`, `a.b.C`, `C++`,
  `--retry`, `SQLSTATE 40P01`, stack-trace fragments, numeric error codes or Windows paths.
- Keep raw technical tokens alongside split forms where useful. Split camelCase/snake_case for
  discovery but deduplicate equivalent contributions so aliases do not multiply the same evidence.
- Do not apply English stopword removal to phrases, identifier/path tokens or exact matching.
  Do not blindly stem source symbols. Start without stemming. If all ordinary tokens disappear,
  use a bounded literal fallback rather than silently making common-word queries impossible.
- Keep the existing two-character minimum and 300 ms debounce initially. Explain the new behavior
  in the hint: `Ranked by relevance; use "quoted phrases" to require exact text.`
- Bound pathological pasted input: initially allow at most 4,096 UTF-16 characters and 128 compiled
  terms. Above that, show a concise query-too-long message instead of silently dropping terms or
  phrases. If an otherwise valid query exceeds the native model's token budget, run BM25 only
  with an availability reason; do not silently truncate the semantic query.
- Stable tie order: exact-evidence tier, numeric relevance, last activity, provider/session key.
  Preserve current pin/group presentation as an explicit UI override, not part of relevance metrics.

### Semantic behavior

- Semantic mode supplements BM25. The ordinary search field and project/date/provider/worktree/
  hidden filters apply to both retrieval paths. Never infer filters from a sentence.
- For the first release, queries containing any quoted phrase use lexical search only. Pure
  technical identifier/path queries also stay lexical; use a deterministic, tested classifier.
  Natural-language queries containing a symbol may use both, while retaining the exact lexical hit.
- Deliver lexical results promptly, then at most one semantic refinement for the same query and
  corpus generation. Preserve selection by provider/session key and the user's scroll position.
  A later index revision may trigger a new request; debounce/coalesce these updates.
- Do not return a semantic candidate solely because it is in the nearest-neighbor top K. It must
  pass an independently evaluated relevance/abstention policy before fusion. Show no results when
  neither lexical evidence nor eligible semantic evidence exists.
- A semantic result shows the actual supporting passage and a small `Related meaning` label.
  It must navigate to that passage even if there is no query-word overlap. Do not fabricate literal
  highlights, exact-match counts, confidence percentages or generated explanations.
- If semantic setup is missing, indexing is partial/paused, the model crashes, or a request times
  out, BM25 remains usable. Show a concise status explaining reduced semantic availability.
- Unknown/unindexed/evicted content is not evidence of no match. Report lexical and semantic
  coverage separately, including when there are no hits.

## 6. Production design and data flow

Names below are suggested new symbols. They do not exist yet. Keep pure code separate from services.

### Shared snapshot and query/result types

Introduce `SearchSessionKey(kind, id)` internally to prevent cross-provider collisions. Do not
migrate existing organisation persistence as a side effect. Preserve the underlying `Session` on
results for existing actions. Audit UI maps currently keyed only by `session.id` where results meet.

Introduce a retrieval query plan separate from literal display matching, e.g. `RetrievalQuery`:
raw input in memory, ordinary terms, exact phrases, technical tokens, query kind and version.
Use `TextQuery`/folding for literal constraints and source offset matching; share lexical token
logic between document indexing and query compilation.

Replace the bare result list boundary with an immutable envelope, e.g. `SearchResultSnapshot`:
request generation, candidate/filter revision, corpus revision, settings/model revision, ordered
hits, phase (`LEXICAL`/`REFINED`), lexical coverage, semantic coverage and sanitized availability.
Coverage includes eligible/indexed/skipped/limited counts and reasons, independent of hit count.
Do not present parse failure as a confident complete empty result.

A hit should carry: provider/session key, Session, rank/score, match kind, title/path match flags,
source entry ID, original UTF-16 passage range, snippet, content revision and partial flags.
Use `Double` internally for BM25/fusion, reject non-finite scores, and keep scores out of user UI.
The chosen passage range must be revalidated against the currently loaded source before scrolling.
If it changed, refresh the hit or show a truthful unavailable/changed message; do not jump blindly.

### BM25 index

Build a bounded in-memory inverted index over the shared provider-extracted snapshot. Do not
re-tokenize every document for every keystroke as the prototype does. Store term frequencies,
lengths and enough source positions for snippets. Keep title/local-title/path fields separate
from conversation fields so a huge tool log cannot dilute a useful title match.

Use standard BM25 as the initial scorer (`k1=1.2`, `b=0.75`):

```
idf(t) = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
score(t,d) = idf(t) * tf(t,d) * (k1 + 1)
             / (tf(t,d) + k1 * (1 - b + b * len(d) / avgLen))
```

Define the scoring unit consistently: start with bounded dialogue/tool passages as body documents;
metadata fields are independent short documents with field weights. Each term contributes once
per field representation. Session body score starts as its best passage score (not the sum over
all chunks), combined with weighted metadata scores and exact-evidence tier. Initial weights are
title 8, path 3 and body 1; use the maximum of provider-title and local-title scores, not their sum,
so an identical override cannot double the title evidence. These are starting values to calibrate
on development labels and record here, not measured optima. Do not optimize on the held-out set.

`N`, `df`, lengths and average length must refer to one immutable index revision and one consistent
field/document population. Start with statistics over the current bounded indexed corpus, then
filter postings to the caller's candidates before ranking/top-K. Do not rank globally and discard
out-of-scope hits afterward. Eviction changes statistics and increments corpus revision; publish
updates atomically. Report partial coverage if the bounded corpus excludes candidates.

Provider content stamps control extraction invalidation. Unknown stamps are not reusable evidence;
retry rather than making an indefinitely stale cache. Metadata/title changes need independent
revision tracking. Remove deleted sessions from text, postings, statistics and semantic associations.
Failed extraction must be retriable. Cancellation must not publish partially mutated index state.
Use immutable replacement entries/snapshots and short critical sections.

### Semantic worker and index

Use one APP-owned worker shared across project windows, loaded on demand, not one per query/window.
The implementation direction is a small supervised native helper linked to a pinned llama.cpp
build, with framed stdin/stdout requests and responses. The prototype CLI reloads each call and
uses `/dev/stdin`; it is not the final worker protocol and is not Windows-compatible as written.

Prefer an isolated process to loading native model code into the IDE JVM. No shell interpolation,
user shell hooks or hidden service daemon. Startup handshake verifies protocol/runtime/model
identity and dimensions. Requests carry internal IDs, input type, bounded texts and model version;
responses carry the same IDs, vectors or sanitized reason codes. Enforce maximum frame sizes,
row counts, dimensions, finite values and nonzero norms. Never forward native stderr verbatim.

The helper must offer token counting or tokenizer-aligned chunking. Begin with the measured
512-token batch configuration; do not expand it to 2K just because the model supports 2K input.
Keep title/prompt/special-token overhead inside the actual budget. Respect Unicode and preserve
source UTF-16 offsets. Evaluate modest passage overlap and adjacent short dialogue turns; never
combine unrelated sessions or lose entry mapping. Exclude coverage markers and existing excluded
content types. Prefer prose before large repetitive tool output when a session budget is reached,
but keep tool-only technical evidence searchable through BM25 and test semantic tool retrieval.

Store normalized 768-dimensional float vectors initially; smaller dimensions lost useful top-one
quality in the prototype and saved little relative to native memory. Use exact dot-product search
over the bounded vector set first; no ANN library until measured scale requires it. Deduplicate
chunks and aggregate to one hit per session before top-K/fusion.

Cache identity includes provider/session key, content stamp, entry/range, local/provider title
revision, model checksum, dimensions, tokenizer/prompt/chunking version. Content equality hashes
may deduplicate unchanged passages in memory. Appended data should embed new/affected chunks,
not re-embed the entire history. Reject late results for superseded stamps/models/settings.
Model changes invalidate all embeddings and any query-vector cache.

### Hybrid ranking and abstention

Compare BM25-only, semantic-only and BM25+semantic on the same frozen candidate corpus. Start
with reciprocal rank fusion at session level (`sum(1 / (60 + rank))`, one-based ranks), using
bounded top-K lists after filtering and semantic eligibility checks. Keep exact lexical evidence
in a protected first tier; unrelated semantic neighbors must not displace precise symbol hits.
Use 20 candidates per retrieval path as an initial development value, not a hidden correctness cap
for phrase filters. Measure recall before increasing or reducing it.

Calibrate semantic acceptance with positive examples AND hard/no-answer negatives on a development
split; possible inputs are cosine score, top-score margin and query kind. A high cosine score is
not a probability. Freeze the rule before held-out evaluation. If no useful abstention policy
passes, leave semantic mode unshipped; do not choose a threshold merely to pass the four old cases.
Deduplicate sessions and select the passage that actually supports the result's displayed reason.

## 7. Scheduling, cancellation and resource policy

The following are initial engineering defaults/targets, **not measured production guarantees**.
Validate them on the reference corpus/hardware and record justified revisions before release.
Keep constants centralized and documented; do not scatter independent limits through UI/services.

| Resource | Starting policy |
| --- | --- |
| Lexical index | 128 MiB logical budget including retained text, folded copies if any, postings, positions, metadata and conservative object overhead. Do not count just UTF-8 source bytes. |
| Semantic index | 64 MiB logical budget for vectors plus associations/bookkeeping; shared text is charged once. At most 20,000 passages globally and 256 per session, with memory budget taking precedence. |
| Native runtime | Aim for at most 1.5 GiB peak RSS with the tested model. Supervise a 2 GiB stop threshold where reliable process accounting is supported; this is a sampled failsafe, not an OS hard memory limit. |
| Inference concurrency | One resident worker and one bounded native batch at a time. Start with two CPU threads and 512 total tokens per batch. The prototype's four-thread timings do not predict this configuration. |
| Background queue | At most 32 queued passage jobs, generated lazily. Do not materialize every full-history chunk before applying caps. Interactive query work has priority between batches. |
| Query vector cache | In-memory LRU, at most 32 entries, included in semantic memory accounting; clear on disable/model change. No query persistence. |
| Result count | Initially at most 100 displayed sessions with an explicit limit indication; filter before selection and preserve deterministic order. |
| Worker timeouts | Initial load 30 s, interactive query 2 s, background batch 10 s. On expiry, cancel/terminate as appropriate, report status, return BM25 and require explicit retry after a persistent failure. |
| Idle unload | Release the native model after five minutes with no query or indexing work. In-memory vectors can remain within budget. |
| Disable/dispose | Stop accepting work immediately; terminate the owned helper and clear semantic/query memory within two seconds as a target. Never stop another app's model process. |

Default indexing starts when semantic search is enabled, assets are ready and a relevant search
scope is requested. Prioritize current candidates and recently active sessions in open scopes;
index incrementally in the background. Do not eagerly embed every session machine-wide at IDE
startup. Selecting All Projects may expand work, and the UI must communicate that. Hidden or
out-of-scope content must not appear in results; filters always apply even if vectors are cached.

Pause is an application-wide indexing preference and survives restart. It stops background
embedding at the next safe boundary; cached passages remain available for interactive semantic
queries. Resume continues from available in-memory state. Disabling semantic search is stronger:
cancel requests, stop the worker and release all semantic state; keep downloaded assets on disk.
A separate Remove Downloaded Assets action removes only Seshlog-owned assets, never a user-selected
model, other tool's model cache or files under agent roots.

Schedule model load, background embedding and query embedding separately from lexical search.
A slow/dead worker cannot monopolize `ContentSearchService`'s existing single search executor.
Bound queues, prioritize active queries, coalesce duplicate chunks, and implement fairness across
project windows. Closing window A cancels A's delivery without cancelling window B's useful work.
Global settings changes/disable legitimately invalidate work for every window.

Check cancellation between extraction units, token batches, ranking loops and native responses.
Verify request + candidate + content + settings/model generations before applying results on EDT.
If native work cannot be interrupted safely, ignore its obsolete response, bound how long it may
run, and restart only the owned helper when necessary. No retry or crash-respawn loop.

Eviction must remove index entries and their statistics/associations coherently. Use LRU/recent-scope
priority, avoid constantly re-embedding evicted passages under pressure, and indicate incomplete
coverage. A restart loses the content/vector index by design. Settings must say rebuilding is
necessary; do not solve restart latency by silently persisting embeddings or extracted text.

## 8. Settings, setup, asset management and status UX

Add a Search section in **Settings → Tools → Seshlog**. Default search needs no setup.

Primary checkbox (default false):

> Enable semantic search (experimental)

Supporting text, adjusted only when new measurements justify it:

> Find conversations using related meanings. Runs locally. Requires a model download of about
> 334 MB, plus the local runtime. Our prototype used about 1.35 GB of additional memory.
> Background indexing uses CPU and may increase battery use. Larger histories take longer to
> index, and the search index is rebuilt after restarting the IDE.

The 1.35 GB value must remain labelled a prototype observation, not a RAM ceiling. Show the
actual selected download sizes for both model and helper before installation. Explain that
regular search remains available while semantic setup/indexing is incomplete.

Controls and states:

- `Download model and runtime…`: explicit setup action; show source/model/version, sizes, storage
  destination and applicable license information before starting. Do not start a download merely
  by applying the checkbox. Cancellation leaves default search usable.
- `Use an existing model…`: file picker for the supported GGUF; validate checksum/format off EDT.
  Treat it as externally owned and read-only. A compatible managed helper is still required.
- Status: Off, Setup required, Downloading, Verifying, Loading, Indexing, Ready, Indexing paused,
  Limited coverage, Unsupported platform, or Unavailable with a short reason and Retry action.
  Model availability, worker state and coverage are separate facts; do not collapse them into
  one ambiguous boolean or enumerate every possible combination in one giant state enum.
- Show progress as completed/current eligible passages or sessions with a changing-total qualifier.
  Do not invent a percentage or ETA for an unknown total. Keep status updates throttled.
- `Pause indexing` / `Resume indexing`, current resource/coverage summary where measurable,
  `Retry`, and `Remove downloaded assets…`. Avoid making users choose dimensions or BM25 constants.
- A compact status in the tool window, with a route to settings, explains partial/paused/unavailable
  semantic search. Do not show modal error dialogs on every query or silently switch modes.
- Plain query results may arrive before semantic refinement. No repeating reorder animation;
  keep keyboard focus, selected session, pinned behavior and scroll location stable.

Persist app-level preferences in `SeshlogSettings`: enabled (false), indexingPaused (false),
selected supported asset identity and optional existing-model path. Defaults for existing users
must remain false after an upgrade or malformed settings load. Runtime PID, progress, query text,
content hashes, vectors, request IDs and index associations are never persistent state.
Use `isModified`/apply/reset semantics correctly; test canceling the settings dialog. Download/remove
buttons are explicit asset operations and must not masquerade as deferred preferences.

### Managed assets and platform support

The production helper is a separately provisioned, pinned artifact. Do not assume Homebrew,
`llama-embedding` on PATH, `/dev/stdin`, Python, or a running external model server exists.
Package a small protocol-aware helper around the pinned runtime, with reproducible builds and
license notices. Keep model weights out of the ordinary plugin ZIP and ordinary test/CI path.

Initial target matrix: macOS arm64 and x86_64, Windows x86_64, Linux x86_64. Record the minimum
OS/CPU requirements and tested binary for each. Other architectures remain BM25-only with a clear
unsupported semantic message until a helper is explicitly supported. Do not label a platform
supported merely because the Kotlin code compiles there.

Use a fixed, versioned release manifest with allowlisted HTTPS artifact sources, per-asset sizes,
SHA-256 checksums, platform identity, helper protocol version and license references. Verify model
checksum against the pinned known model. Pin helper checksums in trusted plugin/release metadata;
a checksum downloaded from the same untrusted source alone is not an authenticity mechanism.
Support normal IDE proxy/cancellation facilities using verified platform APIs. No credentials or
local query information belong in these download requests. Do not automatically accept new terms.

Store assets under a Seshlog-owned subdirectory of the IDE system/cache storage, resolved through
supported `PathManager` APIs. Store only safe installation metadata there. Download to a unique
`.part` file in that directory, enforce size/time bounds, verify it, then atomically activate.
Reject archive traversal/unsafe symlinks if archives are used; prefer simple pinned assets.
Validate executable permissions/signing/quarantine behavior per OS. Never invoke a shell to
assemble commands from settings paths. Coordinate concurrent windows/IDE processes installing
into the same location. A canceled/failed upgrade must preserve the last verified usable asset.

Do not auto-update assets at startup. Upgrades are explicit, with compatibility verification and
safe rollback. Asset removal must stop owned users first, handle in-use files on Windows and
avoid deleting external files through symlinks. No model/runtime provisioning is permitted in
ordinary CI or normal `make check`.

Before distributing binaries/weights or implementing bundled downloads, verify the current model
terms, native dependency licenses, notices and JetBrains Marketplace requirements from primary
sources. Record exact links/versions/decision here. Earlier conversation statements about other
EmbeddingGemma versions are not a substitute for checking the actual chosen artifacts.

## 9. Retrieval and performance evaluation gates

### Expanded synthetic relevance set

Keep the original 44 cases as a smoke comparison; add a separate deterministic corpus with at
least 200 sessions, 150 positive queries and 50 no-answer/hard-negative queries. Include realistic
multi-topic conversations, duplicated boilerplate, large tool logs, near-identical sessions,
misleading titles, rare identifiers, paths, Unicode, mixed languages, spelling variations, stale
content, quoted phrases and content near extraction/chunk limits. Include multiple relevant
sessions for some queries. New examples must be synthetic, not lightly edited private transcripts.

Split by underlying scenario/session family into development and evaluation partitions, so near
copies do not leak across the split. Write relevance labels before tuning. Freeze the evaluation
partition. Manually review labels and ambiguous cases; do not use the embedding model being tested
to generate its own ground truth. Record dataset version/hash, tokenizer/scorer/chunker versions,
model checksum, seed, device, threads and limits with each report.

Report hit@1, recall@5, MRR@5 and nDCG@10 where graded labels exist, separately for query categories.
Report no-answer false-positive rate, exact-query regressions and coverage, not just one average.
Count all relevant sessions correctly when computing recall; the original single-answer hit@5
was not a general recall implementation. Preserve per-case results for synthetic IDs only.

Starting gates to approve the BM25 default on the frozen evaluation set:

- Exact identifier/path and quoted-query acceptance tests all pass; no mandatory phrase violations.
- No aggregate MRR@5 or recall@5 regression against the old literal baseline, and a clear improvement
  on natural-language queries. Review every lost top-one technical match before accepting a tradeoff.
- At least 90% of labelled exact technical queries rank a relevant session first; target at least
  90% recall@5 across positives. If the corpus shows the target is inappropriate, document why and
  adjust the implementation/evaluation design; do not delete difficult examples to pass.

Starting gates for semantic opt-in on the same candidates/coverage:

- At least five percentage points better recall@5 than BM25 on the paraphrase/synonym subset,
  with no exact-query behavior regression. Aggregate metrics alone must not hide category losses.
- No-answer false-positive rate at most 5% on the held-out negative set, with examples inspected.
  A setting labelled experimental does not excuse returning arbitrary neighbors for every query.
- BM25-only fallback is identical when semantic mode is off, unavailable or bypassed by query kind.
- A qualified semantic-only result always has valid passage evidence and a working navigation target.

These are development/release criteria, not guarantees about every user's search. If a gate fails,
record the failure and improve the feature or keep that phase unshipped. Do not mark the final
semantic milestone complete with a known failing gate.

### Resource and latency evaluation

Use an opt-in reproducible benchmark with 1,000 and 10,000 synthetic sessions, long-session/tool
stress cases, and cold/warm runs. Measure p50/p95 and peak memory over repeated runs rather than
publishing a single best timing. Run on at least one ordinary lower-memory supported machine as
well as the M1 Max reference, and record concurrent workload/CPU settings.

Initial targets: warm BM25 p95 under 100 ms at 10,000 synthetic sessions within the stated budget;
warm semantic query embedding + retrieval p95 under 300 ms; lexical first-result delivery under
500 ms when the candidate corpus is already indexed. Debounce and cold model/index load are
reported separately. Measure EDT responsiveness, stale-work cancellation, load/idle-unload times,
full versus capped coverage, per-passage indexing throughput, JVM heap and native RSS separately.
Do not describe model-startup latency as warm latency or compare capped semantic coverage with
uncapped lexical coverage as equal workloads.

Real-history benchmarks are opt-in, read-only and counts/timings only, using existing providers
and configured roots. They establish cost and robustness, not relevance without human labels.
No transcript exports, prompt excerpts, real paths/IDs in reports, or model/network calls outside
the local worker. Ordinary tests must not require personal data or downloaded weights.

## 10. Failure and lifecycle acceptance table

Each row needs a deterministic fake-based regression test, plus native/manual checks where noted.

| Event | Required behavior |
| --- | --- |
| Semantic disabled/default upgrade | No native process, download, model read or vector allocation; BM25 works. |
| Missing/corrupt/incompatible assets | Setup/unavailable status and BM25; no repeated automatic download/restart. |
| Canceled download/disk full/checksum mismatch | No partial asset activation, prior working install preserved, concise retry action. |
| Worker fails handshake/malformed output/NaN/zero vector | Reject response, sanitize error, release worker resources, retain lexical results. |
| Worker timeout or crash mid-index/query | Bounded shutdown, no IDE hang, no raw stderr leak; explicit retry after persistent failure. |
| Model exceeds resource policy | Stop/pause semantic work with reason, preserve BM25 and IDE responsiveness. |
| Transcript append/rewrite/truncate/delete | Invalidate affected passages/postings; no stale hit or orphan vector; retry transient reads. |
| Provider root/local title changes | Clear or rekey affected snapshots, refresh current query even if text is unchanged. |
| Query/scope/filter changes while native work runs | Old delivery rejected; current filters applied before ranking and again on receipt. |
| Two windows search; one closes | Surviving scope receives correct results; no cross-window cancellation or leaked registrations. |
| Settings disable/model switch while indexing | Invalidate generations, reject late results, stop/reload only the owned helper as needed. |
| Pause/resume | Stop new background work, maintain truthful coverage and cached search; no duplicate work flood. |
| IDE close/restart/plugin unload | Owned worker and queues released; preferences/assets retained; content/vectors rebuilt in memory. |
| Index eviction/empty result with incomplete data | Coverage remains visible; no claim that unindexed content has no match. |
| Semantic-only hit/tool entry beyond collapsed preview | Correct passage revealed without requiring a literal query match. |
| Source changes before navigation | Revalidate target, refresh/fallback honestly, never use stale offsets into unrelated text. |
| Unsupported OS/arch | Semantic setup unavailable with explanation; default plugin behavior remains supported. |

## 11. Diagnostics and user-facing documentation

Use existing bounded local diagnostics, with search-specific safe events/reasons as needed:
index revision/coverage, count of added/removed/evicted passages, extraction/index/inference duration,
worker lifecycle, download/install phase and byte counts, timeouts, cancellation, resource-limit
fallback and model/protocol identifiers. Use existing hashed references only when necessary.
Never log raw query strings, titles, prompts, replies, snippets, native inputs/outputs, environment
variables or unsanitized exception messages. Error reporting itself must not leak sensitive data.

Update README search semantics, quoting, filters, semantic opt-in/setup and performance/privacy
tradeoffs. Update settings help and tooltips, accessibility labels, and relevant release notes or
Marketplace description sources. Use synthetic screenshot fixtures if media changes are warranted;
follow `docs/marketplace/README.md`. Do not upload/publish media or release a build without a
separate user request. Keep the prototype report as historical evidence, labelled test-only.

The plan remains the execution checklist. Other docs explain implementation/evidence to users or
maintainers; do not fork an alternative active roadmap that contradicts this file.

## 12. Ordered implementation work items

All boxes below start unchecked. Existing prototype success does not complete a production item.
Use one commit per item. If an item needs multiple working sessions, record partial progress here
without checking it. Items 0–4 deliver the BM25 milestone. Items 5–12 deliver the optional semantic
milestone. Later phases may not retroactively make BM25 depend on native setup.

### 0. Freeze behavior and build the evaluation harness

- [ ] **Deliverable:** encode section 5 as explicit test cases; expand/split the synthetic corpus
  per section 9; add a common evaluator for production literal/BM25 and optional model methods.
- Add labels with multiple relevant sessions, hard negatives, exact/phrase categories and a frozen
  evaluation split. Include query cases with two phrases in different entries, a phrase crossing
  an entry boundary (must fail), an unmatched quote and all-stopword input.
- Verify metric implementations with hand-calculated rankings: zero hits, missing labels, multiple
  relevant sessions, ties, truncated top-K and no-answer cases. Reject duplicate IDs and invalid
  relevance references. Do not assert every retrieval method must get every labelled query right.
- Preserve the original literal baseline as a test-only adapter/snapshot before changing production.
  Keep the original 44-case report reproducible. Avoid relying on whatever new implementation
  happens to occupy `ContentSearchIndex` when comparing against the old algorithm.
- **Done when:** deterministic metric/fixture tests and `make check` pass; corpus/version/split and
  baseline results are recorded here; no model is needed for the ordinary evaluation tests.

### 1. Implement query compilation and pure lexical scoring

- [ ] **Deliverable:** pure retrieval query/tokenizer/BM25 components under `index/` (a subpackage
  is fine), plus immutable document statistics and ranking inputs. Keep IntelliJ types out.
- Implement ordinary-term scoring, mandatory quoted phrases, technical-token exact lane, title/
  local-title/path fields, aliases, body passage aggregation and stable tie behavior from section 5.
- Start `k1=1.2`, `b=0.75` and title/path/body weights 8/3/1. Tune on the development partition only; record the chosen
  constants, why they exist and their version. Never compare raw cosine directly with BM25 scores.
- Keep original source offsets. If token normalization changes character lengths, explicitly map
  normalized tokens back to source ranges; do not apply normalized offsets to original text.
- **Tests:** score examples with known numbers; rare vs common terms; length normalization; empty
  corpus; stopword-only queries; identifiers/path punctuation; Unicode/case folding; aliases not
  double-counted; phrase boundaries; huge repetitive tool output not overwhelming a title; finite
  scores; deterministic ties independent of map iteration; metadata-only matches.
- **Done when:** pure tests and `make check` pass and development-set behavior is documented.
  The default UI need not switch yet.

### 2. Replace the scan-per-query matcher with a bounded lexical index

- [ ] **Deliverable:** production in-memory inverted index using existing provider extraction,
  with atomic snapshots, memory accounting, metadata updates and content-stamp invalidation.
- Introduce the provider-aware internal key and query/result envelope. Update all constructors
  and consumers found with `rg 'SearchHit|ContentSearchIndex' src`; do not truncate scores to Int.
- Maintain consistent term statistics on insert/replace/delete/evict. Budget raw strings, source
  positions and posting/object overhead. Enforce limits during construction, not after allocating
  a full corpus. Index metadata independently of unavailable body extraction.
- Null stamps, failed reads and partial-provider coverage need honest states and retry behavior.
  Process cached entries before misses where appropriate to avoid eviction/re-extraction thrash.
- Preserve parent/subagent and continuation rules supplied by providers/index; do not count
  hidden continuation rows as extra independent relevant sessions.
- **Tests:** unchanged search avoids extraction/tokenization; append/rewrite/delete invalidates;
  local rename updates without file-stamp change; cross-provider ID collision; unknown stamp is
  retried; transient read failure recovers; eviction removes stats; budget never exceeds the
  documented accounting bound; cancel mid-rebuild preserves the prior coherent snapshot;
  zero hits still carry partial/error coverage.
- **Done when:** `make check` passes and a model-free benchmark demonstrates cached query work is
  postings/scoring, not repeated full-history extraction/tokenization.

### 3. Wire BM25 into the service, tree and passage navigation

- [ ] **Deliverable:** BM25 is the normal session search with the new hint, correct snippets and
  source navigation. Default settings do not create any semantic worker or asset activity.
- Update `ContentSearchService` to deliver envelopes, preserve per-scope cancellation and remove
  raw-query logging. Treat empty results, errors and partial results distinctly.
- Include corpus/title/settings revisions in `SessionTreePanel` deduplication and delivery guards.
  Keep filters before ranking, pins/group behavior, selected identity and scroll stable.
- Carry the winning entry/range into preview and full conversation actions. Preserve literal
  next/previous-match behavior separately from opening the chosen passage. Handle collapsed tools,
  title/path-only hits, truncated entries and changed sources without false highlights.
- Update tooltips, README search semantics and accessibility wording in this item so the UI never
  promises all-terms AND after the behavior changes.
- **Tests:** independent scopes; filters changed with an identical query; local title change;
  stale result rejection; rank order survives grouping; hidden/provider/date/worktree filters;
  metadata-only and body snippets; correct Unicode offsets; tool expansion; selection stability;
  zero-hit partial/error status. Extend the platform/UI tests named in section 4.
- **Done when:** `make check` passes and the BM25 portion of the manual matrix in section 14 is
  checked in `make run`. Record any manual check still owed instead of marking it passed.

### 4. Validate and prepare the BM25 milestone

- [ ] **Deliverable:** expanded held-out relevance report and resource measurements using production
  BM25, plus a release-ready default-search change independent of semantic infrastructure.
- Run the BM25 gates in section 9, inspect lost technical top-one cases, and fix implementation
  defects. If tuning changes, rerun development validation before consuming a new held-out split;
  document evaluation iteration rather than pretending repeatedly tuned cases stayed held-out.
- Compare old/new cold and warm timings and memory on identical retained coverage. Run with large
  synthetic histories, long tool outputs, multiple windows and rapid typing. Confirm no EDT work.
- Run `make check`, `./gradlew verifyPlugin`, and `make release` as appropriate for the completed
  production change. Inspect the ZIP: no model, helper, test-only benchmark or private data leaked in.
- **Done when:** BM25 relevance/resource/compatibility gates and relevant manual acceptance pass.
  This is an independently shippable milestone; do not publish or upload it without a user request.

### 5. Prove a resident, supervised native worker

- [ ] **Deliverable:** protocol-aware native helper and pure Kotlin client/supervisor with a fake
  transport for tests. Use the pinned 300M model first; changing model version is a new evaluation.
- Add reproducible native build instructions/manifests and dependency license notices. Do not use
  the test CLI runner as the long-lived service or parse human console logs for vectors.
- Implement handshake, tokenizer access, frame bounds, request IDs, cancellation, priority,
  timeouts, process ownership, bounded stderr handling and deterministic cleanup from sections 6–7.
- Verify mean pooling and prompt/tokenizer conventions against the pinned model/runtime using
  synthetic inputs. Validate normalized vectors and expected dimensions. Match retrieval quality
  against the CLI reference within a justified tolerance, not bit-identical floats across devices.
- **Tests:** fake success, reordered/stale replies, wrong dimensions, NaN/Inf/zero vectors, excess
  frames/output, blocked stdin/stdout, process exit, timeout, dispose, two callers and no prompt
  leakage in argv/logs/errors. Add a separate opt-in real-model worker smoke test.
- **Measurements:** true warm query p50/p95, load/unload time, RSS, sustained background cost,
  interference with lexical queries and responsiveness at two CPU threads/512 tokens.
- **Done when:** ordinary tests pass without a model; opt-in native tests and runtime gates pass
  on the first supported host. Record supported/untested platforms explicitly. Do not expose a
  functional-looking semantic setting before a usable provisioned runtime exists.

### 6. Implement explicit verified asset setup

- [ ] **Deliverable:** model/helper manifest, downloader/installer/remover and supported-platform
  resolver. Define storage ownership and on-disk asset layout before writing installer code.
- Build/package the helper for the section 8 platform matrix; verify minimum OS/CPU requirements,
  executable signing/quarantine and actual launch behavior. No opaque unpinned system command.
- Verify license/distribution/Marketplace requirements against current primary sources and record
  the resulting references/versions here. A license/source uncertainty blocks distributing that
  asset, not ongoing pure BM25 work.
- Implement explicit download/cancel, expected-size limits, checksum/protocol validation, temporary
  files, atomic activation, concurrent-install coordination, prior-version retention and safe removal.
- Support the pinned existing-model path as read-only. Reject unsupported models with a clear
  reason; do not load arbitrary GGUF architectures and hope the dimensions happen to match.
- **Tests:** local fake downloader fixtures, no network from off/startup/search/settings-open paths,
  interrupted download, disk failure, invalid hash, version mismatch, concurrent install, malicious
  archive paths if applicable, canceled upgrade, external file never deleted and Windows in-use case.
- **Done when:** `make check` passes; real setup/cancel/retry/offline operation is manually verified
  per supported OS. Model and helper download sizes/locations are accurate in the manifest/UI.

### 7. Implement bounded semantic indexing and its lifecycle

- [ ] **Deliverable:** APP-owned semantic index/scheduler using shared extraction snapshots and the
  resident worker, with complete in-memory lifecycle and coverage state.
- Implement tokenizer-aligned chunks, source ranges, stable versioned cache keys, append-aware
  reuse, metadata/title invalidation, exact vector scoring and per-session aggregation.
- Enforce the section 7 budgets/queue/caps during enumeration, including transient work. Prioritize
  active candidates/recent sessions, avoid evict/re-embed loops, and never serialize vector state.
- Implement pause/resume, disable, model replacement, scope removal, idle unload, restart and
  explicit retry. Reindex only affected content; retain valid cached work across ordinary queries.
- **Tests:** fake deterministic embeddings with known nearest sessions; multi-topic/overlap/tool
  chunk mapping; special tokens/Unicode/budget edges; append vs rewrite; deleted sessions; unknown
  stamps; title/model/chunker changes; eviction; bounded lazy enumeration of huge inputs; two scopes;
  pause/restart/disable and late response rejection; memory accounting and no persistent index files.
- **Done when:** `make check` passes; opt-in native indexing demonstrates incremental reuse,
  bounded memory and correct coverage with counts-only real-history diagnostics.

### 8. Implement semantic eligibility, abstention and hybrid ranking

- [ ] **Deliverable:** production BM25 + qualified semantic fusion, with exact-query protection and
  calibrated no-answer behavior. Replace the prototype's unconditional nearest-neighbor return.
- Define and test the technical-query classifier and quoted-query bypass. Apply scope/phrase
  constraints before top-K/fusion. Keep session deduplication and exact-evidence tiers deterministic.
- Calibrate candidate count/fusion weights/abstention on development data; freeze the policy and
  version it alongside model/chunker/tokenizer identity. Evaluate the held-out set once frozen.
- Audit regressions by category, including high-cosine but irrelevant neighbors, generic boilerplate,
  near-duplicate sessions and real identifier queries that embeddings might misinterpret.
- **Tests:** RRF with one/both/empty lists; duplicated passages; scope filtering before truncation;
  pure exact and mixed technical/natural-language queries; false-neighbor rejection; successful
  semantic-only match; zero semantic availability identical to BM25; no bogus score percentages.
- **Done when:** `make check` and semantic relevance gates pass, with report/version stored as
  synthetic/aggregate evidence. If abstention fails, do not enable a nearest-neighbor-only feature.

### 9. Complete settings and progressive search UI

- [ ] **Deliverable:** the section 8 checkbox, load explanation, verified setup controls, coverage/
  status, pause/resume/retry and removal behavior. No user needs command-line model tooling.
- Add app-level persistent settings with safe upgrade defaults. Separate desired enablement from
  asset availability, worker status and index coverage. Apply/reset/cancel must work correctly.
- Connect lexical-first then semantic-refined delivery, guarded by all relevant revisions. Make
  semantic source navigation work in preview/full viewer without literal matches. Preserve focus,
  selection, grouping and scroll when enrichment arrives or settings change.
- Show truthful disabled/setup/loading/partial/unavailable text; stop repeated dialogs and resource
  surprises. Disable releases resources; pause only stops background indexing as documented.
- **Tests:** settings serialization/default migration; invalid persisted values; apply/reset/cancel;
  no worker/download when disabled; explicit setup action only; all status combinations; lexical
  delivery during delayed inference; semantic-only passage/tool expansion; stale navigation source;
  keyboard/screen-reader labels; multiple windows observe applied settings without canceling peers.
- **Done when:** `make check` passes and the complete settings/search flow works in `make run`
  using synthetic sessions and the real local model. Screenshot tests use only synthetic content.

### 10. Harden failure handling, resource enforcement and privacy

- [ ] **Deliverable:** all section 10 failure rows covered, resource policy enforced and sanitized
  diagnostics useful enough to explain degraded search without logging content.
- Audit worker/process shutdown, frame/queue limits, asset ownership, interrupted downloads,
  disposal races, callback registration leaks and repeated startup failures. Verify no orphan helpers.
- Audit logs/exception paths/temporary files/argv and caches using synthetic marker strings. Check
  that a deliberately failing helper cannot echo the input into `idea.log` or test reports. Confirm
  only managed model/runtime files and safe asset metadata are written to the managed directory.
- Compare combined JVM + native behavior with realistic indexing. Enforce one worker across windows;
  exercise budgets and pause under pressure. On systems without reliable RSS sampling, document
  the limitation and use bounded native configurations; do not pretend a hard limit was enforced.
- **Tests:** deterministic fault injection for every failure row, including cancellation during
  disposal/model switch, unsupported platform, content changes between lookup/navigation and
  low-resource fallback. Native crash/timeout/exit tests remain opt-in if they require a real worker.
- **Done when:** `make check` passes, privacy/resource checks have recorded evidence, native failure
  tests recover to BM25, and the IDE remains interactive without a restart.

### 11. Complete cross-platform validation and documentation

- [ ] **Deliverable:** tested platform matrix, final quality/performance report, user-facing docs,
  accurate settings resource copy and release/asset manifests.
- Run the native setup/worker/lifecycle checks on every platform labelled supported; record OS,
  architecture, helper/model checksum, CPU configuration and outcome. Untested support is not passing.
- Repeat the final frozen-corpus comparison using production components, including BM25 fallback.
  Measure cold/warm latency, p50/p95, native RSS, JVM memory, index coverage and indexing throughput.
- Update README, settings hints/accessibility, release notes and any affected synthetic Marketplace
  assets. Keep changes scoped to search; do not rewrite unrelated product claims.
- Run `make check`, `./gradlew verifyPlugin`, `make release`, relevant opt-in benchmarks and the
  manual matrix below. Inspect plugin ZIP contents and asset distribution licensing/checksums.
- **Done when:** the validation records are filled, all relevant manual checks pass, known remaining
  limitations are accurately documented, and no mandatory semantic release gate is failing.

### 12. Final review and completion

- [ ] **Deliverable:** a clean, reviewable change set and an honest handoff showing both milestones.
- Re-read sections 1, 3, 5 and 8 against the actual implementation. Check default behavior on a
  clean IDE profile with no model, then the opt-in flow on a supported installation.
- Ensure no helper/model process starts when off; a user can download or select the model, enable
  the feature, see progress/load, find a semantic-only passage, pause, disable and reclaim memory.
- Confirm model/inference network isolation, in-memory content/vector state, exact phrase behavior,
  filters, read-only providers and unchanged resume/fork/restore actions.
- Record final commit IDs, commands, metrics, platform matrix and remaining optional follow-ups.
  Do not mark the whole plan complete if a supported platform or mandatory acceptance check is owed.
- **Done when:** all items and mandatory acceptance checks are complete. Publishing/deployment is
  a separate user-authorized action; preparing a verified release artifact does not upload it.

## 13. Commands, checkpoints and troubleshooting

```sh
# Inspect the actual current code rather than trusting old line numbers:
rg -n 'SearchHit|ContentSearchIndex|TextQuery|sourceMatches|groupRanked' src/main src/test
rg -n 'search|Search|semantic|Semantic' src/main/kotlin/com/hedworth/seshlog/settings

# Required normal test suite; semantic/native/real-data suites must remain opt-in:
make check

# Focused examples while implementing (adjust names for new tests):
./gradlew test --tests '*ContentSearchIndexTest' --tests '*TextQueryTest'
./gradlew test --tests '*ContentSearchServiceTest' --tests '*SearchRequestScopeTest'
./gradlew test --tests '*SemanticPrototypeTest'

# Existing prototype comparison, not proof that new production integration works:
SESHLOG_EMBED_MODEL="$PWD/build/semantic-models/embeddinggemma-300M-Q8_0.gguf" make semantic-bench

# Optional bounded local histories, read-only and aggregate output only:
SESHLOG_EMBED_MODEL="$PWD/build/semantic-models/embeddinggemma-300M-Q8_0.gguf" \
  SESHLOG_SEMANTIC_REAL=1 make semantic-bench

# Platform integration and release verification:
make run
./gradlew verifyPlugin
make release

git diff --check
git status --short
```

If Gradle cannot reach dependency services, use `./gradlew test --offline` or the corresponding
Gradle command with `--offline`. The existing semantic target already uses offline Gradle and a
local model. Gradle may need permission to write locks/caches outside the checkout; use the normal
sandbox escalation mechanism rather than changing agent storage permissions or recreating caches.

The existing semantic target writes `build/semantic-bench.log`; the original real-data run was
also saved locally as `build/semantic-bench-real.log`. These ignored files may be absent after a
clean. Do not rely on them as the only evidence or copy their real-data source material anywhere.
The committed prototype report records aggregate observations. Add corresponding opt-in targets
for the production worker/evaluator as implementation develops, and document them here.

When debugging tracking rather than search, follow the diagnostics instructions in `AGENTS.md`.
Do not change transcripts to manufacture failures. For search tests, use injected fake providers,
workers and synthetic files. Avoid a broad `idea.log` inspection unless a specific platform/worker
failure requires it; raw historical logs may contain unrelated sensitive information.

## 14. Manual acceptance matrix

Use `make run` with synthetic sessions first. Record IDE/build, OS/arch, helper/model identity,
configuration, pass/fail, and date. An unchecked box means unverified, not implicitly passing.

### BM25 milestone

- [ ] Clean/default profile: search works without model files, native binaries or network.
- [ ] Natural-language query finds a session despite one or more missing ordinary query words.
- [ ] Quoted and mixed quoted/unquoted queries obey section 5; no cross-message phrase matches.
- [ ] Exact symbol, filename, error code, path and Unicode queries navigate/highlight correctly.
- [ ] Title override, metadata-only session, tool-only result and large/partial history work honestly.
- [ ] Project/date/provider/hidden/worktree filters and pinned/grouped rows retain expected behavior.
- [ ] Rapid typing, clearing, filter changes, file updates and two windows never deliver stale results.
- [ ] Keyboard navigation, selection and scroll survive refresh; preview/full viewer opens the hit.
- [ ] Empty results distinguish complete no-match from partial/error coverage.
- [ ] No EDT stalls or excessive memory/repeated extraction at reference scale.

### Semantic milestone — repeat setup/worker rows on each supported OS/architecture

- [ ] Off by default after install/upgrade; no hidden download, worker start or model allocation.
- [ ] Resource explanation and exact model/runtime download sizes appear before explicit setup.
- [ ] Download cancel/failure/checksum mismatch/retry preserves a usable prior install or clean state.
- [ ] Existing supported model path works read-only, including paths with spaces/non-ASCII characters.
- [ ] Unsupported/corrupt model and unsupported platform produce useful status with working BM25.
- [ ] Enable and index: progress/coverage is accurate; lexical results remain responsive.
- [ ] Semantic-only query reveals the actual relevant passage with no fake literal highlighting.
- [ ] Unrelated query produces no semantic result under the accepted abstention policy.
- [ ] Exact/quoted queries retain lexical behavior; filters apply before semantic ranking.
- [ ] Pause/resume, idle unload, disable, re-enable and IDE restart behave as documented.
- [ ] Disabling releases native/semantic memory; downloaded assets remain until explicitly removed.
- [ ] Removing assets deletes only Seshlog-owned files; externally selected models remain intact.
- [ ] Native crash, timeout and resource pressure fall back to BM25 without UI hangs/restart loops.
- [ ] Concurrent windows share the worker; closing one leaves other active searches intact.
- [ ] Transcript/title/model changes reject stale results and reindex only the affected content.
- [ ] A changed/deleted source between search and click cannot open an unrelated passage.
- [ ] Offline inference works after setup; session/query data never enters an external request.
- [ ] Synthetic privacy markers absent from logs, temp files, argv and persistent index storage.
- [ ] IDE shutdown/plugin unload leaves no owned helper, worker threads or queued callbacks behind.
- [ ] Signed/verified helper runs on every supported platform; ordinary plugin ZIP contains no weights.

## 15. Decision and validation record — fill as work progresses

Keep concrete evidence here so the next session does not have to infer what happened from chat.
The first row is already established; later rows are intentionally pending.

| Area | Decision / evidence | Status |
| --- | --- | --- |
| Product direction | BM25 default; experimental local EmbeddingGemma opt-in with resource explanation; semantic off on upgrade. | Agreed |
| Prototype baseline | `e598a18`; measurements/caveats in section 2 and `docs/semantic-search-prototype.md`. | Complete, test-only |
| Production query/scorer versions and weights | Record tokenizer/alias rules, field weights, exact tier and corpus-statistics unit. | Pending item 1 |
| Evaluation corpus/split | Record fixture version/hash, seed, counts, label review and baseline report. | Pending item 0 |
| BM25 release gates | Record `make check`, held-out metrics, timings/memory and manual matrix results. | Pending item 4 |
| Helper protocol/build | Record protocol version, pinned runtime commit, build toolchains and checksums. | Pending item 5 |
| Distribution/licenses | Record primary-source links, checked versions, notices, hosting and explicit download design. | Pending item 6 |
| Supported platforms | Record actual OS/arch/minimum requirements and launch/lifecycle tests per binary. | Pending items 6/11 |
| Chunker/index policy | Record tokenizer budget/overlap, limits, measured memory and invalidation version. | Pending item 7 |
| Hybrid/abstention | Record frozen development policy, evaluation gains/negatives and exact-match regressions. | Pending item 8 |
| Settings/navigation UX | Record apply/reset/default tests and real-model sandbox acceptance. | Pending item 9 |
| Failure/privacy/resource audit | Record failure-matrix coverage, synthetic-marker checks and process cleanup evidence. | Pending item 10 |
| Final validation | Record exact commands, commit IDs, verifier/build outcome and unresolved optional limitations. | Pending item 12 |

### Definition of the requested final state

A user gets useful relevance-ranked BM25 search immediately, with exact phrases and technical
queries preserved. They can deliberately enable a fully local semantic enhancement from settings,
understand its extra load, provision the supported assets, see/pause indexing, navigate meaningful
results and turn it off to release resources. Search remains usable if semantic work is unavailable.
The implementation is tested, bounded, compatible with its advertised platforms and honest about
coverage; agent data remains read-only and content-derived indexes remain in memory.
