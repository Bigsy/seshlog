package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session

/**
 * With `thread-id` in `[tui] terminal_title`, a Codex TUI titles its terminal with the thread it
 * shows and retitles it on `/new`, `/clear`, `/resume` and the resume picker. That is the only
 * per-TUI record of its thread once the app-server daemon holds the rollout. Codex shortens the
 * item to 29 characters plus "...", so a shortened id names a thread only as a unique prefix.
 */
internal object CodexTitle {
    private val ID = Regex("""(?<![0-9A-Za-z_-])([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{0,12})(?![0-9A-Za-z_])(\.\.\.|…)?""")
    private const val FULL_LENGTH = 36

    /** The thread id or shortened id prefix in [title]; null when it names none or several. */
    fun thread(title: String?): String? {
        if (title.isNullOrEmpty()) return null
        return ID.findAll(title)
            // An unmarked partial id is some other text, not a shortened title item.
            .filter { it.groupValues[1].length == FULL_LENGTH || it.groupValues[2].isNotEmpty() }
            .map { it.groupValues[1].lowercase() }
            .distinct().singleOrNull()
    }

    /** The one indexed Codex session [thread] names, or null when none or several match. */
    fun match(thread: String, sessions: List<Session>): String? =
        sessions.filter { it.kind == AgentKind.CODEX && it.id.lowercase().startsWith(thread) }
            .map { it.id }.distinct().singleOrNull()
}
