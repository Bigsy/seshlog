# SQLite FTS5 BM25 comparison — 2026-10-25

The user approved persistent SQLite FTS5 BM25 as Phase 1 after this experiment. PLAN.md is the
implementation handoff. These test-only adapters do not change production search or complete its
release gates. The existing sqlite-jdbc 3.53.4.0 dependency supplies SQLite 3.53.4 / FTS5.

## Reproduce

```sh
make sqlite-bench
# Optional bounded read-only local history cost; private text/indexes remain in RAM:
SESHLOG_SQLITE_REAL=1 make sqlite-bench
# Ordinary correctness tests, no model or private histories:
./gradlew test --offline --tests '*SqliteBm25PrototypeTest' --tests '*SemanticPrototypeTest'
# Rerun the original synthetic model comparison with the existing local GGUF:
SESHLOG_EMBED_MODEL="$PWD/build/semantic-models/embeddinggemma-300M-Q8_0.gguf" make semantic-bench
```

`build/sqlite-bench.log` contains the complete report; the target suppresses synthetic per-case
ranks on the console. All three benchmark methods are skipped in normal tests. Synthetic SQLite
files are temporary and removed in finally blocks. No new dependencies or external services.
Private history selection uses the same root overrides as the original semantic benchmark, ten
recent sessions per provider and at most 16 passages per session. It never persists a private FTS
index. Its results are aggregate counts/timings, not relevance measurements.

Committed evidence:

- [SQLite run](benchmarks/sqlite-bm25-2026-10-25.jsonl): synthetic per-case ranks, timing samples
  summarized by percentile, storage measurements and aggregate private-history costs.
- [Fresh model comparison](benchmarks/embeddinggemma-comparison-2026-10-25.jsonl): rerun of the
  original synthetic experiment, including model checksum and the 768/256/128-dimensional results.
- [Original experiment](semantic-search-prototype.md): model setup, historical results and limits.

## Fair comparison

The unchanged fixture has 30 sessions, 90 passages, 40 positive queries and four absent queries.
SHA-256: `05158f4895ed737472cc2bc26e3a6d5f0985b8253b4aa1e07d0a6713449b7537`.
Labels were not changed or tuned to SQLite. All BM25 methods use the same title-prefixed passages,
OR across distinct ordinary query tokens and best-passage aggregation per session. They deliberately
retain the old prototype's bag-of-words behavior even for quoted queries, not production exactness.

The old BM25 adapter re-tokenizes every document on every query. A new prepared inverted-index
adapter uses its same tokens/formula and is tested against all original fixture rankings, providing
a fairer warm-query comparator. Neither is the final production implementation.

SQLite performs MATCH retrieval, native `bm25()` scoring, passage-to-session grouping and top-100
selection in SQL. It uses a MATERIALIZED scored CTE before grouping, since BM25 needs the FTS cursor.
A separate table stores session/entry associations. Contentless-delete FTS5 stores tokens/positions
and lengths without a duplicate original-text column. It still contains sensitive derived content.

Two predetermined variants isolate document normalization:

- **Aligned:** the original Unicode word/underscore tokenizer and English stopword removal before
  insertion; SQLite still owns the index and scoring.
- **Native:** original passage text inserted directly; `unicode61 remove_diacritics 0 tokenchars '_'`,
  no stemming or document stopword removal. Query normalization is the same in both variants.

