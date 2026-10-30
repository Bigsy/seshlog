package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.openapi.util.SystemInfo

/**
 * With `thread-id` in `[tui] terminal_title`, a Codex TUI titles its terminal with the thread it
 * shows and retitles it on `/new`, `/clear`, `/resume` and the resume picker. That is the only
 * per-TUI record of its thread once the app-server daemon holds the rollout. Codex shortens the
 * item to 29 characters plus "...", so a shortened id names a thread only as a unique prefix.
 */
internal object CodexTitle {
    private val ID = Regex("""(?<![0-9A-Za-z_-])([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{0,12})(?![0-9A-Za-z_])(\.\.\.|…)?""")
    private const val FULL_LENGTH = 36
    // Codex's default items (0.154 and later) plus thread-id, so a title the tab shows before
    // Seshlog renames it still reads like Codex's own.
    val OVERRIDE = "-c " + ShellQuote.quote("""tui.terminal_title=["activity","thread-name","project-name","thread-id"]""")

    /**
     * [command] with [OVERRIDE], so the Codex terminals Seshlog starts can be followed without
     * changing the title of every other Codex terminal. Codex keeps command-line overrides through
     * `/new`, `/clear` and `/resume`. Left off on Windows: Windows PowerShell strips the inner
     * quotes, and Codex then cannot load its config at all.
     */
    fun launch(command: String, windows: Boolean = SystemInfo.isWindows): String = if (windows) command else "$command $OVERRIDE"

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
