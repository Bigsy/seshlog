# Search source filters — implementation and validation, 2026-10-26

This records the implemented source controls and match provenance. [PLAN.md](../PLAN.md) tracks
the current acceptance checks and performance decision, consolidated from the completed
source-filter handoff (`fcd0d57:PLAN-filter.md` in Git). This report does not complete the broader
Phase 1 release gates or implement semantic search. The original frozen corpus and
[2026-10-25 report](production-bm25.md) remain unchanged.

## Behavior and provenance

Search in is visible beside the field. Conversation is the default: Your messages and Assistant
replies on, Tool activity off. All eight selections are valid, including Titles & paths with all
three off. Titles (provider and local override) and project paths are always eligible. Preferences
persist in app-level `seshlog.xml`, synchronize across project panels, and survive unrelated Settings
bindings. They are independent of index imports/exports.

`SearchSources`, `SearchContext`, the kind-first classifier and `SearchPrimaryMatch` are immutable
and independent of IntelliJ. Tool calls/results take precedence over recorded role, including
USER-role tool results and Claude subagent dialogue. Coverage notices never supply evidence.
Pi's existing dialogue-only reader remains unchanged.

Selections constrain SQL candidates/statistics, exact tiers, mandatory phrases, substring/Unicode/
stopword fallbacks, primary snippets and navigation. All supported sources remain collected/indexed
within the existing shared retention bounds. Disabled categories are intentional exclusions; real
provider retention loss remains partial. Metadata-only searches neither require body reads nor mark
an unvalidated body current. Metadata evidence survives failed body reads.

A row's Title / Project path / You / Assistant / Tool call / Tool result label describes its actual
excerpt. Metadata has a variant identity and no fabricated conversation target. Body identity,
range and hash refer to one original eligible entry. Opening checks category as well as ID/range/hash.
The preview follows the current context. An opened full editor retains its original query/options,
shows their summary and body-match count, and maps Seshlog highlights/F3 through original-entry
ranges into Markdown offsets. Headers, labels, dates, separators, coverage and excluded sources are
not navigation evidence. Full conversation context, normal IDE Find, copying, latest-reply positioning
and completion/unread behavior remain available.

## Storage and transition

Schema **3**, classification **1**, normalization **1** uses contentless-delete FTS5 populations:
`title`, `path`, `user_body`, `assistant_body`, `tool_body`. Associations retain tool-call/result
category, metadata variant, entry ID, original UTF-16 window bounds and source hash. Classification
changes require a schema-version change. SQL computes max(provider/local title), path, and the best
body across enabled categories, then combines **8/3/1**. Body categories never sum echoed evidence.
MATERIALIZED MATCH/BM25 cursors and deterministic source/entry/range ties remain in SQLite.
Protected full-query evidence takes precedence when choosing an explanation.

The 100,000-passage ceiling is shared across body categories; source, disk/recovery, lease, source
buffer, cancellation and transaction bounds remain. Source replacements move categories atomically.
Compatible restarts and toggles reuse all populations. Metadata renames do not extract bodies.
No raw transcript copies, snippets, query history or preference are persisted in/exported with the DB.

Format 2 cannot identify roles. It is preserved intact and produces a specific local-Rebuild remedy,
with correctly filtered temporary search until that explicit action. Future formats are preserved
as well. Rebuild uses the existing recovery-copy and disk policy. Legacy/unknown/unsafe snapshots
are rejected without mutating the active store; rebuild and re-export in the original IDE.
New imports validate the exact schema, allowed field/category/variant combinations, posting
associations and bounds. Dense token batches adapt downward instead of rejecting valid exports.
Imported source identities still require validation against discovered local sources.

## Frozen source-labelled evaluation

`fixtures/search/source-retrieval-v1.json` SHA-256:
`6480eef5e1a16d3900099f523110c7c87312bc374fd5f2a3b6f965e96714423f`.
Eight synthetic sessions and eleven queries run on the same retained text in three modes. Cases
cover a misleading large tool result, a solution found only in an assistant reply, task requests,
repeated echoes, a tool-only technical error, subagent classification, metadata overrides/paths,
multiple eligible phrases and absent queries. The earlier `source-contract.json` freezes the
classifier's individual-entry expectations separately.

