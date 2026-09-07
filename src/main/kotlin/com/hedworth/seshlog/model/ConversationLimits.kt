package com.hedworth.seshlog.model

import java.nio.file.Files
import java.nio.file.Path

/** Shared extraction budget: the index and viewer consume precisely the same retained text. */
object ConversationLimits {
    const val ENTRY_CHARS = 32_000
    const val TOTAL_CHARS = 500_000
    const val ENTRIES = 2_000
    const val RECORD_CHARS = 1_000_000
    const val SOURCE_CHARS = 16_000_000
    const val NOTICE = "Partial content: up to 32,000 characters per entry, 500,000 characters / 2,000 entries per session. Records over 1,000,000 characters are skipped; only the first 16,000,000 source characters are scanned. Search covers only retained text."

    class Collector {
        val entries = ArrayList<ConversationEntry>()
        private var chars = 0
        var limited = false
        val full: Boolean get() = chars >= TOTAL_CHARS || entries.size >= ENTRIES
        fun add(entry: ConversationEntry) {
            if (full) { limited = true; return }
            val text = entry.text.take(minOf(ENTRY_CHARS, TOTAL_CHARS - chars))
            val truncated = entry.truncated || text.length < entry.text.length
            limited = limited || truncated
            entries += entry.copy(message = entry.message.copy(text = text), truncated = truncated)
            chars += text.length
        }
        fun finish(): List<ConversationEntry> = entries + if (limited) listOf(
            ConversationEntry(ConversationMessage(Role.ASSISTANT, NOTICE, null), "coverage", EntryKind.COVERAGE)
        ) else emptyList()
    }

    /** Bounded line reader: never allocates an arbitrarily large JSON record. I/O errors propagate. */
    fun read(path: Path, parse: (String, String) -> List<ConversationEntry>): List<ConversationEntry> {
        val out = Collector()
        Files.newBufferedReader(path).use { reader ->
            val line = StringBuilder()
            var source = 0
            var lineNumber = 0
            var oversized = false
            fun flush() {
                if (oversized) out.limited = true
                else parse(line.toString(), "line:${lineNumber}").forEach(out::add)
                line.setLength(0)
                oversized = false
                lineNumber++
            }
            while (true) {
                val ch = reader.read()
                if (ch == -1) { if (line.isNotEmpty() || oversized) flush(); break }
                if (++source > SOURCE_CHARS || out.full) { out.limited = true; break }
                if (ch == 10) flush()
                else if (line.length < RECORD_CHARS) line.append(ch.toChar())
                else oversized = true
            }
        }
        return out.finish()
    }
}
