package com.hedworth.seshlog.index.evaluation

import org.junit.Assert.*
import org.junit.Test

class RetrievalEvaluationTest {
    @Test
    fun `metrics count multiple labels and missing ranks correctly`() {
        val q = EvaluationQuery("q", "technical", "evaluation", "test", setOf("a", "b"))
        val n = q.copy(id = "n", relevant = emptySet())
        val m =
            RetrievalEvaluation.metrics(
                listOf(q, n),
                mapOf("q" to listOf("x", "b"), "n" to emptyList()),
            )
        assertEquals(0.0, m.hit1, 0.0)
        assertEquals(0.5, m.recall5, 0.0)
        assertEquals(0.5, m.mrr5, 0.0)
        assertEquals(0.386852807, m.ndcg10, 0.000001)
        assertEquals(0.0, m.falsePositive, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalEvaluation.validate(listOf("a", "a"), listOf(q))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalEvaluation.validate(listOf("a"), listOf(q))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalEvaluation.metrics(listOf(q), emptyMap())
        }
        assertEquals(
            0.0,
            RetrievalEvaluation.metrics(listOf(q), mapOf("q" to emptyList())).mrr5,
            0.0,
        )
    }

    @Test
    fun `frozen corpus and old baseline are reproducible`() {
        val c = RetrievalEvaluation.corpus()
        assertEquals(200, c.sessions.size)
        assertEquals(200, c.queries.size)
        assertEquals(50, c.queries.count { it.relevant.isEmpty() })
        val index =
            LegacyLiteralSearchIndex(
                { emptyList() },
                { 1 },
                entryExtractor = { c.entries.getValue(it.id) },
            )
        val rankings =
            c.queries.associate {
                it.id to index.search(it.text, c.sessions).map { h -> h.session.id }
            }
        val m = RetrievalEvaluation.metrics(c.queries, rankings)
        assertEquals(2.0 / 3, m.hit1, 0.00001)
        assertEquals(2.0 / 3, m.recall5, 0.00001)
        assertEquals(0.0, m.falsePositive, 0.0)
    }
}
