package com.hedworth.seshlog.semantic

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.index.evaluation.LegacyLiteralSearchIndex as ContentSearchIndex
import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.opencode.OpenCodeSessionProvider
import com.hedworth.seshlog.pi.PiSessionProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

internal data class RetrievalQuery(val id: String, val category: String, val text: String, val relevant: Set<String>)
internal data class RetrievalCorpus(
    val sessions: List<Session>, val entries: Map<String, List<ConversationEntry>>, val queries: List<RetrievalQuery>,
)

internal fun retrievalCorpus(): RetrievalCorpus {
    val resource = SemanticSearchBenchmark::class.java.getResourceAsStream("/fixtures/semantic/retrieval.json")!!
    val root = resource.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    check(root["synthetic"].asBoolean)
    val entries = LinkedHashMap<String, List<ConversationEntry>>()
    val sessions = root["sessions"].asJsonArray.mapIndexed { index, row ->
        val obj = row.asJsonObject
        val id = obj["id"].asString
        entries[id] = obj["messages"].asJsonArray.mapIndexed { entryIndex, message ->
            val m = message.asJsonObject
            ConversationEntry(ConversationMessage(if (entryIndex == 0) Role.USER else Role.ASSISTANT,
                m["text"].asString, null), "$id:$entryIndex",
                if (m["tool"]?.asBoolean == true) EntryKind.TOOL_RESULT else EntryKind.DIALOGUE)
        }
        Session(AgentKind.CLAUDE_CODE, id, obj["title"].asString, Path.of(obj["cwd"].asString),
            null, null, Instant.ofEpochSecond(index.toLong()), null, false, null, null, 1, true)
    }
    val queries = root["queries"].asJsonArray.map { row ->
        val obj = row.asJsonObject
        RetrievalQuery(obj["id"].asString, obj["category"].asString, obj["text"].asString,
            obj["relevant"].asJsonArray.map { it.asString }.toSet())
    }
    return RetrievalCorpus(sessions, entries, queries)
}

/** Explicit opt-in only. All real-data output is aggregate counts, resource usage and timings. */
class SemanticSearchBenchmark {
    private fun engine(): LlamaEmbedding {
        assumeTrue(System.getenv("SESHLOG_SEMANTIC_BENCH") == "1")
        val model = Path.of(requireNotNull(System.getenv("SESHLOG_EMBED_MODEL")) { "Set SESHLOG_EMBED_MODEL" })
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(model).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        report("model", mapOf("sha256" to digest.digest().joinToString("") { "%02x".format(it) },
            "bytes" to Files.size(model), "device" to "cpu", "threads" to 4))
        return LlamaEmbedding(System.getenv("SESHLOG_EMBED_BIN") ?: "llama-embedding", model)
    }

