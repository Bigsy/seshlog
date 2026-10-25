package com.hedworth.seshlog.index.evaluation

import com.google.gson.JsonParser
import com.hedworth.seshlog.model.*
import java.nio.file.Path
import java.time.Instant
import kotlin.math.log2

internal data class EvaluationQuery(
    val id: String,
    val category: String,
    val split: String,
    val text: String,
    val relevant: Set<String>,
)

internal data class EvaluationCorpus(
    val sessions: List<Session>,
    val entries: Map<String, List<ConversationEntry>>,
    val queries: List<EvaluationQuery>,
)

internal data class RetrievalMetrics(
    val hit1: Double,
    val recall5: Double,
    val mrr5: Double,
    val ndcg10: Double,
    val falsePositive: Double,
)

internal object RetrievalEvaluation {
    fun validate(ids: List<String>, queries: List<EvaluationQuery>) {
        require(ids.size == ids.toSet().size)
        require(queries.size == queries.map { it.id }.toSet().size)
        require(queries.all { ids.containsAll(it.relevant) })
    }

    fun metrics(
        queries: List<EvaluationQuery>,
        rankings: Map<String, List<String>>,
    ): RetrievalMetrics {
        require(queries.all { it.id in rankings })
        require(rankings.values.all { it.size == it.toSet().size })
        val positives = queries.filter { it.relevant.isNotEmpty() }
        val negatives = queries.filter { it.relevant.isEmpty() }
        fun average(items: List<EvaluationQuery>, f: (EvaluationQuery) -> Double) =
            if (items.isEmpty()) 0.0 else items.map(f).average()
        return RetrievalMetrics(
            average(positives) {
                if (rankings.getValue(it.id).firstOrNull() in it.relevant) 1.0 else 0.0
            },
            average(positives) { q ->
                rankings.getValue(q.id).take(5).count { it in q.relevant }.toDouble() /
                    q.relevant.size
            },
            average(positives) { q ->
                val i = rankings.getValue(q.id).take(5).indexOfFirst { it in q.relevant }
                if (i < 0) 0.0 else 1.0 / (i + 1)
            },
            average(positives) { q ->
                val dcg =
                    rankings
                        .getValue(q.id)
                        .take(10)
                        .mapIndexed { i, id -> if (id in q.relevant) 1.0 / log2(i + 2.0) else 0.0 }
                        .sum()
                dcg / (0 until minOf(10, q.relevant.size)).sumOf { 1.0 / log2(it + 2.0) }
            },
            average(negatives) { if (rankings.getValue(it.id).isEmpty()) 0.0 else 1.0 },
        )
    }

    fun corpus(): EvaluationCorpus {
        val root =
            javaClass
                .getResourceAsStream("/fixtures/search/retrieval-v1.json")!!
                .bufferedReader()
                .use { JsonParser.parseReader(it).asJsonObject }
        require(root["synthetic"].asBoolean && root["version"].asInt == 1)
        val entries = LinkedHashMap<String, List<ConversationEntry>>()
        val sessions =
            root["sessions"].asJsonArray.map { row ->
                val r = row.asJsonObject
                val id = r["id"].asString
                entries[id] =
                    r["messages"].asJsonArray.mapIndexed { i, text ->
                        ConversationEntry(
                            ConversationMessage(Role.ASSISTANT, text.asString, null),
                            "$id:$i",
                        )
                    }
                Session(
                    AgentKind.CLAUDE_CODE,
                    id,
                    r["title"].asString,
                    Path.of(r["cwd"].asString),
                    null,
                    null,
                    Instant.EPOCH,
                    null,
                    false,
                    null,
                    null,
                    1,
                    true,
                )
            }
        val queries =
            root["queries"].asJsonArray.map { row ->
                val r = row.asJsonObject
                EvaluationQuery(
                    r["id"].asString,
                    r["category"].asString,
                    r["split"].asString,
                    r["text"].asString,
                    r["relevant"].asJsonArray.map { it.asString }.toSet(),
                )
            }
        validate(sessions.map { it.id }, queries)
        return EvaluationCorpus(sessions, entries, queries)
    }
}
