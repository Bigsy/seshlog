package com.hedworth.seshlog.codex

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.codex.CodexTranscriptParser.objectValue
import com.hedworth.seshlog.codex.CodexTranscriptParser.string

/** Extracts visible user and assistant messages from a Codex rollout record. */
object CodexConversationMessages {
    fun parseLine(line: String): ConversationMessage? {
        if (!line.contains("response_item") || !line.contains("\"role\"")) return null
        val obj = CodexTranscriptParser.parseObject(line) ?: return null
        if (obj.string("type") != "response_item") return null
        val payload = obj.objectValue("payload") ?: return null
        if (payload.string("type") != "message") return null
        val role = when (payload.string("role")) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> return null
        }
        val accepted = if (role == Role.USER) setOf("input_text", "text") else setOf("output_text", "text")
        val text = CodexTranscriptParser.messageText(payload.get("content"), accepted) ?: return null
        if (role == Role.USER && !CodexTranscriptParser.isRealPrompt(text)) return null
        return ConversationMessage(role, text, CodexTranscriptParser.instant(obj.string("timestamp")))
    }
}
