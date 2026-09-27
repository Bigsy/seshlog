package com.hedworth.seshlog.codex

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.cache.IncrementalJsonl
import com.hedworth.seshlog.cache.IncrementalParseResult
import com.hedworth.seshlog.claude.TranscriptParser
import com.hedworth.seshlog.model.Activity
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
    /** Internal thread_spawn/review/guardian rollouts are not user conversations. */
    val isSubagentRollout: Boolean = false,
    val forkedFromId: String? = null,
    /** From the last `task_started` / `task_complete` / `turn_aborted` event, see [Activity]. */
    val activity: Activity = Activity.UNKNOWN,
    val activityAt: Instant? = null,
)

/**
 * Streams Codex rollout JSONL defensively. Codex's local format is not a public API, so unknown
 * record and content types are ignored and malformed lines never fail an entire scan.
 */
object CodexTranscriptParser {
    private val LOG = logger<CodexTranscriptParser>()

    /** A CLI process also writes subagent/approval rollouts; those are not its terminal conversation. */
    internal fun isCliTranscript(path: Path): Boolean = try {
        Files.newBufferedReader(path).use { reader ->
            val header = StringBuilder()
            while (header.length < 64 * 1024) {
                val next = reader.read()
                if (next == -1 || next == '\n'.code) break
                header.append(next.toChar())
            }
            val record = parseObject(header.toString())
            record?.string("type") == "session_meta" && record.objectValue("payload")?.string("source") == "cli"
        }
    } catch (_: Exception) { false }

    fun parse(path: Path): CodexTranscriptInfo =
        Files.newInputStream(path).use { input ->
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16)
            parseLines(generateSequence { reader.readLine() })
        }

    internal fun parseIncremental(path: Path, previous: CodexTranscriptInfo?, offset: Long): IncrementalParseResult<CodexTranscriptInfo> {
        val accumulator = Accumulator(previous)
        val completed = IncrementalJsonl.read(path, offset, accumulator::offer)
        return IncrementalParseResult(accumulator.build(), completed)
    }

    fun parseLines(lines: Sequence<String>): CodexTranscriptInfo {
        val acc = Accumulator()
        lines.forEach(acc::offer)
        return acc.build()
    }

    private class Accumulator(previous: CodexTranscriptInfo? = null) {
        var sessionId: String? = previous?.sessionId
        var cwd: String? = previous?.cwd
        var gitBranch: String? = previous?.gitBranch
        var promptTitle: String? = previous?.promptTitle
        var startedAt: Instant? = previous?.startedAt
        var promptCount = previous?.promptCount ?: 0
        var isSubagentRollout = previous?.isSubagentRollout ?: false
        var forkedFromId: String? = previous?.forkedFromId
        var activity = previous?.activity ?: Activity.UNKNOWN
        var activityAt: Instant? = previous?.activityAt

        fun offer(line: String) {
            if (line.isBlank()) return
            when {
                line.contains("session_meta") -> offerSessionMeta(line)
                line.contains("response_item") -> offerResponseItem(line)
            }
            // Independent of the dispatch above: a tool output quoting "response_item" must not hide a task event.
            if (line.contains("event_msg") && TASK_EVENTS.keys.any(line::contains)) offerEvent(line)
        }

        /** Codex brackets every turn with task events; the last one says whether it is still working. */
        private fun offerEvent(line: String) {
            val obj = parseObject(line) ?: return
            if (obj.string("type") != "event_msg") return
            val payload = obj.objectValue("payload") ?: return
            val next = TASK_EVENTS[payload.string("type")] ?: return
            activity = next
            activityAt = instant(obj.string("timestamp"))
        }

        private fun offerSessionMeta(line: String) {
            val obj = parseObject(line) ?: return
            if (obj.string("type") != "session_meta") return
            val payload = obj.objectValue("payload") ?: return
            // Subagent and approval rollouts share the parent's session_id, but have their own id.
            // Keep the id so a malformed or unusual rollout cannot merge into its parent.
            isSubagentRollout = isSubagentRollout || payload.isInternalSubagent()
            if (sessionId == null) sessionId = payload.string("id")
            if (forkedFromId == null) forkedFromId = payload.string("forked_from_id")?.takeIf { it.isNotBlank() }
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

        fun build() = CodexTranscriptInfo(
            sessionId, cwd, gitBranch, promptTitle, startedAt, promptCount, isSubagentRollout, forkedFromId, activity, activityAt,
        )
    }

    private val TASK_EVENTS = mapOf(
        "task_started" to Activity.WORKING,
        "task_complete" to Activity.WAITING,
        "turn_aborted" to Activity.INTERRUPTED,
    )

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

    /** Identifies Codex's internal subagent and approval rollout source variants. */
    private fun JsonObject.isInternalSubagent(): Boolean {
        if (string("thread_source") in setOf("guardian_review", "thread_spawn", "review")) return true
        val source = get("source") ?: return false
        if (source.isJsonPrimitive) {
            return source.asString in setOf("subagent", "thread_spawn", "review", "guardian", "guardian_review")
        }
        if (!source.isJsonObject) return false
        val subagentElement = source.asJsonObject.get("subagent") ?: return false
        if (subagentElement.isJsonPrimitive) {
            return subagentElement.asString in setOf("thread_spawn", "review", "guardian")
        }
        val subagent = subagentElement.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return subagent.has("thread_spawn") || subagent.has("review") || subagent.string("other") == "guardian"
    }
}
