package com.hedworth.seshlog.semantic

import com.google.gson.Gson
import com.hedworth.seshlog.index.ContentSearchIndex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil

/** Same frozen labels/passages as the Gemma experiment. Only synthetic inputs reach disk. */
class SqliteSearchBenchmark {
    @Test
    fun syntheticQualityAndPersistence() {
        assumeTrue(System.getenv("SESHLOG_SQLITE_BENCH") == "1")
        val corpus = retrievalCorpus()
        val chunks = corpus.sessions.flatMap { SemanticPrototype.chunks(it, corpus.entries.getValue(it.id)) }
        val fixture = javaClass.getResourceAsStream("/fixtures/semantic/retrieval.json")!!.use { it.readAllBytes() }
        report("fixture", mapOf("sha256" to MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) },
            "sessions" to corpus.sessions.size, "chunks" to chunks.size, "queries" to corpus.queries.size))
        val literal = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { corpus.entries.getValue(it.id) })
        quality("literal", corpus, corpus.queries.map { literal.search(it.text, corpus.sessions).map { hit -> hit.session.id } })
        quality("old_bm25", corpus, corpus.queries.map { SemanticPrototype.bm25(chunks, it.text) })
        val prepared = PreparedBm25Prototype(chunks)
        quality("prepared_bm25", corpus, corpus.queries.map { prepared.search(it.text) })
        for (mode in SqliteBm25Prototype.Tokenization.entries) {
            withDatabase { db ->
                val start = System.nanoTime()
                SqliteBm25Prototype.open(db).use { index ->
                    index.create(); index.insert(chunks, mode)
                    report("index", mapOf("method" to mode.name, "sqliteVersion" to index.version(), "buildMs" to ms(start)))
                    quality("fts5_${mode.name.lowercase()}", corpus, corpus.queries.map { index.search(it.text) })
                    index.integrityCheck()
                }
                report("storage", mapOf("method" to mode.name, "bytes" to Files.size(db)))
                val openStart = System.nanoTime()
                SqliteBm25Prototype.open(db).use { reopened ->
                    val openMs = ms(openStart)
                    val firstStart = System.nanoTime()
                    val result = reopened.search(corpus.queries.first().text)
                    report("reopen", mapOf("method" to mode.name, "openMs" to openMs, "firstQueryMs" to ms(firstStart),
                        "hits" to result.size, "documentsReinserted" to 0, "filesystemCache" to "warm"))
                    timings("fts5_${mode.name.lowercase()}", "fixture", corpus.queries.map { it.text }, 10, search = { reopened.search(it) })
                }
            }
        }
        timings("prepared_bm25", "fixture", corpus.queries.map { it.text }, 10, search = { prepared.search(it) })
        timings("old_bm25", "fixture", corpus.queries.map { it.text }, 3) { SemanticPrototype.bm25(chunks, it) }
    }

    @Test
    fun syntheticTenThousandSessionScale() {
        assumeTrue(System.getenv("SESHLOG_SQLITE_BENCH") == "1")
        val corpus = retrievalCorpus()
        val start = System.nanoTime()
        // Repetition is a storage/load workload, never used for relevance claims.
        val chunks = (0 until 10_000).flatMap { i ->
            val original = corpus.sessions[i % corpus.sessions.size]
            SemanticPrototype.chunks(original, corpus.entries.getValue(original.id)).mapIndexed { j, chunk ->
                chunk.copy(sessionId = "synthetic-$i", entryId = "synthetic-$i:$j")
            }
        }
        report("scaleCorpus", mapOf("sessions" to 10_000, "chunks" to chunks.size,
            "promptBytes" to SemanticPrototype.bytes(chunks), "chunkConstructionMs" to ms(start)))
        val queries = corpus.queries.map { it.text }
        val preparedStart = System.nanoTime()
        val prepared = PreparedBm25Prototype(chunks)
        report("scaleBuild", mapOf("method" to "prepared_bm25", "buildMs" to ms(preparedStart)))
        timings("prepared_bm25", "10k_sessions", queries, 3, search = { prepared.search(it) })
        // The old implementation tokenizes every document on every query; keep this bounded.
        timings("old_bm25", "10k_sessions", queries.take(3), 1, warmups = 0) { SemanticPrototype.bm25(chunks, it).take(100) }
        for (mode in SqliteBm25Prototype.Tokenization.entries) {
            withDatabase { db ->
                val buildStart = System.nanoTime()
                SqliteBm25Prototype.open(db).use { index ->
                    index.create(); index.insert(chunks, mode)
                    report("scaleBuild", mapOf("method" to "fts5_${mode.name.lowercase()}", "buildMs" to ms(buildStart)))
                }
                report("scaleStorage", mapOf("method" to mode.name, "bytes" to Files.size(db)))
                val openStart = System.nanoTime()
                SqliteBm25Prototype.open(db).use { index ->
                    val openMs = ms(openStart)
                    val queryStart = System.nanoTime()
                    index.search(queries.first())
                    report("scaleReopen", mapOf("method" to mode.name, "openMs" to openMs,
                        "firstQueryMs" to ms(queryStart), "documentsReinserted" to 0, "filesystemCache" to "warm"))
                    timings("fts5_${mode.name.lowercase()}", "10k_sessions", queries, 3, search = { index.search(it) })
                    val updateStart = System.nanoTime()
                    index.deleteSession("synthetic-0")
                    index.insert(chunks.take(3).map { it.copy(prompt = "title: replacement | text: uniquechangedtoken") }, mode,
                        chunks.size.toLong() + 1)
                    check(index.search("uniquechangedtoken") == listOf("synthetic-0"))
                    report("scaleUpdate", mapOf("method" to mode.name, "replaceOneSessionMs" to ms(updateStart)))
                    index.integrityCheck()
                }
            }
        }
    }

    @Test
    fun boundedLocalHistoryCost() {
        assumeTrue(System.getenv("SESHLOG_SQLITE_BENCH") == "1" && System.getenv("SESHLOG_SQLITE_REAL") == "1")
        val home = Path.of(System.getProperty("user.home"))
        fun root(variable: String, fallback: String) = System.getenv(variable)?.let(Path::of) ?: home.resolve(fallback)
        val providers = listOf(
            com.hedworth.seshlog.claude.ClaudeCodeSessionProvider({ root("SESHLOG_BENCH_CLAUDE_ROOT", ".claude") }, { "claude" }),
            com.hedworth.seshlog.codex.CodexSessionProvider({ root("SESHLOG_BENCH_CODEX_ROOT", ".codex") }, { "codex" }),
            com.hedworth.seshlog.pi.PiSessionProvider({ root("SESHLOG_BENCH_PI_ROOT", ".pi/agent/sessions") }, { "pi" }),
            com.hedworth.seshlog.opencode.OpenCodeSessionProvider({ root("SESHLOG_BENCH_OPENCODE_ROOT", ".local/share/opencode") }, { "opencode" }),
        )
        val chunks = ArrayList<SemanticPrototype.Chunk>()
        var selected = 0
        var clipped = 0
        var partial = 0
        val extractStart = System.nanoTime()
        for (provider in providers) {
            val scanStart = System.nanoTime()
            val sessions = provider.scan(emptyMap()).sortedByDescending { it.lastActivityAt }
            report("realProvider", mapOf("provider" to provider.kind.name, "available" to sessions.size,
                "selected" to minOf(10, sessions.size), "scanMs" to ms(scanStart)))
            for (session in sessions.take(10)) {
                selected++
                val entries = provider.conversationEntries(session)
                if (entries.any { it.truncated || !it.searchable }) partial++
                val passages = SemanticPrototype.chunks(session.copy(id = "${provider.kind}:${session.id}"), entries)
                if (passages.size > 16) clipped++
                chunks += passages.take(16)
            }
        }
        report("realCorpus", mapOf("sessions" to selected, "chunks" to chunks.size, "clippedSessions" to clipped,
            "providerPartialSessions" to partial, "scanExtractChunkMs" to ms(extractStart)))
        val queries = retrievalCorpus().queries.map { it.text } // Public synthetic queries, no private labels.
        val buildStart = System.nanoTime()
        val prepared = PreparedBm25Prototype(chunks)
        report("realBuild", mapOf("method" to "prepared_bm25", "buildMs" to ms(buildStart)))
        timings("prepared_bm25", "bounded_real", queries, 3, search = { prepared.search(it) })
        timings("old_bm25", "bounded_real", queries, 1) { SemanticPrototype.bm25(chunks, it) }
        for (mode in SqliteBm25Prototype.Tokenization.entries) {
            val start = System.nanoTime()
            // Private text and its token index remain in RAM. Persistence is tested on synthetic data.
            SqliteBm25Prototype.open().use { index ->
                index.create(); index.insert(chunks, mode)
                report("realBuild", mapOf("method" to "fts5_${mode.name.lowercase()}", "buildMs" to ms(start), "storage" to "memory"))
                timings("fts5_${mode.name.lowercase()}", "bounded_real", queries, 3, search = { index.search(it) })
            }
        }
    }

    private fun quality(method: String, corpus: RetrievalCorpus, rankings: List<List<String>>) {
        for (category in listOf("all", "semantic", "literal", "phrase", "absent")) {
            val positions = corpus.queries.indices.filter { category == "all" || corpus.queries[it].category == category }
            val positives = positions.filter { corpus.queries[it].relevant.isNotEmpty() }
            val negatives = positions.filter { corpus.queries[it].relevant.isEmpty() }
            report("quality", mapOf("method" to method, "category" to category, "queries" to positives.size,
                "hitAt1" to positives.count { rankings[it].firstOrNull() in corpus.queries[it].relevant },
                "hitAt5" to positives.count { i -> rankings[i].take(5).any { it in corpus.queries[i].relevant } },
                "mrrAt5" to positives.map { i -> SemanticPrototype.reciprocalRank(rankings[i], corpus.queries[i].relevant) }.average().takeIf { it.isFinite() },
                "absentQueries" to negatives.size, "absentWithResults" to negatives.count { rankings[it].isNotEmpty() }))
        }
        corpus.queries.forEachIndexed { i, query ->
            report("case", mapOf("method" to method, "id" to query.id, "top" to rankings[i].firstOrNull(),
                "rank" to rankings[i].indexOfFirst { it in query.relevant }.let { if (it < 0) null else it + 1 }))
        }
    }

    private fun timings(method: String, corpus: String, queries: List<String>, repeats: Int,
                        warmups: Int = 1, search: (String) -> List<String>) {
        repeat(warmups) { queries.forEach { search(it) } }
        var hits = 0L
        val times = buildList {
            repeat(repeats) { queries.forEach { query ->
                val start = System.nanoTime(); hits += search(query).size; add(ms(start))
            } }
        }.sorted()
        report("latency", mapOf("method" to method, "corpus" to corpus, "samples" to times.size,
            "p50Ms" to times[(ceil(times.size * 0.5).toInt() - 1).coerceAtLeast(0)],
            "p95Ms" to times[(ceil(times.size * 0.95).toInt() - 1).coerceAtLeast(0)],
            "maxMs" to times.last(), "returnedHits" to hits))
    }

    private fun withDatabase(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("seshlog-synthetic-benchmark-")
        try { action(dir.resolve("index.sqlite")) }
        finally { Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }; Files.delete(dir) }
    }
    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun report(event: String, fields: Map<String, Any?>) = println("SESHLOG_SQLITE " + Gson().toJson(mapOf("event" to event) + fields))
}
