package com.hedworth.seshlog.model

import com.hedworth.seshlog.claude.ClaudeConversationEntries
import com.hedworth.seshlog.codex.CodexConversationEntries
import com.hedworth.seshlog.index.ContentSearchIndex
import com.hedworth.seshlog.opencode.OpenCodeConversationEntries
import com.hedworth.seshlog.ui.ConversationDocument
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class ConversationEntriesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val formats = listOf<Pair<String, (String, String) -> List<ConversationEntry>>>(
        "claude" to ClaudeConversationEntries::parse,
        "codex" to CodexConversationEntries::parse,
        "opencode" to { line, id -> OpenCodeConversationEntries.parse(line, id, Role.ASSISTANT, null) }
    )
    private fun session() = Session(AgentKind.CLAUDE_CODE, "synthetic", "Title", Paths.get("/synthetic"),
        null, null, Instant.EPOCH, null, false, null, null, 1, true)

    @Test fun toolOnlySearchOrderingAssociationAndCollapsedNavigationForEveryProvider() {
        for ((name, parser) in formats) {
            val path = Paths.get(javaClass.getResource("/fixtures/${name}_tools.jsonl")!!.toURI())
            val entries = ConversationLimits.read(path, parser)
            assertEquals(name, entries.size, entries.map { it.sourceId }.distinct().size)
            val call = entries.single { it.kind == EntryKind.TOOL_CALL && it.callId == "call-1" }
            val result = entries.single { it.kind == EntryKind.TOOL_RESULT && it.callId == "call-1" }
            assertTrue(name, entries.indexOf(call) < entries.indexOf(result))
            assertTrue(call.text.contains("src/demo.kt"))
            assertEquals(listOf("Find the build failure", "Checking now.", "It needs a fix."),
                entries.filter { it.kind == EntryKind.DIALOGUE }.map { it.text })
            assertFalse(entries.any { it.text.contains("HIDDEN_") })
            assertTrue(entries.any { it.text == "orphan output" && it.callId == null })
            val index = ContentSearchIndex({ error("must use structured extractor") }, { 1 }, entryExtractor = { entries })
            val hit = index.search("TOOL_ONLY \"second line\"", listOf(session())).single()
            assertTrue(name, hit.toolMatch)
            assertEquals(result.sourceId, hit.entryId)
            assertTrue(hit.snippet!!.startsWith("Tool result:"))
            val doc = ConversationDocument.buildEntries(entries)
            assertTrue(doc.expanded.isEmpty())
            val match = doc.sourceMatches("TOOL_ONLY").single()
            val (opened, range) = doc.reveal(match)
            assertEquals(setOf(result.sourceId), opened.expanded)
            assertEquals("TOOL_ONLY", opened.text.substring(range))
            assertEquals(entries.indexOf(result), opened.entryAt(range.first))
            assertEquals(match, opened.sourceMatches("TOOL_ONLY").single())
        }
    }

    @Test fun perEntryAndSessionLimitsAreSharedBySearchAndViewerForEveryProvider() {
        for ((name, parser) in formats) {
            val output = "START " + "x".repeat(ConversationLimits.ENTRY_CHARS) + " UNSEARCHABLE_END"
            val line = toolResult(name, output)
            val file = tmp.newFile("$name.jsonl").toPath()
            Files.writeString(file, line + "\n")
            val entries = ConversationLimits.read(file, parser)
            assertEquals(ConversationLimits.ENTRY_CHARS, entries.first().text.length)
            assertTrue(entries.first().truncated)
            assertEquals(EntryKind.COVERAGE, entries.last().kind)
            val index = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { entries })
            assertTrue(index.search("START", listOf(session())).single().partial)
            assertTrue(index.search("UNSEARCHABLE_END", listOf(session())).isEmpty())
            val doc = ConversationDocument.buildEntries(entries)
            assertTrue(doc.sourceMatches("UNSEARCHABLE_END").isEmpty())
            assertTrue(doc.sourceMatches("Partial content").isEmpty())

            Files.writeString(file, (1..20).joinToString("\n") { line })
            val limited = ConversationLimits.read(file, parser)
            assertTrue(limited.filter { it.searchable }.sumOf { it.text.length } <= ConversationLimits.TOTAL_CHARS)
            assertEquals(EntryKind.COVERAGE, limited.last().kind)

            Files.writeString(file, toolResult(name, "x".repeat(ConversationLimits.RECORD_CHARS + 1)) +
                "\n" + toolResult(name, "AFTER_LARGE_RECORD") + "\n")
            val skipped = ConversationLimits.read(file, parser)
            assertTrue(skipped.any { it.text == "AFTER_LARGE_RECORD" })
            assertTrue(skipped.any { it.kind == EntryKind.COVERAGE })
        }
    }

    @Test fun malformedInputAndIoFailureRemainDefensiveAndRetryable() {
        for ((_, parser) in formats) {
            for (line in listOf("{broken", "null", "[]", "{}", "{\"type\":null}"))
                assertTrue(parser(line, "bad").isEmpty())
        }
        var attempts = 0
        val index = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = {
            if (attempts++ == 0) throw java.io.IOException("busy")
            listOf(ConversationEntry(ConversationMessage(Role.ASSISTANT, "retryable", null), "result", EntryKind.TOOL_RESULT))
        })
        assertTrue(index.search("retryable", listOf(session())).isEmpty())
        assertEquals(1, index.search("retryable", listOf(session())).size)
        assertEquals(2, attempts)
    }

    @Test fun entryCountAndSourceScanBudgetsAreEnforced() {
        val file = tmp.newFile("many.jsonl").toPath()
        Files.newBufferedWriter(file).use { writer ->
            repeat(ConversationLimits.ENTRIES + 1) { writer.appendLine(toolResult("codex", "small")) }
        }
        val many = ConversationLimits.read(file, CodexConversationEntries::parse)
        assertEquals(ConversationLimits.ENTRIES, many.count { it.searchable })
        assertEquals(EntryKind.COVERAGE, many.last().kind)
        Files.newBufferedWriter(file).use { writer ->
            repeat(17) { writer.append(" ".repeat(1_000_000)) }
            writer.appendLine()
            writer.appendLine(toolResult("codex", "BEYOND_SCAN"))
        }
        val scanned = ConversationLimits.read(file, CodexConversationEntries::parse)
        assertEquals(listOf(EntryKind.COVERAGE), scanned.map { it.kind })
    }

    private fun toolResult(name: String, output: String): String = when (name) {
        "claude" -> """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"c","content":"$output"}]}}"""
        "codex" -> """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c","output":"$output"}}"""
        else -> """{"type":"tool","callID":"c","state":{"status":"completed","output":"$output"}}"""
    }
}
