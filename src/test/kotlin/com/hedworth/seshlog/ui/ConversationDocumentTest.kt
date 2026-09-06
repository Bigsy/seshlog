package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import org.junit.Assert.*
import org.junit.Test

class ConversationDocumentTest {
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
