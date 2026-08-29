package com.hedworth.seshlog.codex

import com.hedworth.seshlog.codex.CodexTranscriptParser.string
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Reads Codex's append-only session-name index. Later records for the same id win. */
object CodexSessionIndexReader {
    fun read(path: Path): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        try {
            Files.newInputStream(path).use { input ->
                val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 32 * 1024)
                while (true) {
                    val line = reader.readLine() ?: break
                    val obj = CodexTranscriptParser.parseObject(line) ?: continue
                    val id = obj.string("id") ?: continue
                    val name = obj.string("thread_name")?.takeIf { it.isNotBlank() } ?: continue
                    result[id] = name
                }
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return result
    }
}
