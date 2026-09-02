package com.hedworth.seshlog.model

enum class AgentKind(val displayName: String) {
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
    /** opencode styles its own name lowercase. */
    OPENCODE("opencode"),
}
