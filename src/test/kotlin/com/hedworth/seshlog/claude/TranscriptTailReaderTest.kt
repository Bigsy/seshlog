package com.hedworth.seshlog.claude

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class TranscriptTailReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String) = Paths.get(javaClass.getResource("/fixtures/$name")!!.toURI())

    @Test
    fun `returns the last N conversation messages in order, skipping noise`() {
        val path = fixture("custom_and_ai_title.jsonl")
        val last2 = TranscriptTailReader.lastMessages(path, 2)
        assertEquals(listOf(Role.ASSISTANT, Role.USER), last2.map { it.role })
        assertEquals("Sure.", last2[0].text)
        assertEquals("Now also cover the reverse case.", last2[1].text)
        assertEquals(Instant.parse("2026-08-20T08:25:00.000Z"), last2[1].timestamp)

        val all = TranscriptTailReader.lastMessages(path, 50)
        assertEquals(3, all.size)
        assertEquals("Write a rollback procedure for the widgets migration.\nKeep it short.", all[0].text)
        assertEquals(emptyList<ConversationMessage>(), TranscriptTailReader.lastMessages(path, 0))
    }

    @Test
    fun `lines spanning chunk boundaries are reassembled`() {
        val file = tmp.newFile("big.jsonl").toPath()
        val lines = (1..40).map { n ->
            val filler = "x".repeat(37 + n) // varied lengths so boundaries land mid-line
            """{"type":"user","message":{"role":"user","content":"prompt $n $filler"},"timestamp":"2026-01-01T00:00:${"%02d".format(n)}Z"}"""
        }
        Files.write(file, lines)
        for (chunk in listOf(7, 50, 129, 1 shl 20)) {
            val got = TranscriptTailReader.lastMessages(file, 5, chunkSize = chunk)
            assertEquals("chunk=$chunk", (36..40).map { "prompt $it " + "x".repeat(37 + it) }, got.map { it.text })
            val everything = TranscriptTailReader.lastMessages(file, 1000, chunkSize = chunk)
            assertEquals("chunk=$chunk", 40, everything.size)
        }
    }

    @Test
    fun `empty and malformed files never throw`() {
        val empty = tmp.newFile("empty.jsonl").toPath()
        assertEquals(emptyList<ConversationMessage>(), TranscriptTailReader.lastMessages(empty, 3))
        assertEquals(1, TranscriptTailReader.lastMessages(fixture("malformed.jsonl"), 3).count { it.role == Role.USER })
    }
}
