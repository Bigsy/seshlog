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

    sealed interface Reading {
        data object Absent : Reading
        data object Invalid : Reading
        data class Named(val thread: String) : Reading
    }

    fun read(title: String?): Reading {
        if (title.isNullOrBlank()) return Reading.Absent
        val ids = ID.findAll(title).filter { it.groupValues[1].length == FULL_LENGTH || it.groupValues[2].isNotEmpty() }
            .map { it.groupValues[1].lowercase() }.distinct().toList()
        if (ids.size == 1) return Reading.Named(ids.single())
        return if (ids.isNotEmpty() || Regex("[0-9a-fA-F]{8}-").containsMatchIn(title)) Reading.Invalid else Reading.Absent
    }

    /** The thread id or shortened id prefix in [title]; null when it names none or several. */
    fun thread(title: String?): String? = (read(title) as? Reading.Named)?.thread

    /** The one indexed Codex session [thread] names, or null when none or several match. */
    fun match(thread: String, sessions: List<Session>): String? =
        sessions.filter { it.kind == AgentKind.CODEX && it.id.lowercase().startsWith(thread) }
            .map { it.id }.distinct().singleOrNull()
}
