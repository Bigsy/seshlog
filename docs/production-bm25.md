# Persistent BM25 validation — 2026-10-25

Phase 1 implementation uses production `ContentSearchIndex` and `LexicalSearchStore`.
These results do not complete the manual/platform acceptance matrix in PLAN.md.

```sh
make check
make lexical-bench  # synthetic only; three independent DB builds at each scale
./gradlew verifyPlugin --offline
make release
```

The [JSONL report](benchmarks/production-bm25-2026-10-25.jsonl) contains synthetic case
IDs, all six scale runs, environment/version details and aggregate memory samples.
No real histories, model, endpoint or credentials are used. Temporary benchmark DBs are removed.

## Search and storage

Schema 2 has separate contentless-delete FTS5 title, path and body populations. SQLite
computes BM25 and best-passage/field aggregation, with title/path/body weights 8/3/1.
Provider/local titles combine by maximum, not sum. The exact evidence tier and stable
recency/provider/session ties are applied after mandatory source verification.

Normalization 1 uses Unicode letter/number/underscore words, the prototype stop list,
no stemming, and document aliases for camel/snake identifiers. Query aliases do not
multiply a compound token's contributions. Already-normalized tokens use FTS5's ASCII
tokenizer with underscore enabled: non-ASCII word bytes remain intact instead of
being reclassified by unicode61's older Unicode tables. Unicode substring queries
also have source verification. Plain identifier substrings use a scoped fts5vocab
candidate lane, then original-source verification. SQL/MATCH inputs are parameters.

Body windows are 2,048 UTF-16 units with 128-unit overlap, without splitting surrogate
pairs. Phrases are checked on original complete retained entries and can cross a window
boundary, never an entry boundary. Shared provider/viewer extraction limits remain unchanged.
Snippets stay in memory and refer to the actual ranked entry/range. Opening a passage
rechecks its source hash before using offsets.

`${PathManager.getConfigDir()}/seshlog/search/lexical.sqlite` is durable IDE storage.
It is not a registered persistent-state or exportable component directory. Platform
262's SettingsSyncIdeMediatorImpl collects registered exportable storages and roaming
stream-provider writes; this directory participates in neither. It survives Invalidate
Caches. No automatic upload or Settings Sync is implemented. Exports still disclose
sensitive words/positions, even without a duplicate transcript text column.

The lifetime file lease excludes competing IDE writers/maintenance. A second process
uses a temporary SQLite fallback. DELETE/FULL journaling, an 8 MiB page cache, in-memory
scratch and bounded SQL progress callbacks apply. There are at most 100,000 body rows
and 50,000 sources. The main-file ceiling starts at 112 MiB, leaving rollback and
maintenance headroom in the 256 MiB disk policy; retained recovery files reduce that
ceiling further. Rebuild preserves one recovery copy; Clear removes owned recovery
files too. Errors never silently delete/downgrade the old index.

Import validates exact schema, versions, integrity, associations and resource bounds.
It reconstructs normalized postings in bounded batches and merges missing sources in
one rollback-capable transaction. It never runs imported SQL or opens stored paths.
The document count per batch adapts to the token/character bound. The reported case
of **256 passages × 350 tokens** now imports successfully, with identical scores and
entry identity, preservation of existing sources, idempotence and reopen tests.

Imported stamps are invalidated. Discovered local files can validate portable hashes
without extraction/tokenization, including moved roots; absent sources stay unresolved.
Hashing is cancellable and bounded to 16 MB across at most 2,000 files. Large/unverifiable
imports require local extraction. Original conversations remain necessary for display.
Reopened body snippets may read their supporting original source; the zero-extraction
reopen measurement below deliberately uses metadata-only evidence.

## Relevance

Frozen v1 fixture SHA-256:
`1ccad9157d44ee0a76a27097276c4b90e25d29df92c7151b87610a2e03d031fa`.
It has 200 synthetic sessions and 200 queries: 150 positive, 50 absent, with multiple
relevant labels. Family-separated development/evaluation partitions each contain
75 positives and 25 absent queries. Labels preceded production ranking changes.

| Evaluation partition | Literal baseline | Production BM25 |
| --- | ---: | ---: |
| Hit@1 / recall@5 / MRR@5 / binary nDCG@10 | 0.667 | 1.000 |
| Natural-query hit@1 / recall@5 | 0 | 1.000 |
| Technical and phrase hit@1 | 1.000 | 1.000 |
| No-answer false-positive rate | 0 | 0 |

Development metrics are the same. No evaluation-specific weights were tuned. This
repeated generated scaffold tests contracts; its perfect results are **not** evidence
of representative real-history relevance. Broader realism and human label review remain
release work. Unicode, identifiers, phrases across entries/windows, stopwords, errors,
large tool output and malformed/provider coverage have separate correctness tests.

The preserved original 44-query smoke set gives 32/40 first and 39/40 top-five, compared
with the frozen literal baseline's historical 15/40 and 16/40. The old test-only combined
field BM25 scored 36/40; separate fields and protected exact tiers change that ordering.
No previously correct literal top-one technical/phrase case was lost in the smoke run.
The original reports/adapters remain reproducible; these methods are not interchangeable.

## Cost and limits

Apple M1 Max, 64 GiB, JDK 25.0.4, platform 262.10968.63, SQLite JDBC 3.53.4.0.
A normal IDE remained open; CPU affinity was not controlled. Each scale has three fresh
DB builds in one warmed JVM. Each warm p95 uses 12 samples: three passes over four
ordinary/technical/phrase queries after one warmup pass. The repeated short corpus is
a load test, not large-history relevance evidence or an IDE startup measurement.

| Cost | 1,000 sessions, range over 3 runs | 10,000 sessions, range over 3 runs |
| --- | ---: | ---: |
| Fresh index + first query | 0.86–1.12 s | 10.28–10.68 s |
| Warm p50 | 8–13 ms | 80–83 ms |
| Warm p95 | 9–15 ms | 84–89 ms |
| Open + reconcile + metadata query | 9–12 ms | 75–87 ms |
| Metadata update + query | 9–12 ms | 71–80 ms |
| One-source append + query | 9–12 ms | 71–88 ms |
| Export | 5 ms | 28–30 ms |
| Import | 95–127 ms | 1.39–1.47 s |
| Main DB size | 839,680 bytes | 7,630,848 bytes |

Every reopen and metadata update asserted **zero source extractions**. Every append
asserted **one**. A separate moved-file snapshot test asserts zero full extraction for
validated metadata search. Durable postings survive source-buffer eviction.

These warm measurements include synthetic stamp reconciliation, SQLite ranking and
literal verification, with filters before the presentation limit. Synthetic stamps
are cheap integers; real provider discovery/stat costs are not measured here.

The 10k run retained about 824k source UTF-16 units. Whole-test-JVM heap samples were
40–133 MB; process RSS samples were 307–447 MB across all scales. Summed heap-pool peaks
in the report are not simultaneous peaks, and RSS is not SQLite-only memory. These do
not prove the 128 MiB logical search budget under worst-case histories. Allocation/native
scratch profiling, a lower-memory machine, broader long-history resource work and the
manual macOS/Windows/Linux matrix remain unverified. The 10k warm latency target passed
on this reference workload; the milestone is not marked fully release-accepted.
