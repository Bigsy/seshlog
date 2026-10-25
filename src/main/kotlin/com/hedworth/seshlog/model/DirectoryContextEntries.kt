package com.hedworth.seshlog.model

import java.nio.file.Path

/** Per-source cursor. A skipped oversized record invalidates context until explicitly reported. */
internal class DirectoryContextEntries(
    private val entries: (String, String) -> List<ConversationEntry>,
    private val context: (String, Path?) -> Path?,
) {
    private var cwd: Path? = null
    private var previousLine = -1
    fun parse(line: String, source: String): List<ConversationEntry> {
        val ordinal = source.removePrefix("line:").toIntOrNull()
        if (ordinal != null) {
            if (ordinal != previousLine + 1) cwd = null
            previousLine = ordinal
        }
        cwd = context(line, cwd)
        return entries(line, source).map { it.copy(recordedCwd = cwd, hasDirectoryContext = true) }
    }
}
