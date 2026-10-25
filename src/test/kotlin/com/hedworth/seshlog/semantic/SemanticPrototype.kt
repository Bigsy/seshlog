package com.hedworth.seshlog.semantic

import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.Session
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.math.sqrt
import kotlin.math.ln

/** Test-only experiment: no application service, persistent vectors, or runtime dependencies. */
internal object SemanticPrototype {
    data class Chunk(val sessionId: String, val entryId: String?, val text: String, val prompt: String)
    data class Hit(val sessionId: String, val entryId: String?, val score: Double)

    fun queryPrompt(query: String) = "task: search result | query: $query"

    // UTF-8 byte bounds are conservative even for byte-fallback tokenization. Including the
    // title/prefix, each document stays below our 512-token native batch/context. Small batches
    // matter: the initial 2048-token batch peaked at ~3 GB even for the tiny synthetic corpus.
    fun windows(text: String, byteLimit: Int = 320, overlapPoints: Int = 40): List<String> {
        require(byteLimit >= 4 && overlapPoints >= 0)
        val points = text.codePoints().toArray()
        val result = ArrayList<String>()
        var start = 0
        while (start < points.size) {
            var end = start
            var bytes = 0
            while (end < points.size) {
                val size = when (points[end]) { in 0..0x7f -> 1; in 0..0x7ff -> 2; in 0..0xffff -> 3; else -> 4 }
                if (bytes + size > byteLimit) break
                bytes += size
                end++
            }
            val part = String(points, start, end - start)
            if (part.isNotBlank()) result += part
            if (end == points.size) break
            start = maxOf(start + 1, end - overlapPoints)
        }
        return result
    }

    fun chunks(session: Session, entries: List<ConversationEntry>): List<Chunk> {
        val title = windows(session.title, byteLimit = 128, overlapPoints = 0).firstOrNull() ?: "none"
        val chunks = entries.filter { it.searchable }.flatMap { entry ->
            windows(entry.text).map { text ->
                Chunk(session.id, entry.sourceId, text, "title: $title | text: $text")
            }
        }
        return chunks.ifEmpty { listOf(Chunk(session.id, null, "", "title: $title | text: ")) }
    }

    fun normalize(vector: FloatArray, dimensions: Int = vector.size): FloatArray {
        require(dimensions in 1..vector.size)
        require(vector.all { it.isFinite() }) { "Non-finite embedding" }
        val result = vector.copyOf(dimensions)
        val norm = sqrt(result.sumOf { it.toDouble() * it })
        require(norm > 0) { "Zero embedding" }
        for (i in result.indices) result[i] = (result[i] / norm).toFloat()
        return result
    }

    /** Max passage score per session; keep the winning entry for navigation, not just a title. */
    fun rank(chunks: List<Chunk>, vectors: List<FloatArray>, query: FloatArray): List<Hit> {
        require(chunks.size == vectors.size)
        val best = LinkedHashMap<String, Hit>()
        for (i in chunks.indices) {
            val vector = vectors[i]
            require(vector.size == query.size)
            val score = vector.indices.sumOf { vector[it].toDouble() * query[it] }
            val chunk = chunks[i]
            if (score > (best[chunk.sessionId]?.score ?: Double.NEGATIVE_INFINITY)) {
                best[chunk.sessionId] = Hit(chunk.sessionId, chunk.entryId, score)
            }
        }
        return best.values.sortedByDescending { it.score }
    }

    /** RRF fuses session ranks; quoted terms retain the existing exact-search contract. */
    fun hybrid(literal: List<String>, semantic: List<String>, quoted: Boolean): List<String> {
        if (quoted) return literal.distinct()
        val scores = LinkedHashMap<String, Double>()
        for (ranking in listOf(literal, semantic.take(20))) {
            ranking.distinct().forEachIndexed { index, id ->
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (60 + index + 1)
            }
        }
        return scores.keys.sortedByDescending { scores.getValue(it) }
    }

    private val stop = "a an the and or of to for in on at with from by is are was were be been it its that this i my we our how when where which about".split(' ').toSet()
    fun tokens(text: String) = Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase()).map { it.value }.filter { it !in stop }.toList()

    /** A model-free lexical baseline, so gains over strict AND aren't all attributed to AI. */
    fun bm25(chunks: List<Chunk>, query: String): List<String> {
        val documents = chunks.map { tokens(it.prompt.removePrefix("title: ").replace(" | text: ", " ")) }
        val terms = tokens(query).toSet()
        if (documents.isEmpty() || terms.isEmpty()) return emptyList()
        val averageLength = documents.map { it.size }.average().coerceAtLeast(1.0)
        val frequency = documents.map { it.groupingBy { word -> word }.eachCount() }
        val idf = terms.associateWith { term ->
            val count = frequency.count { term in it }
            ln(1.0 + (documents.size - count + 0.5) / (count + 0.5))
        }
        val best = LinkedHashMap<String, Double>()
        documents.forEachIndexed { i, doc ->
            val score = terms.sumOf { term ->
                val tf = (frequency[i][term] ?: 0).toDouble()
                idf.getValue(term) * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * doc.size / averageLength))
            }
            if (score > (best[chunks[i].sessionId] ?: 0.0)) best[chunks[i].sessionId] = score
        }
        return best.keys.sortedByDescending { best.getValue(it) }
    }

    fun reciprocalRank(ranking: List<String>, relevant: Set<String>, k: Int = 5): Double {
        val rank = ranking.take(k).indexOfFirst { it in relevant }
        return if (rank < 0) 0.0 else 1.0 / (rank + 1)
    }

    fun bytes(chunks: List<Chunk>): Long = chunks.sumOf { it.prompt.toByteArray(UTF_8).size.toLong() }
}