| Mode | Hit@1 / recall@5 / MRR@5 / nDCG@10 | No-answer false-positive rate | Preferred primary agreement |
| --- | --- | --- | --- |
| Conversation | 1.000 | 0 | 7/7 |
| Your messages | 1.000 | 0 | 5/5 |
| All activity | 1.000 | 0 | 11/12 |

All 33 query/configuration inclusion sets match the frozen labels. Intentionally excluded tool
answers are no-answer cases in Conversation/Your messages. All activity's repeated `echo` case
selects Tool result instead of the fixture's preferred You evidence: its independent BM25 population
wins the fixed body-max comparison. The snippet/category/range are truthful. This disagreement is
retained in the report; labels and weights were not changed to make evidence agreement perfect.
Separate populations intentionally change corpus statistics and can change ranks/evidence.
These small synthetic labels are contract evidence, not a representative history-quality claim.

## Synthetic cost and reuse

Reproduce with `make lexical-bench`. The [JSONL report](benchmarks/search-source-filters-2026-10-26.jsonl)
contains all source cases, original frozen-corpus comparisons, three independent builds at each
scale, source/tokenization counters, category counts and aggregate resource samples.

Apple M1 Max, 64 GiB, JBR/JDK 25.0.4, IntelliJ 2026.2.3 (262.10968.63), SQLite JDBC 3.53.4.0.
A normal IDE remained open; CPU/GC/background activity was not controlled. Each warm p95 uses
12 samples (three passes over four queries after warmup). Source-labelled scale runs repeat the
eight templates, with synthetic original files for portable validation; stamps are cheap integers.
These are neither IDE startup timings nor real provider discovery/stat costs.

| Scale/mode | Warm p50, range over 3 builds | Warm p95, range over 3 builds |
| --- | --- | --- |
| 1k Conversation | 9.15–10.32 ms | 11.55–12.23 ms |
| 1k All activity | 21.95–23.10 ms | 46.77–47.57 ms |
| 1k Titles & paths | 4.69–5.47 ms | 5.35–6.56 ms |
| 10k Conversation | 89.25–91.06 ms | **104.25–112.57 ms** |
| 10k All activity | 215.38–219.40 ms | **452.14–474.91 ms** |
| 10k Titles & paths | 42.54–59.47 ms | 52.16–85.19 ms |

The unchanged **warm p95 <100 ms reference target fails at 10k** for Conversation and All activity.
The original repeated 200-session scaffold also regressed: schema-3 10k p95 is 145.96–172.92 ms,
compared with the historical schema-2 84–89 ms. No target was relaxed. Exact/phrase lanes still
verify the complete selected scope before limiting results; broad enabled-tool verification and
SQL provenance/statistics work are part of these costs. Further profiling is required before
claiming the performance gate passed.

| Cost | 1k source-labelled sessions | 10k source-labelled sessions |
| --- | --- | --- |
| Fresh build + first query | 1.34–1.43 s | 16.86–20.13 s |
| Mean toggle query, six alternating technical searches | 12.15–14.91 ms | 116.90–157.60 ms |
| Reopen + reconcile + metadata query | 8.63–9.52 ms | 70.08–84.42 ms |
| Export | 5.94–6.72 ms | 43.68–63.63 ms |
| Import reconstruction | 158.84–163.18 ms | 2.32–2.39 s |
| Imported-source hash validation + metadata query | 382.68–463.19 ms | 3.93–4.79 s |
| Main DB | 1,183,744 bytes | 10,665,984 bytes |
| Retained source UTF-16 units | 581,125 | 5,811,250 |

Warm searches and toggles made **zero source extractions and zero tokenizations** in these retained
workloads. Each cold build extracted each source once, with 4,500 / 45,000 field tokenizations.
Reopen and validated import metadata queries each made zero full extractions/tokenizations;
portable file hashing is measured separately. Real supporting snippets/exact verification may
read original sources after eviction/restart, as the integration tests explicitly demonstrate.

At 10k there are 8,750 user, 5,000 assistant and 10,000 tool passages: **23,750 shared body rows**,
plus 11,250 titles and 10,000 paths. The bounded long-tool case retains 396,019 UTF-16 units in
201 passages. Its warm p95 is 0.66 ms for Conversation and 14.35 ms for All activity, with no new
reads/tokenizations. Shared truncation/omission warnings have separate regressions.

Whole-JVM sampled heap across the run was 39–222 MB; source-scale RSS samples were about 515–673 MB.
These samples include the test JVM and are not SQLite-only/search-only allocations. They do not
prove the 128 MiB logical search budget, worst-case native scratch, lower-memory performance,
or long real-history behavior. Those broader PLAN.md gates remain open.

