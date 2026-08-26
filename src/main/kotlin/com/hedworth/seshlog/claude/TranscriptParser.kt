package com.hedworth.seshlog.claude

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** What we extract from one Claude Code transcript (`<sessionId>.jsonl`). */
data class TranscriptInfo(
    val sessionId: String?,
    val cwd: String?,
    val gitBranch: String?,
    val version: String?,
    /**
     * Title derived from the first real user prompt, already reduced by [TranscriptParser.promptToTitle]
     * to a single line of at most 80 characters. The raw prompt is deliberately not retained: it is
     * only ever needed to label sessions that have no `ai-title`/`custom-title`, and this value is
     * cached to disk.
     */
    val promptTitle: String?,
    val aiTitle: String?,
    val customTitle: String?,
    val startedAt: Instant?,
    val promptCount: Int,
) {
    /** `custom-title` → `ai-title` → first prompt → "Untitled session". */
    val title: String
        get() = customTitle?.takeIf { it.isNotBlank() }
            ?: aiTitle?.takeIf { it.isNotBlank() }
            ?: promptTitle?.takeIf { it.isNotBlank() }
            ?: UNTITLED

    val hasExplicitTitle: Boolean
        get() = !customTitle.isNullOrBlank() || !aiTitle.isNullOrBlank()

    companion object {
        const val UNTITLED = "Untitled session"
    }
}

/**
 * Streams a transcript once, JSON-parsing only the lines that can carry data we need.
 * Never throws for bad content: unknown types are skipped, malformed lines are skipped, missing
 * fields become null.
 */
object TranscriptParser {
    private val LOG = logger<TranscriptParser>()

    private const val MAX_TITLE_LENGTH = 80
    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"([a-zA-Z-]+)\"")
    private val TIMESTAMP_REGEX = Regex("\"timestamp\"\\s*:\\s*\"([^\"]+)\"")

    fun parse(path: Path): TranscriptInfo =
        Files.newInputStream(path).use { input ->
            parse(BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16))
        }

    fun parseLines(lines: Sequence<String>): TranscriptInfo {
        val acc = Accumulator()
        lines.forEach { acc.offer(it) }
        return acc.build()
    }

    private fun parse(reader: BufferedReader): TranscriptInfo {
        val acc = Accumulator()
        while (true) {
            val line = reader.readLine() ?: break
            acc.offer(line)
        }
        return acc.build()
    }

    private class Accumulator {
        var sessionId: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var version: String? = null
        var promptTitle: String? = null
        var aiTitle: String? = null
        var customTitle: String? = null
        var startedAt: Instant? = null
        var promptCount = 0

        fun offer(line: String) {
            if (line.isBlank()) return
            if (startedAt == null) {
                TIMESTAMP_REGEX.find(line)?.groupValues?.get(1)?.let { ts ->
                    startedAt = runCatching { Instant.parse(ts) }.getOrNull()
                }
            }
            // Cheap pre-filter: only parse lines whose top-level type we care about.
            val type = TYPE_REGEX.find(line)?.groupValues?.get(1) ?: return
            when (type) {
                "ai-title" -> parseObject(line)?.let { obj ->
                    obj.string("aiTitle")?.let { aiTitle = it }
                    if (sessionId == null) sessionId = obj.string("sessionId")
                }
                "custom-title" -> parseObject(line)?.let { obj ->
                    obj.string("customTitle")?.let { customTitle = it }
                    if (sessionId == null) sessionId = obj.string("sessionId")
                }
                "user" -> offerUser(line)
                else -> Unit
            }
        }

        private fun offerUser(line: String) {
            // Tool results are `user` records too, and they can be huge — skip without parsing.
            if (line.contains("\"tool_result\"") && cwd != null) return
            val obj = parseObject(line) ?: return
            if (obj.string("type") != "user") return
            if (sessionId == null) sessionId = obj.string("sessionId")
            if (cwd == null) cwd = obj.string("cwd")
            if (gitBranch == null) gitBranch = obj.string("gitBranch")
            if (version == null) version = obj.string("version")
            if (obj.bool("isSidechain") || obj.bool("isMeta")) return
            val text = promptText(obj.getAsJsonObjectOrNull("message")?.get("content")) ?: return
            if (!isRealPrompt(text)) return
            promptCount++
            if (promptTitle == null) promptTitle = promptToTitle(text)
        }

        fun build() = TranscriptInfo(
            sessionId, cwd, gitBranch, version, promptTitle, aiTitle, customTitle, startedAt, promptCount,
        )
    }

    internal fun parseObject(line: String): JsonObject? = try {
        JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        LOG.debug("Skipping malformed transcript line", e)
        null
    }

    /** Text of a user message: string content, or the concatenated `text` blocks of array content. */
    internal fun promptText(content: JsonElement?): String? {
        if (content == null || content.isJsonNull) return null
        if (content.isJsonPrimitive) return content.asString
        if (!content.isJsonArray) return null
        val parts = content.asJsonArray.mapNotNull { el ->
            val block = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (block.string("type") == "text") block.string("text") else null
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Slash commands, local-command output and IDE-injected reminders are not prompts. */
    internal fun isRealPrompt(text: String): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return false
        return !(t.startsWith("<command-name>") ||
            t.startsWith("<command-message>") ||
            t.startsWith("<local-command-") ||
            t.startsWith("<system-reminder>") ||
            t.startsWith("<bash-input>") ||
            t.startsWith("<bash-stdout>") ||
            t.startsWith("<bash-stderr>") ||
            t.startsWith("<user-memory-input>"))
    }

    /** First line that still has content after trimming and stripping a leading slash command, truncated. */
    fun promptToTitle(prompt: String): String {
        val line = prompt.lineSequence()
            .map { it.trim().replace(SLASH_COMMAND, "").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length > MAX_TITLE_LENGTH) line.substring(0, MAX_TITLE_LENGTH - 1).trimEnd() + "…" else line
    }

    private val SLASH_COMMAND = Regex("^/[\\w:-]+\\s*")

    internal fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    internal fun JsonObject.bool(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    internal fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
