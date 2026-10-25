package com.hedworth.seshlog.semantic

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SqliteBm25PrototypeTest {
    private fun chunk(id: String, text: String) = SemanticPrototype.Chunk(id, "$id:entry", text, "title: example | text: $text")

    @Test
    fun `prepared index preserves original benchmark rankings`() {
        val corpus = retrievalCorpus()
        val chunks = corpus.sessions.flatMap { SemanticPrototype.chunks(it, corpus.entries.getValue(it.id)) }
        val prepared = PreparedBm25Prototype(chunks)
        corpus.queries.forEach { assertEquals(it.id, SemanticPrototype.bm25(chunks, it.text), prepared.search(it.text)) }
    }

    @Test
    fun `contentless FTS persists ranks and handles incremental replacement and deletion`() {
        val directory = Files.createTempDirectory("seshlog-synthetic-fts-")
        val db = directory.resolve("cache.sqlite")
        try {
            SqliteBm25Prototype.open(db).use { index ->
                index.create()
                index.insert(listOf(chunk("a", "payment retry"), chunk("a", "idempotency"),
                    chunk("b", "coffee beans")), SqliteBm25Prototype.Tokenization.NATIVE)
                assertEquals(listOf("a"), index.search("payment idempotency"))
                assertFalse(index.hasStoredText())
                index.integrityCheck()
            }
            SqliteBm25Prototype.open(db).use { index ->
                assertEquals(listOf("a"), index.search("payment"))
                index.deleteSession("a")
                index.insert(listOf(chunk("a", "replacement unique_token")), SqliteBm25Prototype.Tokenization.NATIVE, 4)
                assertTrue(index.search("payment").isEmpty())
                assertEquals(listOf("a"), index.search("unique_token"))
                assertTrue(index.search("unique").isEmpty())
                assertEquals(listOf("b"), index.search("coffee"))
                index.integrityCheck()
            }
            SqliteBm25Prototype.open(db).use { assertEquals(listOf("a"), it.search("replacement")) }
        } finally { Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }; Files.delete(directory) }
    }

    @Test
    fun `query text cannot become MATCH operators or SQL and no-match queries are empty`() {
        SqliteBm25Prototype.open().use { index ->
            index.create()
            index.insert(listOf(chunk("a", "coffee"), chunk("b", "payment")), SqliteBm25Prototype.Tokenization.NATIVE)
            assertEquals(listOf("a", "b"), index.search("coffee OR payment"))
            assertTrue(index.search("the and of").isEmpty())
            assertTrue(index.search("\"' ); DROP TABLE search; -- nonexistenttoken").isEmpty())
            assertEquals(listOf("b"), index.search("payment"))
        }
    }

    @Test
    fun `native FTS token phrases are not the application's literal substring contract`() {
        SqliteBm25Prototype.open().use { index ->
            index.create()
            index.insert(listOf(chunk("a", "database locked"), chunk("b", "database-locked")),
                SqliteBm25Prototype.Tokenization.NATIVE)
            assertEquals(setOf("a", "b"), index.match("\"database locked\"").toSet())
            assertFalse(com.hedworth.seshlog.index.TextQuery.parse("\"database locked\"").matches(listOf("database-locked")))
        }
    }
}
