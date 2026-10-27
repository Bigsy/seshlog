# Current work plan

Updated 2026-10-27. This is the authoritative execution checklist. Read [AGENTS.md](AGENTS.md)
before editing; inspect `git status --short`, preserve user work, and re-find symbols rather than
trusting historical line numbers. Take the open items below in order unless the user directs otherwise.

## Current status and next work

| Area | Status | Evidence / next action |
| --- | --- | --- |
| Persistent SQLite FTS5 BM25 | Implemented and automatically tested; release acceptance open | [Production report](docs/production-bm25.md); finish responsiveness, relevance/resource and manual checks below. |
| Source filters and match provenance | Implemented and automatically tested; release acceptance open | [Source-filter report](docs/search-source-filters.md); all physical UI checks remain unverified. |
| External-server semantic search | Agreed, unimplemented | Start items 5–13 only after Phase 1 acceptance; [detailed design](docs/semantic-search-design.md). |
| Managed local inference | Deferred until separately requested after server-backed evaluation | [Later-phase scope](docs/semantic-search-design.md#deferred-managed-local-inference). |
| Other feature ideas | Local backlog only | Ignored `PLAN-BACKLOG.md` in this checkout; quick session/worktree switcher is the first candidate after current work. |

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

- [ ] Finish both physical checklists below in a verified synthetic sandbox profile. Record the
  actual IDE/build, OS/arch, configuration, date and result. No row is passed by this consolidation.
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
JDK, the JDK-25 retry used repository-default paths, and UI attachment failed. The task sandbox was
stopped without counting any manual row as passed; the daily IDE was not changed. Verify effective
config/system/log paths and loaded build before testing. Do not alter agent data to create examples.

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
make run

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
Follow AGENTS.md for diagnostics and sandbox/cache permissions. No transcript edits to enable logging.

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
- Latest unit/platform test run: 2026-10-27, `make check`, **537 tests, zero failures/errors,
  10 opt-in skips**. The small literal-verification optimization and its synthetic before/after
  benchmark are recorded in the source-filter report.
- Latest packaged validation: 2026-10-27, `make release` and `./gradlew verifyPlugin --offline`;
  0.8.1 passed 537 tests (10 opt-in skips), ZIP integrity/content inspection and compatibility on
  IU-262.10968.63 / IU-263.7307.44 with the existing API warnings. Artifact SHA-256 and details are
  in the source-filter report. This build does not complete the remaining manual/platform checks.
- [SQLite experiment](docs/sqlite-bm25-benchmark.md) and [local semantic prototype](docs/semantic-search-prototype.md)
  remain historical measurements with their original caveats and reproducible commands.
- Historical worktree work and unfinished checks remain in Git at `e598a18:PLAN.md`; the earlier
  local-inference plan is `34363dc:PLAN.md`. This consolidation does not complete that work or
  resume it. The completed source-filter handoff is preserved at `fcd0d57:PLAN-filter.md`.
