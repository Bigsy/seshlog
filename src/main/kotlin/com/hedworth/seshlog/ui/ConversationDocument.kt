package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role

/** Plain text and match offsets share the same source, including Unicode character offsets. */
data class ConversationDocument(val text: String, val messageRanges: List<IntRange>) {
    fun matches(query: String): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val matches = ArrayList<IntRange>()
        for (range in messageRanges) {
            var start = range.first
            while (start + query.length - 1 <= range.last) {
                if (text.regionMatches(start, query, 0, query.length, ignoreCase = true)) {
                    matches += start until start + query.length
                    start += query.length
                } else start++
            }
        }
        return matches
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
