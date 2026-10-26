package com.hedworth.seshlog.index.evaluation

import com.google.gson.Gson
import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.index.lexical.searchHash
import org.junit.Assert.*
import org.junit.Test

class SourceRetrievalTest {
    @Test fun `source labelled fixture measures inclusion and primary evidence for each saved mode`() {
        val corpus = SourceRetrievalEvaluation.corpus()
        val fixture = javaClass.getResourceAsStream("/fixtures/search/source-retrieval-v1.json")!!.reader().use { it.readText() }
        ContentSearchIndex({ emptyList() }, { 1 }, localTitle = { corpus.titles.getValue(it.id) }, entryExtractor = { corpus.entries.getValue(it.id) }).use { index ->
            for ((mode, sources) in SourceRetrievalEvaluation.configurations) {
                val actual = corpus.queries.associate { q -> q.id to index.search(q.text, corpus.sessions, sources) }
                val labels = corpus.queries.map { EvaluationQuery(it.id, "source", "frozen", it.text, it.expected.getValue(mode).keys) }
                val metrics = RetrievalEvaluation.metrics(labels, actual.mapValues { it.value.map { h -> h.session.id } })
                val primaryCount = actual.values.sumOf { it.size }
                val primaryAgreements = corpus.queries.sumOf { q -> actual.getValue(q.id).count { q.expected.getValue(mode)[it.session.id] == it.primary.source } }
                println("SESHLOG_LEXICAL " + Gson().toJson(mapOf("event" to "sourceQuality", "mode" to mode, "fixtureHash" to searchHash(fixture), "metrics" to metrics,
                    "primaryExpectations" to primaryCount, "primaryAgreements" to primaryAgreements)))
                corpus.queries.forEach { q ->
                    val hits = actual.getValue(q.id)
                    println("SESHLOG_LEXICAL " + Gson().toJson(mapOf("event" to "sourceCase", "id" to q.id, "mode" to mode,
                        "expected" to q.expected.getValue(mode), "actual" to hits.associate { it.session.id to it.primary.source })))
                    assertEquals("${q.id} $mode inclusion", q.expected.getValue(mode).keys, hits.map { it.session.id }.toSet())
                    // Preferred evidence is an evaluation label, not permission to tune the fixed
                    // weights or replace the actual SQL winner. Keep disagreements in the report.
                    hits.forEach { hit ->
                        hit.primary.source.entrySource?.let { source ->
                            assertTrue(sources.includes(source))
                            val entry = corpus.entries.getValue(hit.session.id).single { it.sourceId == hit.entryId }
                            assertEquals(entry.searchSource, source)
                            assertTrue(hit.snippet!!.contains(entry.text.substring(hit.range!!)))
                        }
                    }
                }
                assertEquals(1.0, metrics.hit1, 0.0)
                assertEquals(1.0, metrics.recall5, 0.0)
                assertEquals(0.0, metrics.falsePositive, 0.0)
            }
        }
    }
}
