package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConversationCopyTest {
    @Test fun copiesOriginalMessageAndDialogueWithoutToolActivityOrUiText() {
        val first = "  Keep whitespace\n\n```kotlin\nval x = 1\n```\n"
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.USER, first, Instant.EPOCH), "u"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "command\noutput", null), "tool", EntryKind.TOOL_RESULT),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "Answer\nsecond line", Instant.EPOCH), "a"))
        val doc = ConversationDocument.buildEntries(entries)
        assertEquals(first, doc.copyMessage(doc.messageRanges[0].first + 8))
        assertEquals(first, doc.copyMessage(doc.entryRanges[0].first))
        assertEquals("command\noutput", doc.copyMessage(doc.messageRanges[1].first))
        assertEquals("You:\n$first\n\nAssistant:\nAnswer\nsecond line", doc.copyDialogue())
        val match = doc.sourceMatches("\"second line\"").single()
        val (opened, range) = doc.reveal(match)
        assertEquals("Answer\nsecond line", opened.copyMessage(range.first))
        assertNull(doc.copyMessage(-1))
        assertNull(doc.copyMessage(doc.text.length))
    }

    @Test fun emptyConversationsToolsAndCoverageDoNotBecomeDialogue() {
        val empty = ConversationDocument.build(emptyList())
        assertEquals("", empty.copyDialogue())
        assertFalse(empty.hasDialogue)
        assertNull(empty.copyMessage(0))
        val collector = ConversationLimits.Collector()
        collector.add(ConversationEntry(ConversationMessage(Role.ASSISTANT, "x".repeat(ConversationLimits.ENTRY_CHARS + 1), null),
            "tool", EntryKind.TOOL_RESULT))
        val doc = ConversationDocument.buildEntries(collector.finish())
        assertFalse(doc.hasDialogue)
        assertEquals("", doc.copyDialogue())
        assertEquals(ConversationLimits.ENTRY_CHARS, doc.copyMessage(doc.messageRanges.first().first)!!.length)
        assertNull(doc.copyMessage(doc.messageRanges.last().first))
    }
}
