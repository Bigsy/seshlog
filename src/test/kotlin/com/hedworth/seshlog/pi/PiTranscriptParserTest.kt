package com.hedworth.seshlog.pi

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class PiTranscriptParserTest {
    private fun fixture(name: String) = Path.of(javaClass.getResource("/fixtures/pi/$name.jsonl")!!.toURI())
    private fun parse(vararg lines: String) = PiTranscriptParser.parseLines(sequenceOf(
        """{"type":"session","version":3,"id":"test","cwd":"/synthetic"}""", *lines))
    private fun message(id: String, parent: String, text: String) =
        """{"type":"message","id":"$id","parentId":$parent,"message":{"role":"user","content":"$text"}}"""

    @Test fun `active branch preserves history across compaction and uses global name and activity`() {
        val result = PiTranscriptParser.read(fixture("branch"))
        assertEquals(listOf("First prompt", "Selected reply"), result.messages.map { it.text })
        assertEquals("Named session", result.info.explicitTitle)
        assertEquals("First prompt", result.info.promptTitle)
        assertEquals(1, result.info.promptCount)
        assertEquals(Instant.parse("2026-01-03T00:00:00Z"), result.info.lastActivityAt)
    }

    @Test fun `legacy is linear and empty name clears previous title`() {
        val result = PiTranscriptParser.read(fixture("linear"))
        assertEquals(listOf("Legacy prompt", "Legacy answer"), result.messages.map { it.text })
        assertNull(result.info.explicitTitle)
        assertNull(result.info.startedAt)
        assertEquals(1, result.info.promptCount)
    }

    @Test fun `explicit v1 and missing version have identical history even without final newline`() {
        val path = fixture("linear-v1-unterminated")
        assertFalse(Files.readString(path).endsWith("\n"))
        assertEquals(PiTranscriptParser.read(fixture("linear")), PiTranscriptParser.read(path))
    }

    @Test fun `only string text blocks are visible and invalid metadata does not overwrite a valid name`() {
        val result = parse(
            message("a", "null", "Prompt"),
            """{"type":"session_info","id":"name","parentId":"a","name":"Valid name"}""",
            """{"type":"session_info","id":"bad-name","parentId":"name","name":42}""",
            """{"type":"message","id":"b","parentId":"bad-name","timestamp":"invalid","message":{"role":"assistant","timestamp":1767312000000,"content":[null,42,{"type":"text","text":{}},{"type":"text","text":"Answer"},{"type":"text","text":"More"},{"type":"toolCall","name":"Ignored"}]}}""",
        )
        assertEquals(listOf("Prompt", "Answer\nMore"), result.messages.map { it.text })
        assertEquals("Valid name", result.info.explicitTitle)
        assertEquals(Instant.ofEpochMilli(1767312000000), result.info.lastActivityAt)
    }

    @Test fun `invalid and truncated records do not hide valid leaf`() {
        assertEquals(listOf("Safe prompt"), PiTranscriptParser.read(fixture("malformed")).messages.map { it.text })
    }

    @Test fun `missing parents do not join unrelated history`() {
        val result = parse(message("a", "null", "Unrelated"), message("b", "\"missing\"", "Safe suffix"))
        assertEquals(listOf("Safe suffix"), result.messages.map { it.text })
    }

    @Test fun `duplicates stop traversal at ambiguity`() {
        val result = parse(message("a", "null", "First"), message("a", "null", "Duplicate"), message("b", "\"a\"", "Safe suffix"))
        assertEquals(listOf("Safe suffix"), result.messages.map { it.text })
    }

    @Test fun `cycles terminate without repeating messages`() {
        val result = parse(message("a", "\"b\"", "A"), message("b", "\"a\"", "B"))
        assertEquals(listOf("A", "B"), result.messages.map { it.text })
    }

    @Test fun `ancestors beyond tail window are retained`() {
        val filler = "x".repeat(200_000)
        val result = parse(message("a", "null", "Ancestor"),
            """{"type":"custom","id":"padding","parentId":"a","data":"$filler"}""",
            message("b", "\"a\"", "Leaf"))
        assertEquals(listOf("Ancestor", "Leaf"), result.messages.map { it.text })
    }

    @Test fun `invalid identity and launch directory are rejected`() {
        val result = PiTranscriptParser.parseLines(sequenceOf("""{"type":"session","id":4,"cwd":"relative"}"""))
        assertNull(result.info.sessionId)
        assertNull(result.info.cwd)
    }

    @Test fun `reading fixtures never changes bytes including unterminated legacy`() {
        for (name in listOf("branch", "linear", "linear-v1-unterminated", "malformed")) {
            val path = fixture(name)
            val before = Files.readAllBytes(path)
            PiTranscriptParser.read(path)
            assertArrayEquals(before, Files.readAllBytes(path))
        }
    }
}