## Review fixes and remeasurement

The review found that a partial metadata match could suppress protected technical or quoted-query
evidence in a selected body, and that preview navigation could start at the wrong occurrence after
opening a full-query range. Both now have regression coverage, including selected-source exclusion,
ranking beyond 100 results, failed-read coverage, expanded tools and both navigation directions.

The source-scale benchmark was rerun after the fixes, using the same synthetic corpus and three
independent builds. The [new JSONL](benchmarks/search-source-filters-review-fixes-2026-10-26.jsonl)
contains the aggregate measurements. Run this focused benchmark with:

```sh
SESHLOG_LEXICAL_BENCH=1 ./gradlew test --offline \
  --tests '*SourceLexicalSearchBenchmark.classifiedScaleAndReuse' --rerun -i
```

| Scale/mode | Warm p50, range over 3 builds | Warm p95, range over 3 builds |
| --- | --- | --- |
| 1k Conversation | 9.07–14.15 ms | 11.38–16.73 ms |
| 1k All activity | 23.33–28.98 ms | 45.77–49.56 ms |
| 1k Titles & paths | 4.72–6.59 ms | 5.27–9.20 ms |
| 10k Conversation | 90.85–92.42 ms | **108.60–131.93 ms** |
| 10k All activity | 233.45–235.74 ms | **439.99–449.13 ms** |
| 10k Titles & paths | 42.15–42.53 ms | 51.49–52.64 ms |

Warm searches made zero source extractions and zero tokenizations in this retained-source workload.
It does not measure the cost of verifying evicted sources. The 10k p95 target remains unmet for
Conversation and All activity, and uncontrolled background activity limits before/after comparison.
The physical UI and broader release-acceptance checks below remain outstanding.

Final fix validation: `./gradlew test buildPlugin verifyPlugin --offline` passed with 536 tests,
zero failures/errors and 10 opt-in skips. Plugin Verifier 1.411 reports compatibility with
IU-262.10968.63 and IU-263.7307.44, with the same scheduled-for-removal, deprecated and experimental
API counts recorded below. The rebuilt ZIP passed `unzip -tq`; its SHA-256 is
`24b2ea6a46723ddaa2a18d578dc6f8e43a840a278b9ea5a59d70f5451cce58e5`.

## Acceptance and handoff

At the original implementation validation, automated checks passed: `make check`, `make lexical-bench`,
`make release`, and offline Plugin Verifier on IU-262.10968.63 / IU-263.7307.44. The verifier reports compatibility, with 2 scheduled-
for-removal usages per IDE, 14/17 deprecated usages and 65/58 experimental usages respectively.
The ZIP contains the production plugin, SQLite JDBC and searchable-options jars; inspection found
no tests, evaluation/benchmark classes, fixtures or transcript resources. The final counts and
artifact hash are recorded in the review-fix section above; implementation commits are listed below.

The isolated sandbox attempt did not establish usable manual evidence. The first Groovy init-script
launch failed with `Unsupported class file major version 71`. Retrying on the required JDK 25
launched the task sandbox, but process inspection showed repository-default config/system/log paths
rather than the prepared temporary profile. The UI connector could not bind the Java app returned
as Main. Its PID was matched to the cached sandbox Java executable, repository sandbox arguments
and this task's temporary project, then stopped with SIGTERM. No daily-IDE interaction or manual
acceptance row is counted as passed. Manual acceptance is not inferred from platform unit tests. Still verify
physical two-window behavior, all eight combinations on real UI sessions, keyboard/accessibility,
narrow layouts, light/dark themes, provenance-to-passage opening, normal Find/copy/unread behavior,
and rebuild/restart/export/import in the intended sandbox profile. Supported OS, lower-memory and
broader real-history gates in PLAN.md are unchanged. No Marketplace upload or daily-IDE installation
is part of this work.

Implementation work items are recorded in these focused commits: `b3a041b` (source domain and
frozen contracts), `5004ebc` (classified durable retrieval), `1bebda9` (saved control and delivery),
`6356e97` (provenance and captured navigation), `9fe89c4` (evaluation, report and acceptance),
`5d12013` (protected body evidence), and `fcd0d57` (navigation from the opened passage).
No publication, daily-IDE installation or Phase 2 work was performed.
