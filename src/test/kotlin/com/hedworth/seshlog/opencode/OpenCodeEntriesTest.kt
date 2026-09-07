package com.hedworth.seshlog.opencode

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

class OpenCodeEntriesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun databaseEntriesPreservePartsAndSkipMalformedOrOversizedRows() {
        val file = OpenCodeFixture.create(tmp.root.toPath())
        JDBC.createConnection("jdbc:sqlite:$file", Properties()).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM part WHERE session_id = 'ses_a'") }
            val lines = Files.readAllLines(Paths.get(javaClass.getResource("/fixtures/opencode_tools.jsonl")!!.toURI()))
            conn.prepareStatement("INSERT INTO part VALUES (?, 'msg_a2', 'ses_a', 1, 1, ?)").use { st ->
                (lines + """{"type":"tool","state":{"output":"${"x".repeat(ConversationLimits.RECORD_CHARS + 1)}"}}""")
                    .forEachIndexed { i, raw ->
                        st.setString(1, "tool-${i.toString().padStart(3, '0')}")
                        st.setString(2, raw)
                        st.executeUpdate()
                    }
            }
        }
        val db = OpenCodeDatabase(file)
        val entries = db.read { db.conversationEntries(it, "ses_a") }
        val call = entries.single { it.kind == EntryKind.TOOL_CALL }
        val result = entries.single { it.callId == call.callId && it.kind == EntryKind.TOOL_RESULT }
        assertTrue(entries.indexOf(call) < entries.indexOf(result))
        assertEquals("call-1", result.callId)
        assertEquals("TOOL_ONLY failure\nsecond line", result.text)
        assertEquals(EntryKind.COVERAGE, entries.last().kind)
        assertEquals(entries, db.read { db.conversationEntries(it, "ses_a") })
    }

    @Test fun existingDialogueAndPreviewStayDialogueOnly() {
        val db = OpenCodeDatabase(OpenCodeFixture.create(tmp.root.toPath()))
        db.read { conn ->
            val entries = db.conversationEntries(conn, "ses_a")
            assertTrue(entries.any { it.kind == EntryKind.TOOL_RESULT && it.text == "forty lines of SQL" })
            assertEquals(db.conversationMessages(conn, "ses_a").map { it.text },
                entries.filter { it.kind == EntryKind.DIALOGUE }.map { it.text })
            assertFalse(db.lastMessages(conn, "ses_a", 20).any { it.text == "forty lines of SQL" })
            assertEquals(2, db.promptStats(conn, "ses_a").promptCount)
        }
    }
}
