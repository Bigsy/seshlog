# External embedding search — agreed design

Consolidated 2026-10-27 from the 2026-10-25 search plan. **Unimplemented:** this preserves the
agreed Phase 2 requirements; it is not evidence that server-backed search exists or has passed
acceptance. [PLAN.md](../PLAN.md) owns execution order, completion status and open release gates.
Finish Phase 1 acceptance there before starting items 5–13 below. No direction needs reconfirming.

The first semantic release uses an explicitly configured user-supplied EmbeddingGemma server,
a separate durable SQLite vector cache and bounded in-memory vector retrieval. BM25 stays local
and independent. Managed local inference is a separately requested later phase.

The shared source-selection, lexical, persistence and navigation contracts in PLAN.md also apply.
Capture `SearchSources` with requests: disabled sources cannot supply semantic candidates,
explanations or navigation evidence. Recheck current source/scope eligibility before dispatching
queued passage uploads, and cancel unsent work when eligibility narrows. The all-source reusable
lexical index is not permission to upload all sources or histories to the embedding server.

## Constraints

- IntelliJ Platform 2026.2/build 262, JDK 25, Kotlin API level 2.3. Inspect the SDK/dependencies
  before choosing APIs. Do not infer compatibility from the Kotlin 2.4 stdlib bundled by the IDE.
- Agent roots are read-only, including `~/.claude`, `~/.codex`, `~/.pi/agent` and opencode storage.
  Never create a model/cache/temp file there. Respect configured roots and existing provider APIs.
- Raw conversation/tool text, snippets and query caches remain in memory. Phase 1 explicitly
  permits persistent SQLite FTS5 terms, positions, frequencies, lengths, index versions and source
  references. Phase 2 adds vectors, input hashes and model/profile identity. No duplicate raw
  transcripts, query histories or HTTP bodies. Contentless FTS still exposes words and their
  ordering and can reveal substantial content: it is sensitive derived data, not anonymization.
  Persist indexes only in Seshlog-managed IDE storage or explicit user-selected exports; never
  in agent roots or the repository. Source parse caches remain metadata-only.
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
  logs/diagnostics/exception messages. Keep ContentSearchService free of raw-query logging.
- All extraction, scoring, hashing and HTTP work runs off EDT. Rendering consumes immutable snapshots.
- Keep scoring/lifecycle pure/testable; inject extractors, stamps, executors, clocks, HTTP transport,
  credential access and connection-state probes rather than mocking IntelliJ classes.

- JUnit 4 for pure logic; `BasePlatformTestCase` for project/EDT/service tests. New fixtures are
  synthetic and committed under `src/test/resources/fixtures/`. No real transcript fixture copies.
- Preserve unreadable/malformed-provider handling, shared extraction limits, filters, continuation
  handling, subagent coverage, pins/hidden state, session actions and terminal tracking.

## Semantic search behavior

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

## Extend the existing search types

Phase 1 already supplies `SearchSessionKey`, `RetrievalQuery`, `SearchResultSnapshot`,
`SearchHit`, `SearchContext`, source classification and passage validation. Inspect those symbols
and extend the existing seams; do not recreate the pre-Phase-1 index or migrate unrelated
organisation metadata as a side effect.

The immutable result envelope must include request/scope/candidate/corpus/settings/model revisions,
lexical versus refined phase, ordered hits, separate lexical/semantic coverage and sanitized
availability reasons. Coverage is independent of hit count. Retain the underlying `Session` for
actions and provider-aware identity throughout UI maps.

Keep query text/snippets in memory. Use finite `Double` ranking values internally, never numeric
confidence claims in the UI. Carry the actual supporting entry, original UTF-16 range, hash,
source classification and content revision. Revalidate before navigation; a changed source calls
for refresh or an unavailable message, never an unrelated jump.

## Embedding client, HTTP contract and active in-memory index

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

## Durable SQLite embedding cache, restart reuse and portability

This subsection specifies the Phase 2 vector store. Its prohibition on token/posting corpora applies
to that store; the separate Phase 1 lexical store explicitly permits FTS terms and positions. Share
safe path/snapshot/transaction helpers from Phase 1, not the data tables or purge/failure domain.
This is a required first-semantic-release component. Use the existing `org.xerial:sqlite-jdbc` dependency
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

## Hybrid ranking and abstention

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

## Scheduling, cancellation and resource policy

These are starting engineering targets, not measured server guarantees. Keep limits centralized
and record measured revisions. The old ~1.35 GB observation belongs to the local model process,
not this plugin's HTTP client. Server hardware, request latency and model retention are external.

| Resource | Starting policy |
| --- | --- |
| Lexical working memory | 128 MiB logical budget for source/snippet buffers, query results, metadata and SQLite caches; start with 8 MiB page cache per connection and bound connection count. Measure native scratch allocations too; no full RAM copy of postings. |
| Persistent lexical index (Phase 1) | 256 MiB initial disk budget, at most 100,000 body passages plus bounded metadata; include FTS/associations/WAL/maintenance headroom. Memory eviction retains disk rows. |
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

