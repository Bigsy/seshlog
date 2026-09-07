package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.model.*

data class EntryMatch(val entryIndex: Int, val range: IntRange)

/** Source entries and display offsets remain separate so a collapsed tool can still be navigated. */
data class ConversationDocument(
    val text: String,
    val messageRanges: List<IntRange>,
    val entries: List<ConversationEntry> = emptyList(),
    val entryRanges: List<IntRange> = messageRanges,
    val expanded: Set<String> = emptySet(),
) {
    fun matches(query: String): List<IntRange> {
        val parsed = TextQuery.parse(query)
        return messageRanges.flatMapIndexed { index, range ->
            if (entries.getOrNull(index)?.searchable == false) emptyList()
            else parsed.ranges(text.substring(range)).map { (it.first + range.first)..(it.last + range.first) }
        }
    }

    fun sourceMatches(query: String): List<EntryMatch> {
        val parsed = TextQuery.parse(query)
        return entries.flatMapIndexed { index, entry ->
            if (!entry.searchable) emptyList() else parsed.ranges(entry.text).map { EntryMatch(index, it) }
        }
    }

    fun entryAt(caret: Int): Int? = entryRanges.indexOfFirst { caret in it }.takeIf { it >= 0 }

    /** Expands just the target, retaining the original text offset for a search match. */
    fun reveal(match: EntryMatch): Pair<ConversationDocument, IntRange> {
        val entry = entries[match.entryIndex]
        val next = if (entry.isTool && entry.sourceId !in expanded)
            buildEntries(entries, expanded + entry.sourceId) else this
        val start = next.messageRanges[match.entryIndex].first
        return next to ((start + match.range.first)..(start + match.range.last))
    }

    companion object {
        fun build(messages: List<ConversationMessage>): ConversationDocument =
            buildEntries(messages.mapIndexed { index, message -> ConversationEntry(message, "message:$index") })

        fun buildEntries(entries: List<ConversationEntry>, expanded: Set<String> = emptySet()): ConversationDocument {
            val text = StringBuilder()
            val ranges = ArrayList<IntRange>()
            val entryRanges = ArrayList<IntRange>()
            for (entry in entries) {
                val entryStart = text.length
                text.append(entry.label)
                if (entry.isTool) text.append(if (entry.sourceId in expanded) " [expanded]" else " [collapsed]")
                if (entry.truncated) text.append(" [truncated]")
                entry.message.timestamp?.let { text.append(" · ").append(it) }
                text.append("\n")
                val start = text.length
                if (entry.isTool && entry.sourceId !in expanded)
                    text.append(entry.text.take(120).replace('\n', ' ')).append(" …")
                else text.append(entry.text)
                ranges += start until text.length
                text.append("\n\n────────────────────────\n\n")
                entryRanges += entryStart until text.length
            }
            return ConversationDocument(text.toString(), ranges, entries, entryRanges, expanded)
        }
    }
}
