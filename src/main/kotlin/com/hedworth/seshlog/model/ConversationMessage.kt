package com.hedworth.seshlog.model

import java.time.Instant

enum class Role { USER, ASSISTANT }

/** One human-readable turn of a conversation: a real user prompt or an assistant text reply. */
data class ConversationMessage(val role: Role, val text: String, val timestamp: Instant?)