    @Test
    fun syntheticRetrievalQuality() {
        val engine = engine()
        val corpus = retrievalCorpus()
        val chunks = corpus.sessions.flatMap { SemanticPrototype.chunks(it, corpus.entries.getValue(it.id)) }
        val index = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { corpus.entries.getValue(it.id) })
        val literalStart = System.nanoTime()
        val literal = corpus.queries.map { q -> index.search(q.text, corpus.sessions).map { it.session.id } }
        val literalMs = millis(literalStart)
        val bm25 = corpus.queries.map { SemanticPrototype.bm25(chunks, it.text) }
        val docs = engine.encode(chunks.map { it.prompt })
        val queries = engine.encode(corpus.queries.map { SemanticPrototype.queryPrompt(it.text) })
        report("synthetic", mapOf("sessions" to corpus.sessions.size, "chunks" to chunks.size,
            "queries" to corpus.queries.size, "literalAllQueriesMs" to literalMs,
            "documentEmbeddingMs" to docs.elapsedMs, "queryBatchEmbeddingMs" to queries.elapsedMs,
            "nativePeakRssBytes" to docs.peakRssBytes, "promptBytes" to SemanticPrototype.bytes(chunks)))
        for (dimension in listOf(768, 256, 128)) {
            val vectors = docs.vectors.map { SemanticPrototype.normalize(it, dimension) }
            val semantic = corpus.queries.indices.map { i ->
                SemanticPrototype.rank(chunks, vectors, SemanticPrototype.normalize(queries.vectors[i], dimension))
            }
            val hybrid = corpus.queries.indices.map { i ->
                SemanticPrototype.hybrid(literal[i], semantic[i].map { it.sessionId },
                    TextQuery.parse(corpus.queries[i].text).terms.any { it.phrase })
            }
            report("vectors", mapOf("dimensions" to dimension, "vectorBytes" to chunks.size.toLong() * dimension * 4))
            for ((method, rankings) in listOf("literal" to literal, "bm25" to bm25,
                "semantic" to semantic.map { hits -> hits.map { it.sessionId } }, "hybrid" to hybrid)) {
                for (category in listOf("all", "semantic", "literal", "phrase", "absent")) {
                    val positions = corpus.queries.indices.filter { category == "all" || corpus.queries[it].category == category }
                    val positives = positions.filter { corpus.queries[it].relevant.isNotEmpty() }
                    val negatives = positions.filter { corpus.queries[it].relevant.isEmpty() }
                    report("quality", mapOf("dimensions" to dimension, "method" to method, "category" to category,
                        "queries" to positives.size,
                        "hitAt1" to positives.count { rankings[it].firstOrNull() in corpus.queries[it].relevant },
                        "hitAt5" to positives.count { i -> rankings[i].take(5).any { it in corpus.queries[i].relevant } },
                        "mrrAt5" to positives.map { i -> SemanticPrototype.reciprocalRank(rankings[i], corpus.queries[i].relevant) }.average().takeIf { it.isFinite() },
                        "absentQueries" to negatives.size, "absentWithResults" to negatives.count { rankings[it].isNotEmpty() }))
                }
            }
            corpus.queries.forEachIndexed { i, q ->
                report("case", mapOf("dimensions" to dimension, "id" to q.id,
                    "literalRank" to rank(literal[i], q.relevant),
                    "bm25Rank" to rank(bm25[i], q.relevant),
                    "semanticRank" to rank(semantic[i].map { it.sessionId }, q.relevant),
                    "hybridRank" to rank(hybrid[i], q.relevant), "semanticTopScore" to semantic[i].firstOrNull()?.score))
            }
        }
        // Includes model/process startup on every call: deliberately not advertised as warm latency.
        repeat(3) { run ->
            val result = engine.encode(listOf(SemanticPrototype.queryPrompt(corpus.queries[run].text)))
            report("singleQueryProcess", mapOf("run" to run, "elapsedMs" to result.elapsedMs, "nativePeakRssBytes" to result.peakRssBytes))
        }
    }

    @Test
    fun localHistoryScale() {
        assumeTrue(System.getenv("SESHLOG_SEMANTIC_REAL") == "1")
        val engine = engine()
        val home = Path.of(System.getProperty("user.home"))
        fun root(variable: String, fallback: String) = System.getenv(variable)?.let(Path::of) ?: home.resolve(fallback)
        val providers = listOf(
            ClaudeCodeSessionProvider({ root("SESHLOG_BENCH_CLAUDE_ROOT", ".claude") }, { "claude" }),
            CodexSessionProvider({ root("SESHLOG_BENCH_CODEX_ROOT", ".codex") }, { "codex" }),
            PiSessionProvider({ root("SESHLOG_BENCH_PI_ROOT", ".pi/agent/sessions") }, { "pi" }),
            OpenCodeSessionProvider({ root("SESHLOG_BENCH_OPENCODE_ROOT", ".local/share/opencode") }, { "opencode" }),
        )
        val perProvider = (System.getenv("SESHLOG_SEMANTIC_SESSIONS")?.toInt() ?: 10).also { require(it in 1..100) }
        val allChunks = ArrayList<SemanticPrototype.Chunk>()
        val sessions = ArrayList<Session>()
        val entries = HashMap<String, List<ConversationEntry>>()
        var clipped = 0
        var partial = 0
        for (provider in providers) {
            val start = System.nanoTime()
            val scanned = provider.scan(emptyMap()).sortedByDescending { it.lastActivityAt }
            val scanMs = millis(start)
            val selected = scanned.take(perProvider)
            for (session in selected) {
                val extracted = provider.conversationEntries(session)
                // Provider-prefix avoids cross-provider ID collisions in this combined experiment.
                val scoped = session.copy(id = "${provider.kind}:${session.id}")
                sessions += scoped
                entries[scoped.id] = extracted
                val chunks = SemanticPrototype.chunks(scoped, extracted)
                if (chunks.size > 16) clipped++
                if (extracted.any { it.truncated || !it.searchable }) partial++
                allChunks += chunks.take(16)
            }
            report("realProvider", mapOf("provider" to provider.kind.name, "available" to scanned.size,
                "selected" to selected.size, "scanMs" to scanMs))
        }
        // Quality labels do not exist for private histories: measure cost, never invent relevance scores.
        val chunks = allChunks.take(1024)
        val docs = engine.encode(chunks.map { it.prompt })
        val query = "find the discussion about stale search results"
        val embeddedQuery = engine.encode(listOf(SemanticPrototype.queryPrompt(query)))
        val index = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { entries.getValue(it.id) })
        val literalStart = System.nanoTime()
        val literal = index.search(query, sessions)
        val literalMs = millis(literalStart)
        val rankStart = System.nanoTime()
        val ranked = SemanticPrototype.rank(chunks, docs.vectors, embeddedQuery.vectors.single())
        report("realScale", mapOf("sessions" to sessions.size, "indexedSessions" to chunks.map { it.sessionId }.distinct().size,
            "chunks" to chunks.size, "sessionChunkCap" to 16, "clippedSessions" to clipped,
            "providerPartialSessions" to partial, "globalChunksOmitted" to (allChunks.size - chunks.size),
            "promptBytes" to SemanticPrototype.bytes(chunks), "vectorBytes768" to chunks.size.toLong() * 768 * 4,
            "documentEmbeddingMs" to docs.elapsedMs, "singleQueryProcessMs" to embeddedQuery.elapsedMs,
            "nativePeakRssBytes" to docs.peakRssBytes, "rankMs" to millis(rankStart),
            "literalMs" to literalMs, "literalHits" to literal.size, "semanticHits" to ranked.size))
    }

    private fun rank(results: List<String>, relevant: Set<String>) = results.indexOfFirst { it in relevant }.let { if (it < 0) null else it + 1 }
    private fun millis(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun report(event: String, fields: Map<String, Any?>) = println("SESHLOG_SEMANTIC " + Gson().toJson(mapOf("event" to event) + fields))
}
