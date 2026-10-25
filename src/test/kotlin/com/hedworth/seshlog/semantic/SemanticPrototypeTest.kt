package com.hedworth.seshlog.semantic

import com.hedworth.seshlog.index.evaluation.LegacyLiteralSearchIndex as ContentSearchIndex
import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.EntryKind
import com.hedworth.seshlog.model.Role
import org.junit.Assert.*
import org.junit.Test

class SemanticPrototypeTest {
    @Test
    fun `windows preserve Unicode and bound bytes including overlap`() {
        val text = "a😺界é".repeat(1000)
        val windows = SemanticPrototype.windows(text)
        assertTrue(windows.size > 1)
        windows.forEach { window ->
            assertTrue(window.toByteArray(Charsets.UTF_8).size <= 320)
            assertTrue(text.contains(window))
            assertFalse(Character.isLowSurrogate(window.first()))
            assertFalse(Character.isHighSurrogate(window.last()))
        }
        assertTrue(text.startsWith(windows.first()))
        assertTrue(text.endsWith(windows.last()))
        assertEquals(listOf("abcd", "cdef"), SemanticPrototype.windows("abcdef", 4, 2))
        assertTrue(SemanticPrototype.windows("  ").isEmpty())
    }

    @Test
    fun `coverage is not embedded and tool source identity is retained`() {
        val session = retrievalCorpus().sessions.first()
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "Partial conversation", null), "coverage", EntryKind.COVERAGE),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "synthetic stack trace", null), "tool-1", EntryKind.TOOL_RESULT),
        )
        val chunks = SemanticPrototype.chunks(session, entries)
        assertEquals(1, chunks.size)
        assertEquals("tool-1", chunks.single().entryId)
        assertTrue(chunks.single().prompt.startsWith("title: "))
        assertTrue(chunks.single().prompt.endsWith(" | text: synthetic stack trace"))
    }

    @Test
    fun `empty sessions have title representation and titles are bounded`() {
        val session = retrievalCorpus().sessions.first().copy(title = "😺".repeat(1000))
        val chunk = SemanticPrototype.chunks(session, emptyList()).single()
        assertNull(chunk.entryId)
        assertTrue(chunk.prompt.toByteArray(Charsets.UTF_8).size < 200)
    }

    @Test
    fun `truncated vectors are renormalized before cosine scoring`() {
        val vector = SemanticPrototype.normalize(floatArrayOf(3f, 4f, 100f), 2)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), vector, 0.00001f)
        for (bad in listOf(floatArrayOf(0f), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { SemanticPrototype.normalize(bad) }
        }
    }

    @Test
    fun `ranking groups passages by best score and preserves winning entry`() {
        val chunks = listOf(
            SemanticPrototype.Chunk("a", "a1", "", ""), SemanticPrototype.Chunk("a", "a2", "", ""),
            SemanticPrototype.Chunk("b", "b1", "", ""),
        )
        val result = SemanticPrototype.rank(chunks, listOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0.6f, 0.8f)), floatArrayOf(1f, 0f))
        assertEquals(listOf("a", "b"), result.map { it.sessionId })
        assertEquals("a2", result.first().entryId)
        assertEquals(1.0, result.first().score, 0.00001)
    }

    @Test
    fun `hybrid ranks shared evidence first but quoted searches cannot broaden`() {
        assertEquals(listOf("shared", "literal", "semantic"),
            SemanticPrototype.hybrid(listOf("literal", "shared"), listOf("semantic", "shared"), false))
        assertEquals(listOf("literal"), SemanticPrototype.hybrid(listOf("literal"), listOf("semantic"), true))
        assertTrue(SemanticPrototype.hybrid(emptyList(), listOf("semantic"), true).isEmpty())
        assertEquals(listOf("once"), SemanticPrototype.hybrid(listOf("once", "once"), listOf("once"), false))
    }

    @Test
    fun `reciprocal rank is zero for misses or ranks outside cutoff`() {
        assertEquals(0.5, SemanticPrototype.reciprocalRank(listOf("a", "b"), setOf("b")), 0.0)
        assertEquals(0.0, SemanticPrototype.reciprocalRank(listOf("a", "b"), setOf("b"), 1), 0.0)
        assertEquals(0.0, SemanticPrototype.reciprocalRank(emptyList(), setOf("b")), 0.0)
        assertEquals(0.0, SemanticPrototype.reciprocalRank(listOf("a"), emptySet()), 0.0)
    }

    @Test
    fun `BM25 permits partial word overlap and does not return zero-score sessions`() {
        val chunks = listOf(
            SemanticPrototype.Chunk("a", "a1", "", "title: none | text: payments idempotency retry"),
            SemanticPrototype.Chunk("b", "b1", "", "title: none | text: tea coffee"),
        )
        assertEquals(listOf("a"), SemanticPrototype.bm25(chunks, "retry the payment"))
        assertTrue(SemanticPrototype.bm25(chunks, "the and of").isEmpty())
        assertTrue(SemanticPrototype.bm25(chunks, "noexistent_1234").isEmpty())
    }

    @Test
    fun `quality fixture has valid labels and exact cases match the production index`() {
        val corpus = retrievalCorpus()
        val ids = corpus.sessions.map { it.id }.toSet()
        assertEquals(corpus.sessions.size, ids.size)
        assertEquals(corpus.queries.size, corpus.queries.map { it.id }.toSet().size)
        val index = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { corpus.entries.getValue(it.id) })
        corpus.queries.forEach { q ->
            assertTrue(ids.containsAll(q.relevant))
            if (q.category in setOf("literal", "phrase")) {
                assertTrue("Invalid exact fixture ${q.id}", index.search(q.text, corpus.sessions).any { it.session.id in q.relevant })
            }
            if (q.category == "absent") {
                assertTrue(q.relevant.isEmpty())
                assertTrue("Invalid absent fixture ${q.id}", index.search(q.text, corpus.sessions).isEmpty())
            }
        }
    }
}
