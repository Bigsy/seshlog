# Plan: BM25 default, external embeddings and a portable persistent cache

Prepared 2026-10-25. This is the authoritative implementation plan for the next work.
It replaces the worktree plan at the user's request. That plan, including unfinished manual
checks, remains in Git at `e598a18:PLAN.md`; replacing it does not mark those checks complete.
Do not resume unrelated worktree work as part of this search feature.

Updated direction: the first semantic release connects to a user-supplied embedding server.
Managed local inference is deferred, not a prerequisite. The earlier local-first plan is preserved
at `34363dc:PLAN.md`; section 16 records its later scope without blocking this release.
The user subsequently required persistent embeddings in the first version: use SQLite for a durable,
portable embedding cache and bounded in-memory vectors for retrieval. Do not rebuild embeddings
from scratch after every restart. Raw transcript text and lexical postings still remain in memory.

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
8. Do not claim the semantic feature is complete because a benchmark or fake HTTP server passes.
   The first release includes a real user-supplied server connection, secure settings, explicit
   data-sharing disclosure, bounded indexing, useful navigation and reliable BM25 fallback.
9. Do not build/download/package a managed local model or native helper in this release. Keep an
   embedding-client interface so a local implementation can be added later without rewriting ranking.

### What the user has agreed

- Make BM25 the default session-search ranking method; it remains fully local and needs no setup.
- Add optional experimental semantic search, disabled by default, using the user's embedding server.
- Ship external-server support before attempting managed local inference. The server can run on
  localhost or another machine; Seshlog connects to it but does not install/start/manage it.
- Settings need the server base URL, model name, optional API key, Test connection and clear disclosure
  that query text and conversation passages/titles will be sent to the configured endpoint.
- The server handles model CPU/RAM. Seshlog extracts/chunks text, persists reusable embeddings
  in SQLite, and loads relevant vectors into a bounded in-memory search index.
  If the server runs on the same machine, its resource load still affects that machine.
- Keep exact phrases/technical identifier searches reliable and BM25 available on server failures.
- Ship BM25 first, then the server-based semantic option after its operational/relevance gates pass.
- Persistent cache reuse across restarts and export/import into another IDE instance are required
  in the first semantic release. Regenerate embeddings only for missing/changed/incompatible inputs.
- Existing default-local/privacy rules now have a narrow user-configured, explicitly enabled server
  exception. This does not authorize sending private histories to arbitrary servers during development.
- This planning request does not ask for a new implementation during the planning turn.

### Out of scope for this release

Managed local inference, model/runtime downloads, native helper builds/distribution, automatic server
installation/startup/model pulling, built-in vendor-hosted inference, telemetry, transcript modification,
automatic cloud sync of embeddings, persisted raw-content/lexical indexes, generated answers/summaries,
RAG/chat, rerankers,
arbitrary embedding-model profiles, extraction-limit changes and unrelated session/worktree work.
Do not require Lucene, a vector database server, Python, Ollama or an external account for default
search. Reuse the existing sqlite-jdbc dependency for a local embedding cache; no SQLite vector
extension is needed because ranking happens over in-memory float arrays.
Support one documented embedding HTTP contract initially, rather than every server's proprietary API.
Keep the evaluated EmbeddingGemma 300M prompt/dimension profile; a configurable model name permits
server aliases, not an unsupported promise of quality/compatibility with every embedding model.

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
- Raw conversation/tool text, snippets, BM25 postings/statistics and query caches remain in memory.
  The user explicitly authorized a durable SQLite cache of embedding vectors, content hashes, model/
  profile identity and minimal source references. This is a narrow persistence exception, not
  permission to store full transcripts, query histories or HTTP bodies. Embeddings/hashes are
  sensitive derived data, not anonymization. Persist them only in Seshlog-managed IDE storage or
  an explicit user-selected export; never in agent roots or the repository.
- Preferences belong in IDE settings; API keys belong in IntelliJ PasswordSafe or the supported
  secure credential API, never `seshlog.xml`, project files, logs, environment variables or Git.
  Existing transcript parse caches remain metadata-only. No model/runtime assets are installed.
- BM25/default/off state makes no embedding-server requests, including health/model-discovery probes.
  Explicit Test connection uses synthetic strings only; it does not upload any real session content.
- Real text may be sent only to the currently configured, applied and explicitly enabled endpoint,
  after the data-sharing disclosure is accepted for that configuration. No vendor fallback endpoint.
  Changing the endpoint/model/auth context revokes old authorization and cancels queued requests;
  reapply the reviewed configuration before further real text is sent. Never persist a credential
  value/hash in the consent record; use an opaque settings/credential revision.
- An external server may retain inputs or forward them elsewhere. Do not promise otherwise, even
  for localhost. Seshlog persists the disclosed embedding cache, not raw text; it cannot enforce
  server retention or retract inputs that have already been sent.
- Do not send transcript paths, session IDs, environment variables or unrelated metadata as request
  fields. Title/content can themselves contain sensitive material; include titles in the disclosure.
- No request/response bodies, auth headers, API keys, raw query text, snippets or embeddings in
  logs/diagnostics/exception messages. Remove the current raw-query DEBUG log in ContentSearchService.
- All extraction, scoring, hashing and HTTP work runs off EDT. Rendering consumes immutable snapshots.
- Keep scoring/lifecycle pure/testable; inject extractors, stamps, executors, clocks, HTTP transport,
  credential access and connection-state probes rather than mocking IntelliJ classes.

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
  phrases. If an otherwise valid query exceeds the supported model profile's input budget, run BM25 only
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
- If semantic setup is missing, indexing is partial/paused, the server is unavailable, or a request times
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

### Embedding client, HTTP contract and active in-memory index

Define a small `EmbeddingClient` interface independent of ranking/UI: validate/test configuration,
embed query/documents, cancel, and close. Implement an HTTP client for this release. Inject a fake
transport for tests; a managed local client is a later alternative behind this same seam.

**Protocol v1:** accept an OpenAI-compatible embeddings base URL, such as
`http://localhost:11434/v1`, and POST to its `embeddings` child path. Label the field Base URL.
Preserve an explicitly supplied reverse-proxy path prefix and normalize trailing slashes once.
Reject a full `/embeddings` URL with corrective help rather than guessing/appending it twice.
Do not automatically probe arbitrary routes, install a server, pull a model, or use a cloud fallback.

