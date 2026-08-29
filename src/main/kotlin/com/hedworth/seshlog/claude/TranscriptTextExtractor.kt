package com.hedworth.seshlog.claude

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts the human-readable conversation from a supported agent transcript for content search.
 * Never throws for bad content.
 */
object TranscriptTextExtractor {
    /** Per-transcript cap so one pathological session cannot blow up the in-memory index. */
    const val MAX_CHARS_PER_TRANSCRIPT = 2_000_000

    fun extract(path: Path): List<String> =
        Files.newInputStream(path).use { input ->
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16)
            extractLines(generateSequence { reader.readLine() })
        }

    fun extractLines(lines: Sequence<String>): List<String> {
        val out = ArrayList<String>()
        var chars = 0
        for (line in lines) {
            if (chars >= MAX_CHARS_PER_TRANSCRIPT) break
            val text = ConversationMessages.parseLine(line)?.text ?: continue
            out += text
            chars += text.length
        }
        return out
    }
}
