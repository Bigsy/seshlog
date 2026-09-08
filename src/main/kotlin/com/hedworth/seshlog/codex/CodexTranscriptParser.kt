package com.hedworth.seshlog.codex

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.claude.TranscriptParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Metadata extracted from a Codex rollout transcript. */
data class CodexTranscriptInfo(
    val sessionId: String?,
    val cwd: String?,
    val gitBranch: String?,
    val promptTitle: String?,
    val startedAt: Instant?,
    val promptCount: Int,
    val isGuardianReview: Boolean = false,
)

/**
 * Streams Codex rollout JSONL defensively. Codex's local format is not a public API, so unknown
 * record and content types are ignored and malformed lines never fail an entire scan.
 */
object CodexTranscriptParser {
    private val LOG = logger<CodexTranscriptParser>()

    fun parse(path: Path): CodexTranscriptInfo =
        Files.newInputStream(path).use { input ->
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16)
            parseLines(generateSequence { reader.readLine() })
        }

    fun parseLines(lines: Sequence<String>): CodexTranscriptInfo {
        val acc = Accumulator()
        lines.forEach(acc::offer)
        return acc.build()
    }

    private class Accumulator {
        var sessionId: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        var promptTitle: String? = null
        var startedAt: Instant? = null
        var promptCount = 0
        var isGuardianReview = false

        fun offer(line: String) {
            if (line.isBlank()) return
            when {
                line.contains("session_meta") -> offerSessionMeta(line)
                line.contains("response_item") -> offerResponseItem(line)
            }
        }

        private fun offerSessionMeta(line: String) {
            val obj = parseObject(line) ?: return
            if (obj.string("type") != "session_meta") return
            val payload = obj.objectValue("payload") ?: return
            // Internal approval reviews share the user's session_id but have their own rollout.
            isGuardianReview = isGuardianReview || payload.string("thread_source") == "guardian_review" ||
                payload.objectValue("source")?.objectValue("subagent")?.string("other") == "guardian"
            if (sessionId == null) sessionId = payload.string("session_id") ?: payload.string("id")
            if (cwd == null) cwd = payload.string("cwd")
            if (gitBranch == null) gitBranch = payload.objectValue("git")?.string("branch")
            if (startedAt == null) startedAt = instant(payload.string("timestamp") ?: obj.string("timestamp"))
        }

        private fun offerResponseItem(line: String) {
            // Tool calls and outputs dominate rollouts and can be very large. Only message records
            // can contribute prompts, so avoid JSON parsing everything else.
            if (!line.contains("user")) return
            val obj = parseObject(line) ?: return
            if (obj.string("type") != "response_item") return
            val payload = obj.objectValue("payload") ?: return
            if (payload.string("type") != "message" || payload.string("role") != "user") return
            val text = messageText(payload.get("content"), setOf("input_text", "text")) ?: return
            if (!isRealPrompt(text)) return
            promptCount++
            if (promptTitle == null) promptTitle = TranscriptParser.promptToTitle(text)
            if (startedAt == null) startedAt = instant(obj.string("timestamp"))
        }

        fun build() = CodexTranscriptInfo(sessionId, cwd, gitBranch, promptTitle, startedAt, promptCount, isGuardianReview)
    }

    internal fun parseObject(line: String): JsonObject? = try {
        JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        LOG.debug("Skipping malformed Codex transcript line", e)
        null
    }

    internal fun messageText(content: JsonElement?, acceptedTypes: Set<String>): String? {
        if (content == null || content.isJsonNull) return null
        if (content.isJsonPrimitive) return content.asString.takeIf { it.isNotBlank() }
        if (!content.isJsonArray) return null
        val parts = content.asJsonArray.mapNotNull { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (block.string("type") !in acceptedTypes) return@mapNotNull null
            (block.string("text") ?: block.string("input_text") ?: block.string("output_text"))
                ?.takeIf { it.isNotBlank() }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** User-role records injected by Codex itself are context, not conversation prompts. */
    internal fun isRealPrompt(text: String): Boolean {
        val value = text.trimStart()
        if (value.isEmpty()) return false
        return !(value.startsWith("<environment_context>") ||
            value.startsWith("<user_instructions>") ||
            value.startsWith("<turn_aborted>") ||
            value.startsWith("<system-reminder>") ||
            value.startsWith("# AGENTS.md instructions") ||
            value.startsWith("AGENTS.md instructions"))
    }

    internal fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    internal fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    internal fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