Request shape (model name is the user's configured alias; strings below are synthetic):

```json
{
  "model": "embeddinggemma",
  "input": ["task: search result | query: synthetic connection check"],
  "encoding_format": "float"
}
```

Use JSON encoding, Content-Type application/json and an optional Bearer Authorization header.
No SDK that silently adds telemetry, retries or a provider endpoint is required. Inspect the IDE's
supported HTTP/proxy and credential APIs before choosing the implementation; cancellation, response
size limits and controlled redirects are mandatory regardless of the library.

Expect an object with `data`, each row carrying an integer `index` and numeric `embedding` array.
Reorder by index; require exactly one unique row per input, no gaps/duplicates/out-of-range indexes,
768 finite numeric values per row, and a nonzero norm. Normalize client-side before scoring.
Reject shape/model-contract errors without echoing the response. Handle a missing optional model
identifier; if a reported identity changes unexpectedly, invalidate and require a compatibility test.
Do not send `dimensions`, token arrays, streaming flags or proprietary fields in this initial adapter.

An explicit connection test sends a small fixed synthetic single-input and batch probe. It verifies
URL/auth/API availability, row ordering, vector dimensions/norms and basic retrieval consistency.
It cannot prove the remote weights are EmbeddingGemma, prove retention policy, or guarantee absence
of server-side truncation. State this limitation. The server operator must configure the documented
EmbeddingGemma 300M model/profile. Use an immutable model tag/revision where the server supports it.

Apply the model's query/document prefixes exactly once in Seshlog; verify the supported server does
not add a second retrieval prefix. Start with the already measured 320-byte body windows, 40 code
points overlap and 128-byte title prefix cap; each formatted input is at most 480 UTF-8 bytes.
This conservative profile stays below the model's context even without a tokenizer endpoint.
Do not make an undocumented tokenization endpoint a prerequisite. Longer token-aware chunks can
be evaluated later. Valid lexical queries over the semantic budget remain BM25-only with status.
Preserve original UTF-16 source ranges and entry IDs; excluded/coverage entries stay excluded.

Use one APP-owned client/scheduler shared across windows. This is an HTTP client, not a server
process owned by Seshlog. Never kill, unload, retune or delete a user's external server/model.
Start with one background request in flight and one separately available interactive request slot,
with priority/coalescing and a combined ceiling of two concurrent requests to the configured server.
Validated vectors and source associations are committed to the durable SQLite cache, then loaded
into the bounded active in-memory IDE index. SQLite stores/reuses vectors; similarity ranking stays
in Kotlin. The server computes embeddings, not a remote vector database or search index.

Store normalized 768-dimensional float vectors initially. Use exact dot-product retrieval over
the bounded local set; do not add ANN/database infrastructure until measurements require it.
Deduplicate passages and aggregate to session hits before top-K/fusion. Query and document vectors
must come from the same effective server/model/profile revision.

Separate cache identity from connection authorization. The durable vector key is compatible
model/profile identity plus SHA-256 of the exact fully formatted UTF-8 embedding input (including
title/prefix). Source links also include provider/session key, content stamp, entry/range and source
revision. Connection generations include URL/model/auth and decide whether requests may be sent;
API keys or credential hashes must never become cache keys or exported metadata.

A URL/credential change cancels pending work and revokes current send authorization. It does not
needlessly delete reusable vectors: mark the profile unverified until the newly approved server
passes compatibility checks. Reuse across a changed endpoint only when the declared model/profile
and recorded synthetic-probe vectors agree within a documented tolerance. Same name/dimensions
alone are insufficient. Remote APIs cannot generally attest a weight checksum; use one when
available, immutable revisions when available, plus synthetic probe comparison. Store synthetic
probe vectors/profile metadata in the cache for restart/import validation. This is evidence of
compatibility, not cryptographic proof of model identity or a retention guarantee.

Append-aware durable reuse embeds only new/changed inputs. A restarted IDE may rescan/reparse source
files locally and send small synthetic compatibility probes, but unchanged valid document inputs
must result in zero document-embedding requests. Unknown stamps require local revalidation and
input hashing rather than automatic re-embedding. Reject late responses after settings/content/
scope changes. Revalidate source mappings before navigation; never trust imported absolute paths.

Primary protocol reference checked 2026-10-25:
[Ollama embeddings compatibility](https://docs.ollama.com/api/openai-compatibility#v1embeddings)
documents the `/v1/embeddings` route with model and string/array inputs. Its native
[`/api/embed`](https://docs.ollama.com/api/embed) route has a different response and supports native
truncation controls; **it is not the protocol implemented here**. Verify actual server/version
behavior with synthetic probes and the opt-in smoke test before advertising compatibility.

### Durable SQLite cache, restart reuse and portability

This is a required first-release component. Use the existing `org.xerial:sqlite-jdbc` dependency
in build.gradle.kts, plain tables and float BLOBs; no vector extension, external DB service or FTS
copy of conversation content. `EmbeddingCacheStore` should be a pure/injectable store behind the
APP search/index services, accessed on background workers, not by Swing rendering.

**Location/ownership:** default to a dedicated durable Seshlog directory under the IDE config/data
location, conceptually `${IDE_CONFIG}/seshlog/semantic-cache/cache.sqlite`, resolved with supported
PathManager APIs. It must survive process restart, plugin updates and Invalidate Caches; do not use
`build/`, temporary storage, project workspace, agent roots or the transient IDE system-cache tree.
Verify the chosen directory is excluded from automatic IDE Settings Sync and plugin settings export;
these content-derived files must not silently sync. Use explicit cache export/import for movement.
Document the exact resolved path and an Open cache location action. Different IDE profiles may
have different default paths; export/import is the supported way to transfer, not a shared network DB.
Apply user-only file permissions where supported. Do not claim an ordinary SQLite file is encrypted.

Initial schema (names illustrative, field meaning and constraints mandatory):

| Table | Required data / invariants |
| --- | --- |
| `cache_meta` | Schema/export-format version, random cache ID, creation timestamp; migrations controlled by this plugin. |
| `profiles` | Model family/name/revision, known checksum if available, dimensions, prompt/chunker/input-format versions, float encoding version, fixed synthetic probe vectors/fingerprint. No credentials or send authorization. |
| `embeddings` | Profile ID + SHA-256 input hash as unique key; normalized float32 BLOB, dimension and last-used/created metadata. Validate exact byte length (`dimensions * 4`), byte order, finite values and norm on write/read/import. |
| `sources` | Provider kind + stable session ID, provider-relative source locator/root reference where necessary, content-stamp representation/version and source validation revision. Avoid absolute paths in portable exports. |
| `passage_links` | Source/profile/revision + entry ID + original UTF-16 range -> input hash, plus truncation/coverage metadata. Foreign keys prevent a link to a missing embedding. |

Input hashes include every part influencing the vector. A title/prefix/chunker/model change cannot
reuse a mismatched vector. Deduplicate identical inputs within a compatible profile, independently
of session IDs, so an imported/moved source can reuse vectors after locally rebuilding its links.
The database must contain no prompt/reply/tool text, snippet, user query, token/posting corpus,
request/response body, API key or persisted transmission permission. Existing metadata parse-cache
rules remain unchanged. The synthetic probe strings are fixed public test data, not real content.

**Startup/reuse:** open/validate/migrate the store off EDT; enumerate metadata lazily. If semantics
is off, do not load the vector working set or contact a server. If enabled, match discovered local
sources, recheck stamps/profile compatibility, then load relevant persisted vectors into the memory
budget. Metadata/schema reads and local re-hashing are allowed; re-embedding every document is not.
Fetch vectors from disk on scope change/memory eviction before scheduling any HTTP work. A cold
memory miss is not a cache miss. Only missing/changed/incompatible inputs go to the server after
normal authorization/scope checks. Commit each validated result batch promptly so crashes/restarts
lose at most in-flight work, not the whole indexing run. Query embeddings/cache remain memory-only.

**Transactions/concurrency:** use prepared statements, foreign keys, versioned migrations and a
single bounded writer per store. Use short transactions and a bounded busy timeout; WAL mode is
appropriate for this local database if supported by the chosen JDBC setup. Never hold a transaction
open during HTTP calls. Atomically publish source revision + passage links only after input/content
generation checks pass. Checkpoint/compact in bounded background maintenance, not on the EDT.
Multiple windows share the APP store. If two IDE processes access the same path, coordinate migration,
import, purge and writes; one cannot delete/replace a live database under another. If exclusive
maintenance cannot be acquired, fail that action safely and explain how to close the other instance.
No live SQLite database in a network/cloud-synced folder; transfer closed consistent snapshots.

**Disk and memory are different budgets:** start with a 512 MiB persistent cache budget and at most
100,000 vector rows across profiles, while the active set keeps the separate 64 MiB/20,000 passage
limit. Include indexes/links/WAL and bounded transaction/maintenance headroom in disk accounting.
Memory eviction retains durable entries; it must not trigger server recomputation next time.
Disk pruning first removes orphan/obsolete-profile entries, then least-recently-used inactive rows;
show limits/coverage honestly. Avoid immediate re-embedding of disk-evicted inputs in a tight loop.
A missing provider root during an outage is not proof its cache should be deleted. Reconcile truly
deleted sources and unreferenced vectors with bounded GC. Hide/scope filtering affects eligibility;
it does not silently erase portable cache data. Clear saved embeddings is the explicit purge action.

**Failure behavior:** disk full/read-only/locked/corrupt/unsupported-schema cache must not break BM25.
Keep existing usable rows where possible; pause new document embedding if results cannot be saved
rather than spending network work repeatedly with no durable result. Provide retry/change-location/
clear-or-recover guidance appropriate to the actual failure. Never silently delete a potentially
recoverable database and re-upload every transcript. Migrations are transactional with a recoverable
previous version; a newer unsupported schema opens unavailable/read-only rather than being downgraded.
Disable semantics releases in-memory state and stops traffic but retains the durable cache. Clear
saved embeddings explicitly removes this store and its sidecars under coordination; explain that
future indexing will resend passages, and that this does not delete anything at the external server.

**Portable export/import, required in v1:** Export embedding cache creates a consistent, self-contained
SQLite snapshot with schema/profile/encoding metadata and portable source references. Use SQLite's
online backup API or `VACUUM INTO` through a verified JDBC path; never copy only the live main DB
and ignore its WAL. Strip connection endpoints, credentials, local absolute paths and consent records
from the portable format. Include synthetic compatibility probes and vector/input-hash associations.
Exports are sensitive derived data. Save only to the user's explicitly chosen destination; no
background sync/upload. Do not write into an agent data root even if selected.

Import opens the selected snapshot read-only, treats it as untrusted data, validates format/schema,
row/vector/size bounds and referential integrity, then merges compatible entries transactionally
without overwriting the existing good cache. Do not execute imported SQL, accept arbitrary views/
triggers/extensions, follow stored paths or restore credentials/enablement. Reject incomplete live-WAL
copies, newer incompatible schemas and corrupt profiles with useful safe errors. Stage work locally
inside managed storage; bounded cancellation/failure must leave the existing store usable.

After import, require a configured/authorized compatible server before query inference. Match local
sources using discovered provider IDs and relative references; moved/missing sources are unresolved
until matched, not opened from stored foreign absolute paths. Locally re-extract/hash when necessary
and reuse matching vectors without document HTTP requests. An export does not include the original
conversations: sessions absent on the new instance cannot be displayed/resumed just from embeddings.
Revalidate server/model/probe compatibility; imported vectors do not establish connection permission.
Record counts for reused/new/unresolved/incompatible entries and let the user inspect cache status.

Test portability by exporting during concurrent synthetic writes, importing into a fresh IDE store
with relocated synthetic source roots, and proving zero document re-embeddings for unchanged inputs.
Allow the small synthetic compatibility probe and new query embedding separately in that assertion.
If model/profile differs, keep incompatible cached profiles inactive rather than mixing vector spaces.

References checked 2026-10-25: [SQLite backup methods](https://www.sqlite.org/backup.html) describes
consistent snapshots and VACUUM INTO; [WAL documentation](https://www.sqlite.org/wal.html) explains
sidecars, checkpoints and local-host constraints. Verify the concrete JDBC APIs, lock strategy and
safe schema migration path against the already bundled driver before implementation.

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

These are starting engineering targets, not measured server guarantees. Keep limits centralized
and record measured revisions. The old ~1.35 GB observation belongs to the local model process,
not this plugin's HTTP client. Server hardware, request latency and model retention are external.

| Resource | Starting policy |
| --- | --- |
| Lexical index | 128 MiB logical budget including text, postings, positions, metadata and conservative object overhead. |
| Active semantic index | 64 MiB for vectors/associations; shared text charged once. At most 20,000 passages active globally and 256 per session; memory budget takes precedence. |
| Persistent embedding cache | 512 MiB initial disk budget, at most 100,000 vector rows; disk entries outlive memory eviction and restart. Account for SQLite indexes/WAL and bounded maintenance headroom. |
| HTTP concurrency | At most two requests: one background batch and one interactive query; global across windows. |
| Batch/queue | Up to eight document inputs/request and 32 lazily queued passage jobs. Each formatted input at most 480 UTF-8 bytes. |
| Payload limits | At most 64 KiB request JSON and 1 MiB decompressed response; enforce before unbounded allocation. |
| Query vector cache | In-memory LRU of at most 32 entries, included in semantic accounting, invalidated with endpoint/model/auth changes. |
| Result count | At most 100 displayed sessions initially with a limit indication; filter before top-K. |
| Timeouts | Connect 3 s, interactive query total deadline 2 s, background batch 30 s, explicit synthetic connection test 30 s for a server cold-load. |
| Retry/backoff | No automatic interactive retries. At most one background retry for transient transport/503 errors, with bounded jitter; 429 honors Retry-After within a 60 s cap and pauses/coalesces work rather than spinning. |
| Persistent failure | After three consecutive transient failures stop automatic indexing; show Unavailable/Retry. 401/403, invalid shape and incompatible model fail immediately until settings/retest. |
| Disable/dispose | Cancel local requests/queues and clear vectors/query memory promptly (target two seconds). Cancellation cannot retract data already delivered or guarantee server computation stopped. |

Index only after applied opt-in/configuration authorization and a compatible synthetic connection
test. Prioritize current search candidates and recent sessions within requested scopes. Never eagerly
upload all histories at IDE startup. Index scope defaults to the currently selected project/filter
scope; All Projects or widening filters explicitly expands the disclosed indexing scope. Recheck
current eligible candidates and authorization immediately before sending a queued batch. Removing
scope/hidden sessions cancels unsent work and makes cached vectors ineligible; no stale queued
upload may escape after a scope restriction takes effect.

Pause is an app-wide indexing preference persisted across restart. It stops new document uploads;
cached passage vectors remain searchable, which can still send an interactive query to the server.
Make this distinction clear. Disable stops all new embedding traffic and clears active in-memory semantic state, retaining
the durable cache. Clear saved embeddings is a separate explicit action.
There is no model removal/unload action: Seshlog does not own the server or its model assets.

Lexical search has its own executor; slow HTTP cannot monopolize it. Coalesce duplicate passage
work, debounce index-driven refreshes and ensure fairness across windows. Closing A cancels A's
delivery/unsent exclusive work without canceling B's useful work. Shared global settings changes
invalidate every affected request. An HTTP cancellation is not proof that the server received no data.

Check generation/cancellation during extraction, chunking, scoring, queue dispatch, HTTP receipt and
EDT delivery. Reject out-of-date settings/model/content responses even if cancellation lost a race.
Enforce memory limits before constructing all chunks. Evict coherently and avoid a resend/evict loop;
coverage must reflect unavailable/unindexed passages. Restart reloads validated cached vectors
from SQLite; rebuilding memory must not resend unchanged document inputs. Persist results batch by
batch, including partial indexing progress, and distinguish disk misses from in-memory misses.

## 8. Server settings, disclosure, credentials and status UX

Add a Search section to **Settings → Tools → Seshlog**. Default BM25 requires nothing. The first
semantic release exposes only Use my embedding server; do not display a nonfunctional Run locally
option or a download button. Managed local inference is a later backend in section 16.

Primary checkbox, default false: **Enable semantic search (experimental)**.

Fields/actions:

- Base URL: explicit user value; placeholder `http://localhost:11434/v1` is not an active default.
- Model name: default suggestion `embeddinggemma`; allow a server alias for the supported 300M profile.
- Optional model revision/tag when known; document that a model name alone may be mutable.
- Optional API key: masked editor backed by PasswordSafe; never XML/project storage. Support no-auth
  localhost servers without requiring a dummy key. Credential removal/replacement is explicit.
- Test connection: sends only the fixed synthetic probe to the draft URL/model/auth values. It does
  not save settings, enable indexing, upload histories or broaden an existing authorization.
- Explicit acknowledgement beside Apply/enable: **Send search queries and conversation passages,
  including titles, to this server.** Show the reviewed destination and current indexing scope.
- Pause indexing / Resume indexing, Refresh index, Retry, Export cache, Import cache, Open cache
  location and Clear saved embeddings, plus status/coverage and disk/memory usage. Refresh locally
  revalidates/reuses valid rows; forced re-embedding must be a separate clearly named action. Paused
  indexing can still send search queries for already indexed passages; disabling stops all requests.

Suggested supporting copy:

> Uses your embedding server to find related conversations. Search queries, conversation passages
> and titles are sent to the server you configure. Its logging and retention depend on its settings.
> Seshlog saves embeddings and source references locally, and loads them into memory for search.
> Unchanged passages are reused after restart. New or changed passages use network bandwidth and
> local processing; a cache export lets you reuse embeddings in another IDE instance. The server handles model memory and CPU; if it runs on this
> computer, that load still affects this computer. Regular search remains available without it.

Do not claim this mode is always local/private/offline or that disabling deletes anything already
received by the server. Do not display the local prototype's 334 MB download/1.35 GB RAM as client
requirements. An informational setup link may discuss running the supported model, but Seshlog
must not execute installation/pull commands or automatically start a user's server.

Preferences in SeshlogSettings: enabled false, indexingPaused false, base URL, model name/profile,
optional revision, and a non-secret acknowledgement/configuration revision. Store only an opaque
credential reference in ordinary settings; the key stays in PasswordSafe. Persist the applied
configuration acknowledgement in settings so an unchanged configuration survives restart. Keep
draft test results, active connection validation and indexing progress in memory. Store reusable
model metadata/synthetic probe fingerprints in the cache, never API keys or send permission.
Credential storage failure must not silently write a plaintext fallback; report it, keep semantic
mode unavailable and retain BM25.

Apply/reset/isModified/cancel must work without mutating the active connection while editing.
Endpoint/model/credential changes cancel the previous generation and mark cached profiles unverified; applying
requires the disclosure acknowledgement for the new destination/context. Test successes apply only
to the exact tested draft revision; changed fields invalidate the draft result. Settings broadcasts
and indexing-progress broadcasts must be separate to avoid rescans/reconnections on every update.

Statuses: Off, Setup required, Testing connection, Connection ready, Indexing, Ready, Indexing paused,
Limited coverage, Rate limited, or Unavailable with a concise reason and Settings/Retry action.
Track desired enablement, authorization, connection compatibility and coverage independently.
A 200 response alone is not readiness. Throttle progress and describe unknown/changing totals
honestly. Use compact nonmodal tool-window status; preserve focus, selection and scroll on refinement.

### URL, transport and credentials rules

- Accept a user-supplied HTTP(S) base URL only. Reject userinfo, fragments, query parameters, control
  characters, missing host, invalid ports and unsupported schemes. Tokens belong in the key field.
- Prefer HTTPS and validate certificates/hostnames normally. Do not add trust-all TLS. Support the
  IDE's documented proxy/trust configuration without logging credential-bearing exceptions.
- Plain HTTP is allowed by default only for literal loopback addresses or localhost validated as
  loopback at connection time. A private/LAN host is an intended valid destination, not a blanket
  SSRF rejection. Non-loopback HTTP requires an explicit per-endpoint Allow unencrypted HTTP choice
  with disclosure that request contents/authentication can be observed on that connection.
- Reject all redirects initially, including 307/308. Do not replay bodies or Authorization headers
  to a different host/path because a server redirects. Show a sanitized update-base-URL message.
- Never discover/replace the configured server from transcript content, model output or an error
  body. Never follow response-provided URLs. DNS/proxy behavior must not silently grant a weaker
  transport policy; test loopback classification, IPv6 and hostname changes carefully.
- API keys are sent only in Authorization to the configured endpoint. Redact URLs/auth in logs;
  the settings UI can show the configured destination for user review. Do not retain raw error
  bodies: they may echo prompts or secrets. Report status code/reason and safe instructions only.
- Use a disposable/cancellable HTTP client shared at app scope. Do not add a listening endpoint,
  launch another application or try to manage remote server limits. Rate-limit our own traffic.

### Compatibility and availability

The first tested contract is OpenAI-compatible `/v1/embeddings` with the EmbeddingGemma 300M
profile. Document actual tested servers/versions (start with a user-run Ollama-compatible endpoint),
not a promise that every vaguely compatible server works. The operator supplies compatible weights
and controls retention/security. Different model families are future profiles requiring their own
prompt/chunking/relevance checks, not an arbitrary dimensionality dropdown.

HTTP support is JVM-side on the plugin's supported IDE OS/architectures; there is no native-helper
matrix in this release. Still test credentials, TLS/proxy, IPv6, cancellation and shutdown on macOS,
Windows and Linux. Server CPU/GPU details affect remote performance, not plugin binary packaging.

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
server/version, model/profile/revision (checksum only if known), seed, device/threads when known,
transport conditions and indexing limits with each report.

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
lexical first-result delivery under 500 ms when indexed. Target warm semantic end-to-end p95 under
500 ms against a documented responsive reference server, with local scoring p95 under 50 ms at
the 20,000-passage cap. Record HTTP round-trip, server cold load, indexing throughput and transfer
bytes separately. Network latency/remote hardware are not under Seshlog's control; a slow or
unavailable server must not delay lexical delivery or hang the UI.

Prove restart reuse: after successful indexing, close/reopen a fresh client/store and assert zero
document-embedding requests for unchanged sources. New query and fixed synthetic profile-probe
requests are counted separately. Repeat after export/import with relocated roots; measure cache
load time, disk bytes, hits/misses and changed-input request counts. A matching stamp with corrupt
vector data must be rejected safely; unknown/different stamps may trigger local parsing, not
automatic retransmission. These are mandatory first-release gates.

Measure cancellation, retry/backoff, rate limiting, JVM heap and partial coverage under identical
candidate/extraction coverage. If measuring server CPU/RSS, label it as server-side (and include
it in same-machine impact when appropriate). Do not reuse the local CLI timing as the HTTP client
benchmark or compare capped semantic coverage with uncapped lexical search as equal workloads.

Real-history benchmarks remain opt-in, read-only and aggregate-output-only. The existing CLI
benchmark stays local. New HTTP tests use synthetic data by default, even on localhost. Sending
real histories to any configured test server requires explicit approval for that destination and
scope; a development URL, API key or Test connection click alone is not such approval. They measure
cost/robustness, not relevance without independent labels. Normal tests need neither real histories,
credentials, internet access nor a downloaded model.

## 10. Failure and lifecycle acceptance table

Each row needs deterministic fake/loopback HTTP tests and manual checks where indicated.

| Event | Required behavior |
| --- | --- |
| Semantic disabled/default upgrade | No embedding requests or probes; no semantic allocation; BM25 works. |
| Missing/unreviewed URL/model/credentials | Setup required; no private-text requests; BM25 remains usable. |
| Test connection before enable/Apply | Only fixed synthetic probes reach the draft endpoint; active settings/consent are unchanged. |
| 401/403 or credential store failure | Stop retries, redact errors, show settings action; never persist a plaintext fallback key. |
| 404/incompatible protocol/model | Reject configuration with safe explanation; no guessed endpoint or vendor fallback. |
| Redirect/TLS failure/unapproved plaintext | Do not replay body/auth or disable verification; show corrective settings action. |
| 429/503/transport failure | Bounded backoff/circuit behavior; no request storm; lexical queries stay fast. |
| HTTP timeout/server dies mid-response | Cancel client work, bound resources, reject partial vectors; BM25 remains available. |
| Oversized/decompression-bomb/malformed JSON response | Enforce byte/shape bounds and sanitized failure before unbounded allocation. |
| Missing/duplicate indexes, dimension mismatch, NaN/Inf/zero vector | Reject whole batch; never attach vectors to the wrong source passage. |
| Endpoint/model/auth/profile changes | Revoke old request generation; mark saved profiles unverified and reuse only after compatibility checks. |
| Same-name model replacement/reconnect fingerprint changes | Revalidate/rebuild, never mix incompatible vectors; explain limits of remote identity checks. |
| Transcript append/rewrite/truncate/delete | Reindex affected passages, no stale association; recover from transient reads. |
| Provider root/title/filter changes | Refresh eligible candidates, invalidate affected entries and unsent out-of-scope work. |
| Query changes while HTTP runs | Old result never delivered; cancellation may not stop server compute or retract sent text. |
| Two windows search; one closes | Remaining scope works; no global cancellation or leaked registrations. |
| Pause/disable | Pause stops document uploads; disable stops all new requests and releases semantic client state. |
| IDE close/restart/plugin unload | Requests/queues/connections released; committed cache retained and validated vectors reloaded without unchanged document uploads. |
| Memory eviction | Persisted vectors remain reusable; reload before considering HTTP embedding. |
| Disk full/locked/corrupt/newer schema | BM25 works; preserve usable cache, pause unsavable indexing, no silent destructive rebuild/re-upload. |
| Crash during commit/migration/export/import | Transaction/snapshot guarantees preserve a usable previous state; no partial active source revision. |
| Import into another IDE with moved roots | Validate profiles, rebind discovered sources, reuse identical input hashes; no credentials/consent imported. |
| Clear saved embeddings | Coordinated purge of owned DB/sidecars only; explain re-embedding cost, no claim of server deletion. |
| Eviction/zero hits with incomplete data | Coverage remains visible; unknown content is not declared a complete no-match. |
| Semantic-only/tool hit navigation | Reveal supporting passage without requiring literal query matches. |
| Source changes before navigation | Revalidate offsets, refresh/fallback honestly; never jump to unrelated source text. |

## 11. Diagnostics and user-facing documentation

Use existing bounded local diagnostics with safe events/reasons: index revisions/coverage, passage
counts/eviction, extraction/index/inference durations, request counts/bytes/status categories,
connection/test/configuration revisions, rate limiting, cancellations and fallback reasons. Endpoint
references may be hashed; avoid raw private hosts/paths in diagnostic files. No request/response
bodies, titles, query text, API keys, auth headers, vectors or unsanitized server/transport exceptions.
Use synthetic marker tests to prove failures cannot echo secrets/content into logs or test reports.

Update README search/quoting/filter semantics and the optional external-server setup. Replace any
blanket "no network" or "all data always stays local" claim with accurate mode-specific language:
BM25 is local; opt-in semantic mode sends disclosed inputs to the user-configured server. Explain
server retention limits, indexing scope, pause vs disable vs purge, saved embeddings, startup reuse
and explicit cache export/import. Raw transcript content is not included in the SQLite store/export.
A localhost URL does not prove the server never proxies elsewhere. Keep settings/accessibility,
release notes and Marketplace source copy consistent. Screenshot fixtures must be synthetic.
Do not publish/upload a release or Marketplace media without a separate user request.

Keep the prototype report as historical local-runtime evidence, not the current release contract.
This plan remains the authoritative execution checklist; do not create a competing active roadmap.

## 12. Ordered implementation work items

All boxes below start unchecked. Existing prototype success does not complete a production item.
Use one commit per item. If an item needs multiple working sessions, record partial progress here
without checking it. Items 0–4 deliver the BM25 milestone. Items 5–13 deliver the optional server semantic
milestone. Section 16 is deferred and is not part of the current completion gate. BM25 never
depends on an endpoint, credentials, native helper or installed model.

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
  source navigation. Default settings do not make embedding-server requests or create semantic state.
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

### 5. Implement the bounded HTTP embedding client

- [ ] **Deliverable:** EmbeddingClient seam and one production OpenAI-compatible HTTP adapter,
  using the section 6 protocol and injected transport/credential access. No native runtime work.
- Implement explicit base-URL validation/path composition, conditional Bearer auth, HTTPS validation,
  proxy behavior, redirect rejection, cancellation, request/response limits and safe status parsing.
- Validate/reorder response indexes, dimensions/norms and profile compatibility. Never silently
  select another model, ignore failed rows or attach partial results to the wrong chunk.
- Build reusable loopback fake-server fixtures in tests with synthetic inputs; they do not need
  internet, a real server/model or an API key. Close sockets/threads deterministically.
- **Tests:** URL prefix/trailing-slash/IPv6/userinfo/query/fragment cases; missing key/no-auth;
  exact body and auth destination; redirects that try to steal body/key; 401/403/404/429/503;
  delayed/partial/oversized/compressed/malformed replies; reordered/duplicate indexes; wrong/zero/
  NaN/Inf vectors; cancellation, deadline and no content/key leakage on any error path.
- **Done when:** `make check` passes; the adapter returns validated normalized vectors from a
  synthetic protocol server and never blocks lexical work. It performs no implicit discovery.

### 6. Implement reviewed connection settings and synthetic testing

- [ ] **Deliverable:** app-level server configuration, PasswordSafe credential management,
  per-configuration disclosure/acknowledgement and synthetic Test connection flow from section 8.
- Add missing-state defaults so old installations remain off. Editing fields/testing a draft must
  not mutate the applied endpoint. Apply/reset/cancel/isModified and key removal must work correctly.
- Test connection validates a fixed synthetic single/batch probe, including compatibility checks;
  it is not permission to send histories. Editing the draft after a test invalidates that test result.
- Endpoint/model/auth changes invalidate old send authorization and require cached-profile revalidation. Require review
  of the updated destination before Apply can enable real-text requests. No content in persisted
  consent records; use non-secret configuration/credential revisions.
- Implement HTTPS/loopback/plaintext policy and the explicit non-loopback unencrypted-HTTP option.
  Protect credentials with the IDE secure store and provide actionable failure if unavailable.
- **Tests:** default-off/migration; key never in XML/logs; credential access denied; draft test only
  sends synthetic strings; no network on settings-open/cancel; stale test response; repeated apply;
  endpoint/credential changes with work in flight; unacknowledged settings cannot dispatch content.
- **Done when:** `make check` passes and settings can connect to a fake endpoint and a real
  user-supplied compatible server using synthetic probes. No model is installed or downloaded.

### 7. Implement the durable SQLite cache and portable snapshots

- [ ] **Deliverable:** EmbeddingCacheStore with versioned schema, explicit vector encoding, atomic
  writes, disk budget/GC, durable managed path and export/import. Use the existing JDBC dependency.
- Implement the section 6 store/profile/source/input-hash design. Keep raw text, queries, endpoints,
  auth and consent out of exported caches; never persist transcript bodies/snippets in any cache.
- Prove the default path survives IDE restart/plugin upgrade/Invalidate Caches and is not silently
  included in Settings Sync. Inspect actual supported platform storage APIs before choosing the path.
- Implement single-writer short transactions, migration/maintenance coordination, bounded busy
  handling, crash-safe snapshot export, validated read-only import and merge without clobbering good data.
- Implement durable input-hash reuse across different source roots and compatible endpoint/model
  configurations; a memory eviction must be reloadable from disk with no new embedding request.
- **Tests:** float encoding/endianness, dimensions/norm corruption, FK/dedup invariants, partial writes,
  migration rollback/newer schema, crash/reopen, busy readers/writers/two instances, disk full,
  memory vs disk eviction, incomplete WAL copy, export while writing, import cancellation/corruption/
  oversized rows/unsafe schema, portable source remapping, missing sources, incompatible profiles,
  absence of text/key markers and no automatic external traffic during export/import.
- **Done when:** `make check` passes; a fresh store can import a consistent synthetic export and
  reuse all unchanged compatible vectors without a server call. Export/import never enables semantics
  or restores a credential, endpoint authorization or arbitrary external path.

### 8. Implement bounded semantic indexing and its lifecycle

- [ ] **Deliverable:** APP-owned semantic index/scheduler using shared extraction snapshots and the
  HTTP embedding client plus durable cache, with complete startup/active-memory lifecycle,
  authorization and coverage state.
- Implement conservative bounded chunks with source ranges, versioned cache keys, append-aware
  reuse, metadata/title invalidation, exact vector scoring and per-session aggregation.
- Enforce the section 7 budgets/queue/caps during enumeration, including transient work. Prioritize
  active candidates/recent sessions and avoid evict/re-embed loops. Load reusable durable vectors
  before sending misses, and commit validated batches transactionally before reporting saved coverage.
- Implement pause/resume, disable, endpoint/model/auth replacement, scope removal, reconnect,
  restart and explicit retry. Reindex only affected inputs; reuse durable work across sessions/instances.
  Model/endpoint changes mark profiles unverified until rechecked rather than erasing the store.
- **Tests:** fake deterministic embeddings with known nearest sessions; multi-topic/overlap/tool
  chunk mapping; special tokens/Unicode/input bounds; append vs rewrite; deleted sessions; unknown
  stamps; title/model/chunker changes; eviction; bounded lazy enumeration of huge inputs; two scopes;
  pause/restart/disable and late response rejection; queued scope changes preventing unintended
  uploads; memory/disk accounting; restart and fresh-instance import make zero unchanged-document
  HTTP requests; corrupt/missing/incompatible rows trigger only the appropriate bounded recovery.
- **Done when:** `make check` passes; opt-in HTTP indexing on synthetic data demonstrates incremental reuse, bounded requests/memory
  and correct coverage. Real-history uploads require separate destination/scope approval.

### 9. Implement semantic eligibility, abstention and hybrid ranking

- [ ] **Deliverable:** production BM25 + qualified semantic fusion, with exact-query protection and
  calibrated no-answer behavior. Replace the prototype's unconditional nearest-neighbor return.
- Define and test the technical-query classifier and quoted-query bypass. Apply scope/phrase
  constraints before top-K/fusion. Keep session deduplication and exact-evidence tiers deterministic.
- Calibrate candidate count/fusion weights/abstention on development data; freeze the policy and
  version it alongside endpoint/model/profile/chunker identity. Evaluate the held-out set once frozen.
- Audit regressions by category, including high-cosine but irrelevant neighbors, generic boilerplate,
  near-duplicate sessions and real identifier queries that embeddings might misinterpret.
- **Tests:** RRF with one/both/empty lists; duplicated passages; scope filtering before truncation;
  pure exact and mixed technical/natural-language queries; false-neighbor rejection; successful
  semantic-only match; zero semantic availability identical to BM25; no bogus score percentages.
- **Done when:** `make check` and semantic relevance gates pass, with report/version stored as
  synthetic/aggregate evidence. If abstention fails, do not enable a nearest-neighbor-only feature.

### 10. Complete progressive search and server-status UI

- [ ] **Deliverable:** lexical-first/refined search, complete server status/coverage, pause/resume,
  refresh/retry, cache usage/export/import/purge and passage navigation, using the reviewed settings
  from item 6 and the durable store from item 7.
- Wire configuration changes separately from progress broadcasts. Guard deliveries by all relevant
  request/scope/content/settings/profile generations. Preserve focus, grouping, selection and scroll.
- Show the actual supporting passage for semantic-only results, including collapsed tools; no
  fabricated literal match highlights. Handle changed/deleted source evidence when opening a hit.
- Distinguish indexing paused (queries may still be sent) from off (no traffic), rate limited,
  setup needed and incompatible/unreachable server. No repeated modal errors or hidden retries.
- Ensure the disclosure/load copy describes external-server processing and local indexing cost,
  without pretending the model is installed/managed by Seshlog or always on another machine.
- **Tests:** delayed/out-of-order enrichment; identical query under new endpoint/filter; empty and
  partial status; semantic-only source/tool navigation; unavailable server with prompt BM25 results;
  keyboard/accessibility; progress in two windows; reset/rebuild and late request response rejection.
- **Done when:** `make check` passes and the complete sandbox flow works against a real compatible
  server with synthetic sessions. Screenshots contain only synthetic data.

### 11. Harden HTTP failure handling, resource limits and privacy

- [ ] **Deliverable:** all section 10 failure rows covered and resource/traffic policy enforced.
- Audit client/queue/socket cleanup, settings/disposal races, pending retry timers and stale
  connection results. Verify zero requests after disable/cancel when no request was already sent.
- Test server errors that echo synthetic prompts/API keys; confirm markers never reach logs,
  exception reports, XML, temp files or diagnostic output. Review proxy/TLS failures for leakage.
- Exercise slow/misbehaving/rate-limited servers and memory/disk budgets without slowing lexical
  delivery. Check crash/restart/import reuse and safe behavior when durable writes become unavailable.
  Confirm reconnect/model changes cannot mix vector spaces and changed scopes cancel unsent uploads.
- **Tests:** deterministic fault injection for every failure row, cross-window cancellation, bounded
  retries/backoff, rejected redirects, body limits, credential storage failure and model drift.
- **Done when:** `make check` passes, failure/privacy evidence is recorded, and dropping the server
  mid-index recovers to BM25 without restart, request storms or a new destination being selected.

### 12. Validate real-server compatibility, platforms and documentation

- [ ] **Deliverable:** tested server/version/profile matrix, final quality/resource report and
  accurate README/settings/Marketplace-source privacy language. Native distribution is deferred.
- Run opt-in synthetic smoke/retrieval tests against a real EmbeddingGemma-compatible server,
  initially Ollama's compatible endpoint. Record version, model tag/revision, prefix behavior,
  vector dimensions, transport and whether the server was on this machine. Do not claim untested
  servers are supported solely because they advertise compatibility.
- Validate HTTP/PasswordSafe/TLS/proxy/IPv6 and shutdown on supported macOS/Windows/Linux IDEs.
  Avoid assuming `/dev/stdin`, Homebrew or another platform-specific runtime exists.
- Repeat frozen-corpus evaluation using production BM25 + HTTP embeddings. Record latency p50/p95,
  request counts/bytes, indexing time, JVM memory, coverage and fallback behavior. Remote resource
  measurements, when available, must be labelled server-side.
- Update docs/disclosures to state what is transmitted, when, to where and under which controls.
  Keep unrelated plugin behavior and historical prototype results intact.
- Run `make check`, `./gradlew verifyPlugin`, `make release`, opt-in real-server tests and the
  manual matrix below. Inspect the ZIP for absence of keys, models, helpers and private data.
- **Done when:** current release gates and relevant platform/manual checks pass. Lack of a managed
  local backend is an intentional later phase, not an incomplete external-server release.

### 13. Final review and completion

- [ ] **Deliverable:** a clean, reviewable change set and an honest handoff showing both milestones.
- Re-read sections 1, 3, 5 and 8 against the actual implementation. Check default behavior on a
  clean IDE profile with no configured server, then the reviewed opt-in server flow.
- Ensure no embedding traffic occurs when off; a user can configure/test/review the server, enable
  the feature, see progress, find a semantic-only passage, pause, disable and reclaim client memory.
- Confirm only the authorized endpoint receives inputs, durable vector reuse without raw text storage, exact phrases,
  filters, read-only providers and unchanged resume/fork/restore actions.
- Record final commit IDs, commands, metrics, platform matrix and remaining optional follow-ups.
  Do not mark the whole plan complete if a supported platform or mandatory acceptance check is owed.
- **Done when:** items 0–13 and the current release acceptance checks are complete. Section 16 remains deferred.
  Publishing/deployment is
  a separate user-authorized action; preparing a verified release artifact does not upload it.

## 13. Commands, checkpoints and troubleshooting

```sh
# Inspect the actual current code rather than trusting old line numbers:
rg -n 'SearchHit|ContentSearchIndex|TextQuery|sourceMatches|groupRanked' src/main src/test
rg -n 'search|Search|semantic|Semantic' src/main/kotlin/com/hedworth/seshlog/settings

# Required normal test suite; real-server/model/real-data suites must remain opt-in:
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
for the production HTTP client/evaluator as implementation develops, and document them here.
An HTTP smoke target must take an explicit endpoint/model and use synthetic inputs only. Do not
reuse SESHLOG_SEMANTIC_REAL as automatic permission to send private text to a remote server.

When debugging tracking rather than search, follow the diagnostics instructions in `AGENTS.md`.
Do not change transcripts to manufacture failures. For search tests, use injected fake providers,
HTTP transports and synthetic files. Avoid broad `idea.log` inspection unless a specific platform/client
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

### External-server semantic milestone

- [ ] Off by default after install/upgrade; no background embedding or connection-probe requests.
- [ ] Base URL/model/key and destination/data-sharing disclosure are clear before enabling.
- [ ] Test connection uses only synthetic strings and does not apply settings or upload histories.
- [ ] Key is stored securely; settings cancel/reset/removal works without plaintext leakage.
- [ ] HTTP path prefixes, trailing slashes, IPv6, proxy/TLS and plaintext policy work as specified.
- [ ] Invalid URL, 401/403/404, incompatible model and unavailable server explain fallback to BM25.
- [ ] Redirects cannot replay inputs/auth to another destination; server error bodies remain private.
- [ ] Enable and index: scope/coverage is accurate; lexical search stays responsive.
- [ ] Semantic-only query reveals the actual passage with no fake literal highlighting.
- [ ] Unrelated query abstains under the accepted relevance policy.
- [ ] Exact/quoted queries retain lexical behavior; filters apply before semantic ranking.
- [ ] Narrowing scope/hiding sessions cancels unsent out-of-scope passage work.
- [ ] Pause stops document uploads but permits disclosed query requests; disable stops all new traffic.
- [ ] Endpoint/model/auth change requires review; only compatible verified saved profiles are reused.
- [ ] Same-name model replacement can be detected/rebuilt; limitations are documented.
- [ ] Timeout, 429/503 and connection drops trigger bounded retry/backoff, not request storms.
- [ ] Two windows share bounded client scheduling; closing one leaves the other usable.
- [ ] Source changes before click cannot navigate stale offsets into unrelated text.
- [ ] Restart loads saved embeddings; unchanged document inputs are not re-sent to the server.
- [ ] Export during writes/import into a fresh IDE with moved roots reuses matching vectors; no credentials or consent transfer.
- [ ] SQLite persistence contains only allowed derived data/metadata, no raw text or query history.
- [ ] Disk-full/corrupt/locked/newer-schema cache keeps BM25 available and does not silently erase/re-upload data.
- [ ] Disable retains disk cache; Clear saved embeddings removes owned cache files under coordination.
- [ ] Invalidate Caches/plugin upgrade does not erase the durable store; automatic Settings Sync does not upload it.
- [ ] API key/prompt markers never enter logs, files, XML, diagnostic output or crash error messages.
- [ ] IDE close/plugin unload releases requests, connections, queues, timers and callbacks.
- [ ] Real compatible-server smoke/quality tests pass; actual server/model versions are recorded.
- [ ] HTTP/credentials/privacy flow validated on supported macOS, Windows and Linux IDEs.

## 15. Decision and validation record — fill as work progresses

Keep evidence here so the next session does not reconstruct decisions from chat. Existing local
prototype evidence remains valid as historical measurements, not proof of the new HTTP transport.

| Area | Decision / evidence | Status |
| --- | --- | --- |
| Product direction | BM25 default; first optional semantic release connects to a user-supplied server; managed local backend later. | Agreed |
| Storage | Required v1 SQLite embedding cache with model/profile/input-hash/source metadata; bounded in-memory retrieval; portable export/import; no raw transcript/query persistence. | Agreed, supersedes memory-only design |
| Prototype | `e598a18`; section 2 and docs/semantic-search-prototype.md. | Complete, local/test-only |
| Evaluation corpus | Record version/hash, frozen split, labels and baseline. | Pending item 0 |
| BM25 scorer | Record tokenizer, exact tier, weights and corpus statistics unit. | Pending item 1 |
| BM25 release | Record held-out metrics, tests, performance and manual checks. | Pending item 4 |
| HTTP protocol/client | Record supported API, input/response bounds, URL/TLS/proxy behavior and tests. | Pending item 5 |
| Settings/credentials/authorization | Record PasswordSafe API, config revisions, test-only probes and disclosure/Apply tests. | Pending item 6 |
| Persistent cache | Record durable path/schema/encoding, transactions, migrations, disk limits, export/import and unchanged-input restart tests. | Pending item 7 |
| Semantic index | Record chunk/profile versions, memory limits, cache reuse, reconnection validation and coverage. | Pending item 8 |
| Hybrid/abstention | Record frozen acceptance policy and held-out category/negative results. | Pending item 9 |
| Progressive UI | Record passage navigation, status, selection/focus and fallback tests. | Pending item 10 |
| Failure/privacy | Record HTTP fault matrix, traffic controls, marker checks and cleanup. | Pending item 11 |
| Server/platform matrix | Record actual servers/versions/model profiles plus macOS/Windows/Linux checks. | Pending item 12 |
| Final release | Record commits, commands, verifier/build, metrics and optional limitations. | Pending item 13 |
| Managed local backend | Separate later phase described below; not a current release gate. | Deferred by user |

### Definition of the requested first release

A user gets useful BM25 search immediately, with exact phrases and technical queries preserved.
They can explicitly configure/test/review their own embedding server, understand what is sent,
enable semantic enhancement, see/pause indexing and navigate meaningful passages. Server failure
leaves BM25 usable. Disabling stops new embedding requests and frees Seshlog's active in-memory semantic index,
while keeping the reusable durable cache;
it does not claim to delete already transmitted data or stop the user's server.
Agent data stays read-only. SQLite durably stores embeddings, hashes, model identity and source
references; the IDE loads relevant vectors into memory and reads raw passages from original sources.
Unchanged inputs survive restarts and explicit export/import without re-embedding. No raw transcript
text, API keys or query history is stored in that cache. Server retention remains outside our control.

## 16. Deferred later phase — managed local inference

This is intentionally not part of items 0–13 or the external-server release acceptance gate.
Do not implement it until the server-backed option has been delivered/evaluated and the user asks
for this phase. The prior detailed local-first plan is available at `34363dc:PLAN.md`.

Preserve the EmbeddingClient seam so a later Run locally option can share indexing/ranking/UI.
That later phase would require:

- A supervised resident native runtime with bounded process protocol, tokenizer access, cancellation,
  crash handling, idle unload and actual RSS/CPU measurement on supported machines.
- Reproducible platform binaries, current model/runtime license review, verified explicit downloads,
  asset ownership/removal/upgrade, code signing and OS-specific launch validation.
- Accurate model download and local RAM/CPU/battery disclosure; the measured 334 MB/~1.35 GB values
  are prototype evidence, not automatic promises for a future production runtime.
- One app-owned model worker, resource limits, graceful fallback, real offline operation and tests
  proving no private inputs are sent externally when that local mode is selected.
- A deliberate settings mode choice with backend-specific disclosure and vector invalidation on
  switching. The current external option must remain supported and BM25 must remain independent.

The persistent SQLite embedding cache is already required in the first external-server release;
reuse it for a later local backend when model/profile compatibility is established. Switching backend
must revalidate vector compatibility and authorization rather than mixing spaces or discarding all
cached work automatically. Persisting raw conversation text or query history remains out of scope.
