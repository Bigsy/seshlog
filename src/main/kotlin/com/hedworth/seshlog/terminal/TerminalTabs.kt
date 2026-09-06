package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Read-side view of the Terminal tool window: which shells it runs and which tabs sit idle.
 * Like [TerminalLauncher], this is the one place that touches the terminal plugin's API.
 * All functions must be called on the EDT.
 */
object TerminalTabs {
    private val LOG = logger<TerminalTabs>()

    /** Pids of the shell processes behind every terminal tab in [project]'s Terminal tool window. */
    fun shellPids(project: Project): Set<Long> {
        val result = HashSet<Long>()
        for (widget in widgets(project)) {
            shellPid(widget)?.let { result += it }
        }
        return result
    }

    /** Every tab in [project]'s Terminal tool window with the pid of its shell (null when unknown). */
    fun tabsWithShellPids(project: Project): Map<Content, Long?> {
        val manager = try {
            TerminalToolWindowManager.getInstance(project)
        } catch (t: Throwable) {
            LOG.debug("Cannot access terminal tool window manager", t)
            return emptyMap()
        }
        val result = HashMap<Content, Long?>()
        for (widget in widgets(project)) {
            val content = contentOf(manager, widget) ?: continue
            result[content] = shellPid(widget)
        }
        return result
    }

    /** The tool-window tab that hosts [widget], or null when it is not in a tab (yet). */
    fun contentOf(project: Project, widget: TerminalWidget): Content? = try {
        contentOf(TerminalToolWindowManager.getInstance(project), widget)
    } catch (t: Throwable) {
        LOG.debug("Cannot resolve tab for terminal widget", t)
        null
    }

    private fun contentOf(manager: TerminalToolWindowManager, widget: TerminalWidget): Content? = try {
        manager.getContainer(widget)?.content
    } catch (_: Throwable) {
        null
    }

    /**
     * A terminal tab named [title] that is not running anything — typically a tab the terminal
     * plugin itself restored after a restart, which comes back as a plain shell.
     */
    fun findIdleTab(project: Project, title: String): TerminalWidget? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            ?: return null
        for (content in toolWindow.contentManager.contents) {
            if (content.displayName != title) continue
            val widget = try {
                TerminalToolWindowManager.findWidgetByContent(content)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (state(widget) == TerminalState.IDLE) return widget
        }
        return null
    }

    /** The widget hosted by [content], or null when the tab is gone or not a terminal. */
    fun widgetOf(content: Content): TerminalWidget? = try {
        if (content.manager == null) null else TerminalToolWindowManager.findWidgetByContent(content)
    } catch (_: Throwable) {
        null
    }

    /** True when the shell in [widget] is running something (e.g. `claude`). */
    fun isBusy(widget: TerminalWidget): Boolean = state(widget) == TerminalState.BUSY

    /**
     * Resume [session] in the tab we already own for it (focus it when busy, run the command in it
     * when idle), else reuse an idle tab with the session's title, else open a new tab via
     * [TerminalLauncher]. Either way the tab is registered with [OwnedTerminalTabs]. Returns the
     * widget the session runs in.
     */
    fun resume(project: Project, session: Session, command: String): TerminalWidget {
        val owned = OwnedTerminalTabs.getInstance(project)
        owned.widgetFor(session.id)?.let { widget ->
            if (state(widget) != TerminalState.IDLE) {
                LOG.debug("Session ${session.id} already runs in its tab; focusing")
                owned.focus(session.id)
            } else {
                LOG.debug("Resuming ${session.id} in its own idle tab")
                widget.sendCommandToExecute("cd ${ShellQuote.quote(session.cwd.toString())} && $command")
                owned.focus(session.id)
            }
            return widget
        }
        val idle = findIdleTab(project, session.title)
        val widget = if (idle != null) {
            LOG.debug("Reusing idle terminal tab '${session.title}' for $command")
            idle.sendCommandToExecute("cd ${ShellQuote.quote(session.cwd.toString())} && $command")
            idle
        } else {
            TerminalLauncher.launch(project, session.cwd, session.title, command)
        }
        OwnedTerminalTabs.getInstance(project).track(session, widget)
        return widget
    }

    /**
     * Fork [session]: always a fresh tab titled "<title> (fork)" in the session's cwd. The tab is
     * deliberately *not* registered for [session] — the agent mints a new session id, and adoption
     * by process ancestry in [OwnedTerminalTabs.sync] may pick the tab up on the next rescan.
     */
    fun fork(project: Project, session: Session, command: String): TerminalWidget =
        TerminalLauncher.launch(project, session.cwd, forkTitle(session.title), command)

    fun forkTitle(title: String): String = "$title (fork)"

    private fun widgets(project: Project): Collection<TerminalWidget> = try {
        TerminalToolWindowManager.getInstance(project).terminalWidgets
    } catch (t: Throwable) {
        LOG.debug("Cannot list terminal widgets", t)
        emptyList()
    }

    private fun shellPid(widget: TerminalWidget): Long? = try {
        ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).processTtyConnector?.process?.pid()
    } catch (_: Throwable) {
        null // not a local shell widget (SSH, new engine, not started yet…)
    }

    fun state(widget: TerminalWidget): TerminalState = TerminalState.inspect {
        ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).hasRunningCommands()
    }
}
