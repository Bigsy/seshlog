package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.ui.ConversationDocument
import org.junit.Assert.*
import org.junit.Test

class TextQueryTest {
    @Test fun mixedTermsAndUnfinishedPhrases() {
        val query = TextQuery.parse("fix \"Connection refused\" src/a.kt \"unfinished phrase")
        assertTrue(query.matches(listOf("SRC/A.KT fix", "connection refused", "unfinished phrase")))
        assertFalse(query.matches(listOf("fix src/aXkt", "connection refused unfinished phrase")))
        assertFalse(TextQuery.parse("\"connection refused\"").matches(listOf("connection", "refused")))
        assertTrue(TextQuery.parse("refused connection").matches(listOf("connection", "refused")))
        assertTrue(TextQuery.parse(" \"\" ").terms.isEmpty())
    }

    @Test fun originalUnicodeOffsetsAndOpeningAcrossMessages() {
        val messages = listOf(
            ConversationMessage(Role.USER, "İ 😀 FIX /tmp/a.kt", null),
            ConversationMessage(Role.ASSISTANT, "Connection refused", null))
        val doc = ConversationDocument.build(messages)
        val matches = doc.matches("/tmp/a.kt \"connection refused\"")
        assertEquals(listOf("/tmp/a.kt", "Connection refused"), matches.map { doc.text.substring(it) })
        assertEquals(doc.text.indexOf("/tmp/a.kt"), matches.first().first)
        assertTrue(doc.matches("metadata-only").isEmpty())
    }
}
