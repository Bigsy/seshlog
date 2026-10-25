# Local semantic search experiment

This is a test-only EmbeddingGemma prototype, requested on 2026-10-25. It does not change the
plugin's search UI, ship a model, add a production dependency, or start any background service.
The benchmark calls the real `ContentSearchIndex` as its literal baseline.

## Reproduce

Requires JDK 25, the project's Gradle dependencies, and a local `llama-embedding` executable.
The runner uses `/dev/stdin` (macOS/Linux); Windows is not supported by this experiment.
Tested with llama.cpp 0.4.0, build 10809, commit `5266f24da`, on an Apple M1 Max with 64 GB RAM.
Inference explicitly uses CPU, four threads, mean pooling, a 512-token context/batch, and L2
normalization. Metal/GPU performance and a resident model service have not been measured.

Optional one-time setup download, outside inference:

```sh
mkdir -p build/semantic-models
curl --fail --location \
  'https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/resolve/0f741b5a6585bd53aeb15cd1372c56f2a0f65e12/embeddinggemma-300M-Q8_0.gguf' \
  --output build/semantic-models/embeddinggemma-300M-Q8_0.gguf
shasum -a 256 build/semantic-models/embeddinggemma-300M-Q8_0.gguf
```

Expected SHA-256: `b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63`.
File size: 333,590,944 bytes. This is **EmbeddingGemma 300M Q8_0**, not EmbeddingGemma 2.
The weights remain under ignored `build/`; they are not redistributed in the repository/plugin.
See the [original model card and Gemma terms](https://huggingface.co/google/embeddinggemma-300m)
and [llama.cpp's GGUF distribution](https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF).

```sh
# Synthetic retrieval quality only:
SESHLOG_EMBED_MODEL="$PWD/build/semantic-models/embeddinggemma-300M-Q8_0.gguf" make semantic-bench

# Also measure bounded, read-only local history indexing:
SESHLOG_EMBED_MODEL="$PWD/build/semantic-models/embeddinggemma-300M-Q8_0.gguf" \
  SESHLOG_SEMANTIC_REAL=1 make semantic-bench

# Correctness tests without any model:
./gradlew test --offline --tests '*SemanticPrototypeTest'
```

`SESHLOG_EMBED_BIN` overrides the executable. `SESHLOG_SEMANTIC_SESSIONS` overrides the number
of recent sessions selected **per provider** (default 10, maximum 100). The real benchmark uses
the usual home-directory roots; override them with `SESHLOG_BENCH_CLAUDE_ROOT`,
`SESHLOG_BENCH_CODEX_ROOT`, `SESHLOG_BENCH_PI_ROOT` (the sessions directory), or
`SESHLOG_BENCH_OPENCODE_ROOT` when needed. These are benchmark settings, not IDE settings.

The target forces test reruns and writes `build/semantic-bench.log`, including synthetic query IDs
and per-case ranks. Console output omits those individual cases. Normal `make check` skips both
model benchmarks unless explicitly opted in; no CI model download is needed.

## Retrieval design

- Synthetic labels live in `src/test/resources/fixtures/semantic/retrieval.json`: 30 sessions,
  90 passages, 24 paraphrase queries, 12 literal queries, four quoted queries and four absent queries.
  Expected labels were authored before running the model. These are short, deliberately clear
  examples, not a held-out or representative evaluation of private histories.
- Literal results come from the production search code, including title bonuses and recency.
- BM25 is an additional model-free baseline on the same passages, with a small English stopword
  list, `k1=1.2`, `b=0.75`, no stemming and max-passage aggregation. It is a basic test implementation,
  not an optimized lexical index. It does not implement the production quoted-phrase contract.
- Documents use the model's retrieval title/text prefix; queries use its search-result prefix.
  See [Google's prompt instructions](https://huggingface.co/google/embeddinggemma-300m#prompt-instructions).
- Message windows contain at most 320 UTF-8 bytes, with 40 code points of overlap and a title
  prefix capped at 128 bytes. These conservative byte limits fit the 512-token native batch even
  with byte-fallback tokenization. They deliberately trade context for runtime memory; longer,
  tokenizer-aware windows should be evaluated before integration.
- Dialogue and searchable tool entries are included. Coverage markers are excluded. The highest
  cosine passage score represents its session and retains its entry ID for eventual navigation.
- Hybrid uses reciprocal rank fusion (`k=60`) over literal results and the top 20 semantic
  **sessions**. Queries containing a quoted phrase use literal results exclusively, preserving
  exact-search behavior. BM25 is a separate comparison, not part of this hybrid.
- 256/128-dimensional variants truncate the 768-vector and then renormalize. They reduce vector
  storage/comparison cost, not the model's inference cost.

## Observed results, 2026-10-25

For the 40 queries with one labelled relevant session:

| Method | Correct first result | Relevant in first five | MRR@5 |
| --- | ---: | ---: | ---: |
| Current literal search | 15/40 | 16/40 | 0.388 |
| BM25 | 36/40 | 39/40 | 0.938 |
| EmbeddingGemma, 768 dimensions | 40/40 | 40/40 | 1.000 |
| Literal + semantic hybrid, 768 dimensions | 39/40 | 40/40 | 0.988 |
| EmbeddingGemma, 256 dimensions | 37/40 | 40/40 | 0.963 |
| EmbeddingGemma, 128 dimensions | 33/40 | 40/40 | 0.898 |

For paraphrases specifically: literal 0/24 first, BM25 21/24, semantic/hybrid 24/24 at 768 dimensions.
The one hybrid difference at 768 dimensions is the quoted `generation counter` case: preserving
the current substring/recency rules ranks a discussion-only distractor ahead of the intended session.

**Abstention is unresolved.** Semantic search returns results for all four absent queries; hybrid
does so for the two unquoted absent queries. Literal and BM25 return none on these four examples.
No similarity threshold was tuned against this tiny dataset. These results support evaluating
semantic retrieval further, not claiming 100% real-world accuracy or shipping unfiltered results.
Much of the gain over strict AND search is already available with model-free lexical ranking.

Resource observations (wall time includes process launch, model loading and native warmup):

| Measurement | Observed |
| --- | ---: |
| Synthetic documents: 90 passages | 2.83 s |
| Synthetic queries: 44 in one batch | 1.68 s |
| Synthetic document process peak RSS | 1.34 GB |
| Individual query with a fresh process | approximately 1.0–1.1 s |
| Local history sample: 40 sessions, 536 passages | 43.69 s |
| History document process peak RSS | 1.35 GB |
| History vector ranking, 536 passages | 1 ms |
| 768-dimensional float vectors for those passages | 1,646,592 bytes |

The history scan found 49 Claude, 29 Codex, 90 Pi and 38 opencode sessions. It selected ten recent
sessions per provider and at most 16 passages per session, with an overall 1,024-passage cap.
32 selected sessions exceeded the passage cap; ten had provider-reported partial coverage.
The global cap omitted no further passages in this run. This is a **bounded indexing-cost sample**,
not full-history indexing and not a quality evaluation. No relevance labels exist for that data.
The 155 ms literal timing in this run searched the provider-extracted entries, whereas embeddings
covered only the capped sample; do not treat those times as equivalent-coverage measurements.

Initial 2,048-token batch settings peaked near 3.0 GB and took 23.7 s on the same synthetic
documents. Reducing the batch to 512 cut both substantially; the initial large real-data run was
stopped to make that change. Recorded final history measurements use the smaller batch.
RSS is `/usr/bin/time -l`'s native child-process peak on macOS, including mapped model pages;
it excludes the JVM. On other operating systems the runner leaves RSS unreported. Vector bytes
are raw float payload only, excluding object/string overhead. These are single-run observations,
not stable latency percentiles or cold filesystem-cache measurements.

## Privacy and next decision

The benchmark reads agent-owned files through existing providers with no persistent parse cache.
Prompts travel over anonymous pipes to `llama-embedding --offline`; no local HTTP server is used.
Neither extracted text nor vectors are written to disk, placed on the command line, or printed.
Native stderr is drained and discarded except for the numeric RSS measurement. Native failures
produce sanitized errors. Reports contain synthetic case IDs and aggregate real-history metrics.

Before a plugin feature, evaluate a larger independently judged query set, more realistic long
conversations and distractors, no-result behavior, BM25 + semantic fusion, and native runtime
memory/latency on supported machines. A resident worker, background indexing, incremental updates,
cancellation and memory budgets remain unimplemented. Persisting embeddings would require revisiting
the in-memory-content-index constraint; this prototype keeps them in memory. Rebuilding a full
history after every restart could be expensive. No `make run` acceptance check applies yet because
this experiment has no UI integration.
