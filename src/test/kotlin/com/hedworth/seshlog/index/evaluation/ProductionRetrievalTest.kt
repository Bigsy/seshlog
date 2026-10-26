package com.hedworth.seshlog.index.evaluation

import com.google.gson.Gson
import com.hedworth.seshlog.index.ContentSearchIndex
import com.hedworth.seshlog.index.SearchSources
import com.hedworth.seshlog.semantic.retrievalCorpus
import org.junit.Assert.*
import org.junit.Test

class ProductionRetrievalTest {
    @Test
    fun `production BM25 meets frozen synthetic evaluation gates`() {
        val corpus = RetrievalEvaluation.corpus()
        ContentSearchIndex(
                { emptyList() },
                { 1 },
                entryExtractor = { corpus.entries.getValue(it.id) },
            )
            .use { index ->
                val old =
                    LegacyLiteralSearchIndex(
                        { emptyList() },
                        { 1 },
                        entryExtractor = { corpus.entries.getValue(it.id) },
                    )
                val actual =
                    corpus.queries.associate {
                        it.id to index.search(it.text, corpus.sessions).map { h -> h.session.id }
                    }
                val baseline =
                    corpus.queries.associate {
                        it.id to old.search(it.text, corpus.sessions).map { h -> h.session.id }
                    }
                for (split in listOf("development", "evaluation")) for (category in
                    listOf("all", "natural", "technical", "phrase", "absent")) {
                    val queries =
                        corpus.queries.filter {
                            it.split == split && (category == "all" || it.category == category)
                        }
                    val m = RetrievalEvaluation.metrics(queries, actual)
                    val b = RetrievalEvaluation.metrics(queries, baseline)
                    println(
                        "SESHLOG_LEXICAL " +
                            Gson()
                                .toJson(
                                    mapOf(
                                        "event" to "quality",
                                        "split" to split,
                                        "category" to category,
                                        "bm25" to m,
                                        "literal" to b,
                                    )
                                )
                    )
                    assertTrue(m.recall5 >= b.recall5)
                    assertTrue(m.mrr5 >= b.mrr5)
                    if (category in listOf("technical", "phrase")) assertTrue(m.hit1 >= 0.9)
                    if (category == "all") assertTrue(m.recall5 >= 0.9)
                    assertEquals(0.0, m.falsePositive, 0.0)
                }
                corpus.queries.forEach { q ->
                    println(
                        "SESHLOG_LEXICAL " +
                            Gson()
                                .toJson(
                                    mapOf(
                                        "event" to "case",
                                        "id" to q.id,
                                        "ranking" to actual.getValue(q.id).take(10),
                                    )
                                )
                    )
                    if (q.category == "phrase")
                        assertTrue(actual.getValue(q.id).all { it in q.relevant })
                }
            }
    }

    @Test
    fun `original forty four smoke cases retain the frozen baseline comparison`() {
        val c = retrievalCorpus()
        ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { c.entries.getValue(it.id) })
            .use { index ->
                val positive = c.queries.filter { it.relevant.isNotEmpty() }
                val ranks =
                    c.queries.associate { q ->
                        q.id to index.search(q.text, c.sessions, SearchSources.ALL).map { it.session.id }
                    }
                val legacy =
                    LegacyLiteralSearchIndex(
                        { emptyList() },
                        { 1 },
                        entryExtractor = { c.entries.getValue(it.id) },
                    )
                c.queries
                    .filter { it.category in setOf("literal", "phrase") }
                    .forEach { q ->
                        val before = legacy.search(q.text, c.sessions).firstOrNull()?.session?.id
                        if (
                            before in q.relevant &&
                                ranks.getValue(q.id).firstOrNull() !in q.relevant
                        )
                            println(
                                "SESHLOG_LEXICAL " +
                                    Gson()
                                        .toJson(
                                            mapOf(
                                                "event" to "lostExact",
                                                "id" to q.id,
                                                "ranking" to ranks.getValue(q.id).take(5),
                                            )
                                        )
                            )
                    }
                val hit1 = positive.count { ranks.getValue(it.id).firstOrNull() in it.relevant }
                val hit5 =
                    positive.count { q -> ranks.getValue(q.id).take(5).any { it in q.relevant } }
                println(
                    "SESHLOG_LEXICAL " +
                        Gson()
                            .toJson(
                                mapOf(
                                    "event" to "originalSmoke",
                                    "hit1" to hit1,
                                    "hit5" to hit5,
                                    "positive" to positive.size,
                                )
                            )
                )
                assertTrue(hit1 >= 15)
                assertTrue(hit5 >= 16)
                c.queries
                    .filter { it.category == "absent" }
                    .forEach { assertTrue(it.text, ranks.getValue(it.id).isEmpty()) }
            }
    }
}
