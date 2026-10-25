package com.hedworth.seshlog.semantic

import org.sqlite.JDBC
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties
import kotlin.math.ln

/** Test-only FTS5 experiment. All retrieval, BM25 scoring and session aggregation run in SQLite. */
internal class SqliteBm25Prototype private constructor(private val connection: Connection) : AutoCloseable {
    enum class Tokenization { BASELINE_ALIGNED, NATIVE }

    fun create() {
        connection.createStatement().use { sql ->
            sql.execute("CREATE TABLE passages(id INTEGER PRIMARY KEY, session TEXT NOT NULL, entry TEXT)")
            sql.execute("CREATE INDEX passages_session ON passages(session)")
            // No duplicate raw content. Keep positions and token lengths for phrases and bm25().
            sql.execute("""CREATE VIRTUAL TABLE search USING fts5(text, content='', contentless_delete=1,
                tokenize="unicode61 remove_diacritics 0 tokenchars '_'")""")
        }
    }

    fun insert(chunks: List<SemanticPrototype.Chunk>, mode: Tokenization, firstId: Long = 1) = transaction {
        connection.prepareStatement("INSERT INTO passages(id,session,entry) VALUES(?,?,?)").use { meta ->
            connection.prepareStatement("INSERT INTO search(rowid,text) VALUES(?,?)").use { text ->
                chunks.forEachIndexed { index, chunk ->
                    val id = firstId + index
                    meta.setLong(1, id); meta.setString(2, chunk.sessionId); meta.setString(3, chunk.entryId)
                    meta.addBatch()
                    val input = chunk.prompt.removePrefix("title: ").replace(" | text: ", " ")
                    text.setLong(1, id)
                    text.setString(2, if (mode == Tokenization.BASELINE_ALIGNED)
                        SemanticPrototype.tokens(input).joinToString(" ") else input)
                    text.addBatch()
                    if ((index + 1) % 256 == 0) { meta.executeBatch(); text.executeBatch() }
                }
                meta.executeBatch(); text.executeBatch()
            }
        }
    }

    fun deleteSession(id: String) = transaction {
        connection.prepareStatement("DELETE FROM search WHERE rowid IN (SELECT id FROM passages WHERE session=?)").use {
            it.setString(1, id); it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM passages WHERE session=?").use {
            it.setString(1, id); it.executeUpdate()
        }
    }

    fun search(query: String, limit: Int = 100): List<String> {
        val terms = SemanticPrototype.tokens(query).distinct()
        if (terms.isEmpty()) return emptyList()
        // OR semantics matches the old BM25 baseline, including its deliberate lack of phrase rules.
        // User input never becomes SQL or FTS syntax. Each normalized term is a quoted literal.
        val match = terms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        return match(match, limit)
    }

    fun match(expression: String, limit: Int = 100): List<String> {
        require(limit > 0)
        // Materialize scores before GROUP BY: FTS5 auxiliary functions need the MATCH cursor.
        // SQLite bm25() is negative: MIN is the best passage. Stable ties follow corpus order.
        val sql = """WITH scored AS MATERIALIZED (
            SELECT rowid, bm25(search) AS score FROM search WHERE search MATCH ?
        ) SELECT p.session, MIN(s.score) AS best, MIN(p.id) AS ordinal
          FROM scored s JOIN passages p ON p.id=s.rowid
          GROUP BY p.session ORDER BY best ASC, ordinal ASC LIMIT ?"""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, expression); statement.setInt(2, limit)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    fun integrityCheck() = connection.createStatement().use {
        it.execute("INSERT INTO search(search) VALUES('integrity-check')")
        it.executeQuery("PRAGMA integrity_check").use { rows -> check(rows.next() && rows.getString(1) == "ok") }
    }

    fun hasStoredText(): Boolean = connection.createStatement().use {
        it.executeQuery("SELECT COUNT(*) FROM search WHERE text IS NOT NULL").use { rows -> rows.next(); rows.getLong(1) != 0L }
    }

    fun version(): String = connection.createStatement().use {
        it.executeQuery("SELECT sqlite_version()").use { rows -> rows.next(); rows.getString(1) }
    }

    private fun transaction(action: () -> Unit) {
        connection.autoCommit = false
        try { action(); connection.commit() } catch (failure: Throwable) { connection.rollback(); throw failure }
        finally { connection.autoCommit = true }
    }

    override fun close() = connection.close()

    companion object {
        fun open(path: Path? = null): SqliteBm25Prototype {
            val connection = JDBC.createConnection("jdbc:sqlite:${path ?: ":memory:"}", Properties())
            connection.createStatement().use {
                // Bound SQLite's page cache; SQL scratch state stays in RAM even for private inputs.
                it.execute("PRAGMA cache_size=-8192")
                it.execute("PRAGMA temp_store=MEMORY")
                it.execute("PRAGMA journal_mode=DELETE")
                it.execute("PRAGMA synchronous=FULL")
            }
            return SqliteBm25Prototype(connection)
        }
    }
}

/** Prepared inverted index with the OLD prototype's exact scoring; a fair warm-query comparator. */
internal class PreparedBm25Prototype(private val chunks: List<SemanticPrototype.Chunk>) {
    private val lengths = IntArray(chunks.size)
    private val postings = HashMap<String, MutableList<Pair<Int, Int>>>()
    private val averageLength: Double
    init {
        chunks.forEachIndexed { index, chunk ->
            val tokens = SemanticPrototype.tokens(chunk.prompt.removePrefix("title: ").replace(" | text: ", " "))
            lengths[index] = tokens.size
            tokens.groupingBy { it }.eachCount().forEach { (term, count) ->
                postings.getOrPut(term) { ArrayList() }.add(index to count)
            }
        }
        averageLength = lengths.average().coerceAtLeast(1.0)
    }

    fun search(query: String, limit: Int = 100): List<String> {
        val scores = HashMap<Int, Double>()
        SemanticPrototype.tokens(query).distinct().forEach { term ->
            val matches = postings[term] ?: return@forEach
            val idf = ln(1.0 + (chunks.size - matches.size + 0.5) / (matches.size + 0.5))
            matches.forEach { (index, count) ->
                val contribution = idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * lengths[index] / averageLength))
                scores[index] = (scores[index] ?: 0.0) + contribution
            }
        }
        val best = HashMap<String, Pair<Double, Int>>()
        scores.forEach { (index, score) ->
            val id = chunks[index].sessionId
            val previous = best[id]
            best[id] = maxOf(score, previous?.first ?: 0.0) to minOf(index, previous?.second ?: index)
        }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<Double, Int>>> { it.value.first }
            .thenBy { it.value.second }).take(limit).map { it.key }
    }
}
