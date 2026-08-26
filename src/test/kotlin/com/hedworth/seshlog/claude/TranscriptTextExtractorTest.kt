package com.hedworth.seshlog.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class TranscriptTextExtractorTest {

    private fun fixture(name: String) = Paths.get(javaClass.getResource("/fixtures/$name")!!.toURI())

    @Test
    fun `extracts real prompts and assistant text, skipping meta, slash commands, tool results and thinking`() {
        val texts = TranscriptTextExtractor.extract(fixture("custom_and_ai_title.jsonl"))
        assertTrue(texts.toString(), texts.contains("Write a rollback procedure for the widgets migration.\nKeep it short."))
        assertTrue(texts.toString(), texts.contains("Sure."))
        assertTrue(texts.none { it.contains("<command-name>") })
        assertTrue(texts.none { it.contains("local-command-caveat") })
        assertTrue(texts.none { it.contains("...") }) // the thinking block
    }

    @Test
    fun `sidechain records are skipped, array content is joined`() {
        val texts = TranscriptTextExtractor.extract(fixture("no_title_array_content.jsonl"))
        assertTrue(texts.none { it.contains("subagent") })
        assertTrue(texts.any { it.contains("Please review the PR for the payments service") })
        assertTrue(texts.contains("Reviewing."))
    }

    @Test
    fun `malformed input never throws`() {
        val texts = TranscriptTextExtractor.extract(fixture("malformed.jsonl"))
        assertTrue(texts.contains("hello world"))
        assertEquals(emptyList<String>(), TranscriptTextExtractor.extractLines(sequenceOf("{not json", "", "\"type\":\"user\"")))
    }
}
