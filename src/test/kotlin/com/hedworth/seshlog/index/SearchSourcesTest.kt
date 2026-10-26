package com.hedworth.seshlog.index

import com.google.gson.JsonParser
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test

class SearchSourcesTest {
    @Test fun `kind takes precedence over role and coverage never supplies evidence`() {
        for (role in Role.entries) for (kind in EntryKind.entries) {
            val entry = ConversationEntry(ConversationMessage(role, "text", null), "id", kind, toolName = "Claude subagent")
            val expected = when (kind) {
                EntryKind.COVERAGE -> null
                EntryKind.TOOL_CALL -> SearchEntrySource.TOOL_CALL
                EntryKind.TOOL_RESULT -> SearchEntrySource.TOOL_RESULT
                EntryKind.DIALOGUE -> if (role == Role.USER) SearchEntrySource.USER else SearchEntrySource.ASSISTANT
            }
            assertEquals(expected, entry.searchSource)
            for (bits in 0..7) {
                val sources = SearchSources(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
                assertEquals(expected?.let(sources::includes) == true, entry.eligibleFor(sources))
                assertEquals(kind != EntryKind.COVERAGE, entry.searchable)
            }
        }
    }

    @Test fun `all eight summaries and frozen source expectations are explicit`() {
        val summaries = (0..7).map { SearchSources(it and 1 != 0, it and 2 != 0, it and 4 != 0).summary }
        assertEquals(listOf("Titles & paths", "Your messages", "Assistant replies", "Conversation", "Tool activity", "You + tools", "Assistant + tools", "All activity"), summaries)
        val fixture = javaClass.getResourceAsStream("/fixtures/search/source-contract.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val entries = fixture["entries"].asJsonArray.map { row ->
            val e = row.asJsonObject
            ConversationEntry(ConversationMessage(Role.valueOf(e["role"].asString), e["text"].asString, null), e["id"].asString,
                EntryKind.valueOf(e["kind"].asString), toolName = e["tool"]?.asString)
        }
        val options = mapOf("conversation" to SearchSources.DEFAULT, "all" to SearchSources.ALL,
            "user" to SearchSources(true, false, false), "metadata" to SearchSources.METADATA_ONLY)
        fixture["cases"].asJsonArray.forEach { row ->
            val c = row.asJsonObject
            val matches = entries.filter { it.eligibleFor(options.getValue(c["sources"].asString)) && TextQuery.parse(c["query"].asString).matches(listOf(it.text)) }
            assertEquals(c.toString(), c["entries"].asJsonArray.map { it.asString }, matches.map { it.sourceId })
            c["primary"]?.let { assertEquals(it.asString, matches.first().searchSource!!.name) }
        }
    }
}