Semantic indexing only: start after applied opt-in/configuration authorization and a compatible synthetic connection
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

## Server settings, disclosure, credentials and status UX

Add a Search section to **Settings → Tools → Seshlog**. Default BM25 requires nothing. The first
semantic release exposes only Use my embedding server; do not display a nonfunctional Run locally
option or a download button. Managed local inference is a later backend described under [managed local inference](#deferred-managed-local-inference).

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

## Retrieval and performance evaluation gates

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

For Phase 1, measure source discovery/stamp validation, SQLite open/first result and actual changed
source extraction separately. Assert zero full re-tokenizations of unchanged known-stamp sources
across reopen and compatible snapshot import; do not rely only on wall-time thresholds. Include
scope-filtering and exact/phrase verification costs, long inputs, index growth and failure recovery.

The historical lexical reference is warm BM25 p95 under 100 ms at 10,000 synthetic sessions within
the stated budget. On 2026-10-27 the user chose small optimizations only and dropped that numerical
miss as an active release blocker; see PLAN.md. Preserve responsiveness checks and report actual
measurements rather than requiring a larger lexical redesign. Lexical first-result delivery under
500 ms when indexed remains a reference target. Target warm semantic end-to-end p95 under
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

## Failure and lifecycle acceptance table

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
| Embedding DB full/locked/corrupt/newer schema | Independent lexical DB keeps BM25 working; preserve usable cache, pause unsavable indexing, no silent destructive rebuild/re-upload. |
| Crash during commit/migration/export/import | Transaction/snapshot guarantees preserve a usable previous state; no partial active source revision. |
| Import into another IDE with moved roots | Validate profiles, rebind discovered sources, reuse identical input hashes; no credentials/consent imported. |
| Clear saved embeddings | Coordinated purge of owned DB/sidecars only; explain re-embedding cost, no claim of server deletion. |
| Eviction/zero hits with incomplete data | Coverage remains visible; unknown content is not declared a complete no-match. |
| Semantic-only/tool hit navigation | Reveal supporting passage without requiring literal query matches. |
| Source changes before navigation | Revalidate offsets, refresh/fallback honestly; never jump to unrelated source text. |

## Diagnostics and user-facing documentation

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
and explicit cache export/import. No duplicate raw transcript text is stored/exported; lexical
terms and positions remain sensitive and must be described as such.
A localhost URL does not prove the server never proxies elsewhere. Keep settings/accessibility,
release notes and Marketplace source copy consistent. Screenshot fixtures must be synthetic.
Do not publish/upload a release or Marketplace media without a separate user request.

Keep the prototype report as historical local-runtime evidence, not the current release contract.
[PLAN.md](../PLAN.md) remains the authoritative execution checklist; this file preserves the detailed design.

## Implementation acceptance details (items 5–13)

These are deliverables, tests and completion conditions for the pending items tracked in PLAN.md.
They carry no separate completion status. One focused commit per item; normal tests use synthetic
inputs and injected transports without a model, server, real history, credentials or network.

### 5. Implement the bounded HTTP embedding client

- **Deliverable:** EmbeddingClient seam and one production OpenAI-compatible HTTP adapter,
  using the [HTTP contract](#embedding-client-http-contract-and-active-in-memory-index) and injected
  transport/credential access. No native runtime work.
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

- **Deliverable:** app-level server configuration, PasswordSafe credential management,
  per-configuration disclosure/acknowledgement and synthetic Test connection flow from the
  [server-settings contract](#server-settings-disclosure-credentials-and-status-ux).
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

### 7. Extend durable storage and portable snapshots for embeddings

- **Deliverable:** EmbeddingCacheStore with versioned schema, explicit vector encoding, atomic
  writes, disk budget/GC, durable managed path and export/import. Use the existing JDBC dependency.
- Reuse Phase 1's storage/snapshot/locking helpers, keeping a separate semantic database so clearing
  or corrupting embeddings cannot remove the lexical index. Extend the helpers for vector profiles.
- Implement the [durable-cache design](#durable-sqlite-embedding-cache-restart-reuse-and-portability).
  Keep raw text, queries, endpoints, auth and consent out of exported caches; never persist
  transcript bodies/snippets in any cache.
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

- **Deliverable:** APP-owned semantic index/scheduler using shared extraction snapshots and the
  HTTP embedding client plus durable cache, with complete startup/active-memory lifecycle,
  authorization and coverage state.
- Implement conservative bounded chunks with source ranges, versioned cache keys, append-aware
  reuse, metadata/title invalidation, exact vector scoring and per-session aggregation.
- Enforce the [resource policy](#scheduling-cancellation-and-resource-policy) during enumeration,
  including transient work. Prioritize active candidates/recent sessions and avoid evict/re-embed
  loops. Load reusable durable vectors
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

- **Deliverable:** production BM25 + qualified semantic fusion, with exact-query protection and
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

- **Deliverable:** lexical-first/refined search, complete server status/coverage, pause/resume,
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

- **Deliverable:** all [failure-table](#failure-and-lifecycle-acceptance-table) rows covered and resource/traffic policy enforced.
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

- **Deliverable:** tested server/version/profile matrix, final quality/resource report and
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

- **Deliverable:** a clean, reviewable change set and an honest handoff showing both milestones.
- Re-read PLAN.md, the constraints, search contracts and server settings against the implementation.
  Check default behavior on a clean IDE profile with no configured server, then the reviewed opt-in
  server flow.
- Ensure no embedding traffic occurs when off; a user can configure/test/review the server, enable
  the feature, see progress, find a semantic-only passage, pause, disable and reclaim client memory.
- Confirm only the authorized endpoint receives inputs, durable vector reuse without raw text storage, exact phrases,
  filters, read-only providers and unchanged resume/fork/restore actions.
- Record final commit IDs, commands, metrics, platform matrix and remaining optional follow-ups.
  Do not mark the whole plan complete if a supported platform or mandatory acceptance check is owed.
- **Done when:** Phase 1 acceptance in PLAN.md, items 5–13 and the external-server acceptance checks
  are complete. Managed local inference remains deferred. Publishing/deployment is a separate
  user-authorized action; preparing a verified release artifact does not upload it.

## External-server manual acceptance requirements

All of these remain unverified. Use `make run` with synthetic sessions in a verified sandbox
profile. Record IDE/build, OS/arch, server/model/profile identity, configuration, date and result
in PLAN.md or its linked validation report. Automated fixtures do not establish physical UI or
supported-platform acceptance.

- Off by default after install/upgrade; no background embedding or connection-probe requests.
- Base URL/model/key and destination/data-sharing disclosure are clear before enabling.
- Test connection uses only synthetic strings and does not apply settings or upload histories.
- Key is stored securely; settings cancel/reset/removal works without plaintext leakage.
- HTTP path prefixes, trailing slashes, IPv6, proxy/TLS and plaintext policy work as specified.
- Invalid URL, 401/403/404, incompatible model and unavailable server explain fallback to BM25.
- Redirects cannot replay inputs/auth to another destination; server error bodies remain private.
- Enable and index: scope/coverage is accurate; lexical search stays responsive.
- Semantic-only query reveals the actual passage with no fake literal highlighting.
- Unrelated query abstains under the accepted relevance policy.
- Exact/quoted queries retain lexical behavior; filters apply before semantic ranking.
- Narrowing scope/hiding sessions cancels unsent out-of-scope passage work.
- Pause stops document uploads but permits disclosed query requests; disable stops all new traffic.
- Endpoint/model/auth change requires review; only compatible verified saved profiles are reused.
- Same-name model replacement can be detected/rebuilt; limitations are documented.
- Timeout, 429/503 and connection drops trigger bounded retry/backoff, not request storms.
- Two windows share bounded client scheduling; closing one leaves the other usable.
- Source changes before click cannot navigate stale offsets into unrelated text.
- Restart loads saved embeddings; unchanged document inputs are not re-sent to the server.
- Export during writes/import into a fresh IDE with moved roots reuses matching vectors; no credentials or consent transfer.
- SQLite persistence contains only allowed derived data/metadata, no raw text or query history.
- Disk-full/corrupt/locked/newer-schema cache keeps BM25 available and does not silently erase/re-upload data.
- Disable retains disk cache; Clear saved embeddings removes owned cache files under coordination.
- Invalidate Caches/plugin upgrade does not erase the durable store; automatic Settings Sync does not upload it.
- API keys/raw prompts never enter logs, XML, diagnostics or crash error messages. Embedding caches
  exclude raw text; lexical caches contain the disclosed terms/positions, never duplicate raw transcripts.
- IDE close/plugin unload releases requests, connections, queues, timers and callbacks.
- Real compatible-server smoke/quality tests pass; actual server/model versions are recorded.
- HTTP/credentials/privacy flow validated on supported macOS, Windows and Linux IDEs.

## Deferred managed local inference

This is intentionally outside the external-server implementation and release acceptance gate.
Do not implement it until the server-backed option has been delivered/evaluated and the user asks
for this phase. The prior detailed local-first plan is available in Git at `34363dc:PLAN.md`.

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

## Historical evidence and validation record

- [Local EmbeddingGemma prototype](semantic-search-prototype.md): synthetic relevance, model/profile
  identity, local runtime costs and limitations. It does not validate production HTTP support.
- [SQLite comparison](sqlite-bm25-benchmark.md): the experiment behind the durable BM25 decision.
- [Production BM25 report](production-bm25.md) and [source-filter report](search-source-filters.md):
  implemented lexical behavior, correctness, reuse, measured resources and outstanding acceptance.
- For each implemented item, record its commit, actual commands, tests, server/profile/platform
  versions, metrics and remaining limits in PLAN.md or a linked report. Keep HTTP client,
  settings/authorization, cache/portability, indexing, hybrid relevance, progressive UI,
  failure/privacy and platform/release evidence distinguishable.
- Add explicit synthetic-only HTTP smoke/evaluation targets as implementation develops. An endpoint,
  API key, Test connection result or local benchmark flag never authorizes private-history uploads.
