package com.hedworth.seshlog.model

import com.hedworth.seshlog.claude.ClaudeConversationEntries
import com.hedworth.seshlog.codex.CodexConversationEntries
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class RecordedDirectoryTest {
    @Test fun `changed files retain the directory at the time of each edit for both agents`() {
        for ((fixture, parser) in listOf("claude_edits.jsonl" to ClaudeConversationEntries.withDirectories(),
            "codex_edits.jsonl" to CodexConversationEntries.withDirectories())) {
            val path = Path.of(javaClass.getResource("/fixtures/locations/$fixture")!!.toURI())
            val entries = ConversationLimits.read(path, parser)
            assertEquals(listOf(Path.of("/synthetic/main/before.kt"), Path.of("/synthetic/topic/after.kt")),
                ChangedFileExtractor.fromEntries(entries, Path.of("/wrong/latest-location")).map { it.path })
        }
    }

    @Test fun `skipped context never retargets relative edits to the latest session cwd`() {
        val parse = CodexConversationEntries.withDirectories()
        val lines = javaClass.getResource("/fixtures/locations/codex_edits.jsonl")!!.readText().lines()
        parse(lines[0], "line:0")
        val entries = parse(lines[3], "line:3")
        assertNull(entries.single().recordedCwd)
        assertTrue(ChangedFileExtractor.fromEntries(entries, Path.of("/wrong/latest")).isEmpty())
    }
}