FTS5's scoring variant is not numerically identical to the old BM25 formula on every corpus.
Native FTS phrase matching is token-based; literal punctuation/substring verification needs extra
work. The correctness test demonstrates that its phrase `"database locked"` also matches
`database-locked`. [SQLite FTS5 reference](https://www.sqlite.org/fts5.html).

## Relevance

| Method | First result | First five | MRR@5 | Absent queries returning results |
| --- | ---: | ---: | ---: | ---: |
| Current literal search | 15/40 | 16/40 | 0.388 | 0/4 |
| Old / prepared in-memory BM25 | 36/40 | 39/40 | 0.938 | 0/4 |
| SQLite FTS5, aligned | 36/40 | 39/40 | 0.938 | 0/4 |
| SQLite FTS5, native | 35/40 | 39/40 | 0.925 | 0/4 |
| EmbeddingGemma 768 | 40/40 | 40/40 | 1.000 | 4/4 |
| Original literal + Gemma hybrid | 39/40 | 40/40 | 0.988 | 2/4 |

Paraphrase first-results: aligned SQLite and old BM25 21/24; native SQLite 20/24; Gemma 24/24.
The native difference is q01, “stop duplicate requests from charging twice”: `payments` falls from
first to second behind `pool`. Retaining document stopwords changes document lengths and scoring.
Do not call native FTS5 a zero-regression replacement. Both SQLite variants have 11/12 literal
first-results, so technical-query protection still needs production work. Four positive quoted cases
ranking correctly does not establish the phrase contract; all BM25 adapters ignore the quote rules.

This is a small illustrative dataset, not independent held-out validation. No new hybrid was tested.

## Persistence and cost

Reference machine: Apple M1 Max, 64 GB, JDK 25. Final recorded run includes the private RAM benchmark
before synthetic scale; JVM/JIT and filesystem caches are warm. SQLite uses an 8 MiB page-cache
setting per connection, in-memory SQL scratch, DELETE journaling and FULL synchronous writes.
This cache setting is not a measured total process-memory bound.

The load corpus repeats the 30 fixtures into 10,000 synthetic sessions / 30,000 passages,
3,519,271 input prompt bytes. It is deliberately not used to claim large-corpus relevance.

| Method | Build | Warm query p50 / p95 | Saved database |
| --- | ---: | ---: | ---: |
| Prepared in-memory BM25 | 130 ms | 0.83 / 2.76 ms | — |
| SQLite aligned | 199 ms | 2.73 / 8.85 ms | 3,350,528 bytes (3.20 MiB) |
| SQLite native | 110 ms | 2.71 / 8.92 ms | 3,584,000 bytes (3.42 MiB) |

Warm measurements: one complete query warmup followed by three passes over the same 44 queries
(132 samples), top 100 sessions. The old re-tokenizing adapter took 110–111 ms for three positive
queries; that tiny timing sample is not comparable to the 132-query percentile distribution.

Aligned SQLite reopened in 0.37 ms; first query took 3.19 ms (3.56 ms combined). Native reopened
in 0.47 ms; first query took 3.00 ms. No documents were reinserted. Replacing one three-passage
session took 2.61 ms aligned / 2.03 ms native, including two committed transactions (delete then
insert); production must make replacement a single atomic operation. Integrity checks passed.

These are new connections in the same process with a warm filesystem, not new JVMs/cold boots or
full IDE startup. Timings exclude production source-stamp reconciliation, UI delivery, exact-match
verification, metadata field scoring, scope filtering and migration. This run does not measure
peak native/JVM RSS, WAL behavior, export/import or failure recovery. Those remain Phase 1 gates.

The bounded private run selected 40 sessions / 536 passages; 32 sessions exceeded the passage cap
and nine had provider partial coverage. Discovery/extraction/chunking took 3.13 s. Prepared BM25
built in 25.6 ms with query p95 0.052 ms; aligned RAM-only FTS5 built in 14.1 ms with p95 0.150 ms;
native RAM-only FTS5 built in 5.4 ms with p95 0.140 ms. These use public synthetic queries, lack
private relevance labels and cannot demonstrate disk-restart benefits. The earlier Gemma sample
also contained 536 passages, but histories changed; it is not an identical frozen corpus.

## Decision

Use persistent SQLite FTS5 for Phase 1. It matches the old lexical baseline when normalization is
aligned, offers usable measured latency and avoids rebuilding the saved index on every restart.
An optimized RAM index remains faster in this test; the decision rests on durability and existing
index machinery. Phase 1 must still implement source validation, exact/phrase behavior, separate
metadata scoring, incremental transactions, resource bounds, safe failure handling and portability.
Phase 2 adds the optional external embedding server and a separate durable vector cache.
