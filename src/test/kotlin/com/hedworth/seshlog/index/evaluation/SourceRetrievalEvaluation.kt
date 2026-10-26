package com.hedworth.seshlog.index.evaluation

import com.google.gson.JsonParser
import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.*
import java.nio.file.Path
import java.time.Instant

internal data class SourceEvaluationQuery(val id: String, val text: String, val expected: Map<String, Map<String, SearchMatchSource>>)
internal data class SourceEvaluationCorpus(val sessions: List<Session>, val entries: Map<String, List<ConversationEntry>>,
    val titles: Map<String, String>, val queries: List<SourceEvaluationQuery>)

internal object SourceRetrievalEvaluation {
    val configurations = linkedMapOf("conversation" to SearchSources.DEFAULT, "user" to SearchSources(true, false, false), "all" to SearchSources.ALL)
    fun corpus(): SourceEvaluationCorpus {
        val root = javaClass.getResourceAsStream("/fixtures/search/source-retrieval-v1.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(root["version"].asInt == 1 && root["synthetic"].asBoolean)
        val entries = linkedMapOf<String, List<ConversationEntry>>()
        val titles = linkedMapOf<String, String>()
        val sessions = root["sessions"].asJsonArray.map { row ->
            val r = row.asJsonObject
            val id = r["id"].asString
            titles[id] = r["localTitle"]?.asString.orEmpty()
            entries[id] = r["entries"].asJsonArray.mapIndexed { i, value ->
                val e = value.asJsonObject
                val text = e["text"].asString
                ConversationEntry(ConversationMessage(Role.valueOf(e["role"].asString),
                    List(e["repeat"]?.asInt ?: 1) { text }.joinToString("\n"), null), "$id:$i", EntryKind.valueOf(e["kind"].asString), toolName = e["tool"]?.asString)
            }
            Session(AgentKind.CLAUDE_CODE, id, r["title"].asString, Path.of(r["cwd"].asString), null, null,
                Instant.EPOCH, null, false, null, null, 1, true)
        }
        val queries = root["queries"].asJsonArray.map { row ->
            val r = row.asJsonObject
            SourceEvaluationQuery(r["id"].asString, r["text"].asString, configurations.keys.associateWith { mode ->
                r[mode].asJsonObject.entrySet().associate { it.key to SearchMatchSource.valueOf(it.value.asString) }
            })
        }
        return SourceEvaluationCorpus(sessions, entries, titles, queries)
    }
}
