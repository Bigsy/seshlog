package com.hedworth.seshlog.model

import java.time.Instant

enum class Role { USER, ASSISTANT }

/** One human-readable turn of a conversation: a real user prompt or an assistant text reply. */
data class ConversationMessage(val role: Role, val text: String, val timestamp: Instant?)

enum class EntryKind { DIALOGUE, TOOL_CALL, TOOL_RESULT, COVERAGE }

/** Structured activity has a source identity and, when supplied by the agent, a call association. */
data class ConversationEntry(
    val message: ConversationMessage,
    val sourceId: String,
    val kind: EntryKind = EntryKind.DIALOGUE,
    val callId: String? = null,
    val toolName: String? = null,
    val truncated: Boolean = false,
) {
    val text: String get() = message.text
    val isTool: Boolean get() = kind == EntryKind.TOOL_CALL || kind == EntryKind.TOOL_RESULT
    val searchable: Boolean get() = kind != EntryKind.COVERAGE
    val label: String get() = when (kind) {
        EntryKind.DIALOGUE -> if (message.role == Role.USER) "You" else "Assistant"
        EntryKind.TOOL_CALL -> "Tool call: ${toolName ?: "unknown"}"
        EntryKind.TOOL_RESULT -> "Tool result: ${toolName ?: callId ?: "unknown"}"
        EntryKind.COVERAGE -> "Partial conversation / search coverage"
    }
}
