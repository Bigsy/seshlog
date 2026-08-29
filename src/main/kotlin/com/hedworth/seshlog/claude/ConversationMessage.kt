package com.hedworth.seshlog.claude

import com.hedworth.seshlog.claude.TranscriptParser.bool
import com.hedworth.seshlog.claude.TranscriptParser.getAsJsonObjectOrNull
import com.hedworth.seshlog.claude.TranscriptParser.string
import java.time.Instant

enum class Role { USER, ASSISTANT }

/** One human-readable turn of a conversation: a real user prompt or an assistant text reply. */
data class ConversationMessage(val role: Role, val text: String, val timestamp: Instant?)

/**
 * Turns one transcript line into a [ConversationMessage], or null for anything that is not part
 * of the visible conversation: tool calls, tool results, thinking blocks, sidechains (subagents),
 * meta records, slash commands, title records. Never throws.
 */
object ConversationMessages {
    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"(user|assistant)\"")

    fun parseLine(line: String): ConversationMessage? {
        val type = TYPE_REGEX.find(line)?.groupValues?.get(1)
        if (type != null) {
            return when (type) {
                "user" -> userMessage(line)
                "assistant" -> assistantMessage(line)
                else -> null
            }
        }
        return com.hedworth.seshlog.codex.CodexConversationMessages.parseLine(line)
    }

    private fun userMessage(line: String): ConversationMessage? {
        // Tool results are `user` records too, and they can be huge — skip without parsing.
        if (line.contains("\"tool_result\"")) return null
        val obj = TranscriptParser.parseObject(line) ?: return null
        if (obj.string("type") != "user") return null
        if (obj.bool("isSidechain") || obj.bool("isMeta")) return null
        val text = TranscriptParser.promptText(obj.getAsJsonObjectOrNull("message")?.get("content")) ?: return null
        if (!TranscriptParser.isRealPrompt(text)) return null
        return ConversationMessage(Role.USER, text, timestamp(obj))
    }

    private fun assistantMessage(line: String): ConversationMessage? {
        // Cheap pre-filter: no text block at all (pure tool_use / thinking record).
        if (!line.contains("\"text\"")) return null
        val obj = TranscriptParser.parseObject(line) ?: return null
        if (obj.string("type") != "assistant") return null
        if (obj.bool("isSidechain")) return null
        val content = obj.getAsJsonObjectOrNull("message")?.get("content") ?: return null
        if (!content.isJsonArray) return null
        val parts = content.asJsonArray.mapNotNull { el ->
            val block = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (block.string("type") == "text") block.string("text")?.takeIf { it.isNotBlank() } else null
        }
        val text = parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: return null
        return ConversationMessage(Role.ASSISTANT, text, timestamp(obj))
    }

    private fun timestamp(obj: com.google.gson.JsonObject): Instant? =
        obj.string("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
