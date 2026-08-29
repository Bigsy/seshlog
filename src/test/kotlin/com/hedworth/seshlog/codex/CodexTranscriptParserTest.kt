package com.hedworth.seshlog.codex

import com.hedworth.seshlog.claude.TranscriptTailReader
import com.hedworth.seshlog.claude.TranscriptTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

class CodexTranscriptParserTest {
    private fun fixture(): Path = Paths.get(javaClass.getResource("/fixtures/codex_session.jsonl")!!.toURI())

    @Test
    fun `extracts metadata and real prompts from a rollout`() {
        val info = CodexTranscriptParser.parse(fixture())

        assertEquals("019a1111-2222-7333-8444-555555555555", info.sessionId)
        assertEquals("/Users/tester/workspace/acme", info.cwd)
        assertEquals("feature/codex", info.gitBranch)
        assertEquals(Instant.parse("2026-08-29T10:00:00Z"), info.startedAt)
        assertEquals("Add Codex session support", info.promptTitle)
        assertEquals(2, info.promptCount)
    }

    @Test
    fun `conversation extraction skips injected context tools and malformed records`() {
        assertEquals(
            listOf(
                "Add Codex session support\nand cover the parser.",
                "I’ll add the provider and tests.",
                "Also support named sessions.",
                "Named sessions now take precedence.",
            ),
            TranscriptTextExtractor.extract(fixture()),
        )
        assertEquals(
            listOf("I’ll add the provider and tests.", "Also support named sessions.", "Named sessions now take precedence."),
            TranscriptTailReader.lastMessages(fixture(), 3).map { it.text },
        )
    }

    @Test
    fun `empty and malformed input produces empty metadata`() {
        val info = CodexTranscriptParser.parseLines(sequenceOf("", "{bad", "[]"))
        assertNull(info.sessionId)
        assertNull(info.cwd)
        assertEquals(0, info.promptCount)
    }
}
