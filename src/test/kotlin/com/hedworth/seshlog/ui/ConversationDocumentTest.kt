package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.EntryKind
import com.hedworth.seshlog.index.*
import org.junit.Assert.*
import org.junit.Test

class ConversationDocumentTest {
    @Test fun `eligible matches retain original entry indices and never search rendered labels or coverage`() {
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.USER, "😺 needle user", null), "u"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "needle assistant", null), "a"),
            ConversationEntry(ConversationMessage(Role.USER, "x".repeat(130) + " needle result", null), "t", EntryKind.TOOL_RESULT, toolName = "heading_marker"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "needle coverage_marker", null), "c", EntryKind.COVERAGE),
        )
        val document = ConversationDocument.buildEntries(entries)
        for (bits in 0..7) {
            val sources = SearchSources(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val matches = document.sourceMatches("needle", sources)
            assertEquals(entries.indices.filter { entries[it].eligibleFor(sources) }, matches.map { it.entryIndex })
            matches.forEach { match ->
                val (revealed, range) = document.reveal(match)
                assertEquals("needle", revealed.text.substring(range))
                assertEquals(match.entryIndex, revealed.entryAt(range.first))
                assertTrue(revealed.matches("needle", sources).contains(range))
            }
            assertTrue(document.matches("heading_marker", sources).isEmpty())
            assertTrue(document.matches("coverage_marker", sources).isEmpty())
        }
        assertEquals(2, document.matches("needle", SearchSources.ALL).size) // Tool match is beyond its collapsed prefix.
        assertTrue(document.copyDialogue().contains("needle assistant"))
    }
    @Test fun `finds matches across exchanges with exact Unicode offsets and excludes labels`() {
        val document = ConversationDocument.build(listOf(
            ConversationMessage(Role.USER, "İ 😊 Needle and needle", null),
            ConversationMessage(Role.ASSISTANT, "A NEEDLE reply", null),
        ))
        val matches = document.matches("needle")
        assertEquals(3, matches.size)
        assertEquals(listOf("Needle", "needle", "NEEDLE"), matches.map { document.text.substring(it) })
        assertTrue(document.matches("Assistant").isEmpty())
        assertTrue(document.matches("").isEmpty())
        assertTrue(document.text.indexOf("You") < document.text.indexOf("Assistant"))
    }
}
