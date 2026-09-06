package com.hedworth.seshlog.claude

import com.hedworth.seshlog.model.ConversationMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts the human-readable conversation from a JSONL transcript for content search.
 * Never throws for bad content. [parseLine] decides the line format (default: Claude Code).
 */
object TranscriptTextExtractor {
    /** Per-transcript cap so one pathological session cannot blow up the in-memory index. */
    const val MAX_CHARS_PER_TRANSCRIPT = 2_000_000

    fun extract(path: Path, parseLine: (String) -> ConversationMessage? = ConversationMessages::parseLine): List<String> =
        Files.newInputStream(path).use { input ->
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16)
            extractLines(generateSequence { reader.readLine() }, parseLine)
        }

    fun messages(path: Path, parseLine: (String) -> ConversationMessage? = ConversationMessages::parseLine): List<ConversationMessage> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines -> lines.mapNotNull(parseLine).toList() }

    fun extractLines(lines: Sequence<String>, parseLine: (String) -> ConversationMessage? = ConversationMessages::parseLine): List<String> {
        val out = ArrayList<String>()
        var chars = 0
        for (line in lines) {
            if (chars >= MAX_CHARS_PER_TRANSCRIPT) break
            val text = parseLine(line)?.text ?: continue
            out += text
            chars += text.length
        }
        return out
    }
}
