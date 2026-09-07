package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role

/** Plain text and match offsets share the same source, including Unicode character offsets. */
data class ConversationDocument(val text: String, val messageRanges: List<IntRange>) {
    fun matches(query: String): List<IntRange> {
        val parsed = com.hedworth.seshlog.index.TextQuery.parse(query)
        return messageRanges.flatMap { range ->
            parsed.ranges(text.substring(range)).map { (it.first + range.first)..(it.last + range.first) }
        }
    }

    companion object {
        fun build(messages: List<ConversationMessage>): ConversationDocument {
            val text = StringBuilder()
            val ranges = ArrayList<IntRange>()
            for (message in messages) {
                text.append(if (message.role == Role.USER) "You" else "Assistant")
                message.timestamp?.let { text.append(" · ").append(it) }
                text.append("\n")
                val start = text.length
                text.append(message.text)
                ranges += start until text.length
                text.append("\n\n────────────────────────\n\n")
            }
            return ConversationDocument(text.toString(), ranges)
        }
    }
}
