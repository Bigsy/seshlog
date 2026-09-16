package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
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
    internal val reworked = ReworkedTerminal()

    /** Pids of the shell processes behind every terminal tab in [project]'s Terminal tool window. */
    fun shellPids(project: Project): Set<Long> = tabsWithShellPids(project).values.filterNotNull().toSet()

    /** Enumerate both engines, including tabs in split panes. */
    fun tabsWithShellPids(project: Project): Map<Content, Long?> =
        contents(project).associateWith { terminalOf(project, it)?.shellPid() }

    /** Include unowned/new-engine tabs too, so focusing one clears the active session. */
    internal fun contents(project: Project): List<Content> {
        val root = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager
        val tabs = root?.let(::contentsRecursively).orEmpty()
        return (tabs + widgets(project).mapNotNull { contentOf(project, it) }).distinct()
    }

    private fun contentsRecursively(manager: ContentManager): List<Content> {
        // This public API was added after our 2024.1 baseline. Use it when present to include
        // reworked-terminal panes, which are not necessarily in the legacy widget registry.
        return try {
            val method = ContentManager::class.java.getMethod("getContentsRecursively")
            (method.invoke(manager) as? List<*>)?.filterIsInstance<Content>() ?: manager.contents.toList()
        } catch (_: NoSuchMethodException) {
            manager.contents.toList()
        } catch (e: ReflectiveOperationException) {
            LOG.debug("Cannot enumerate nested terminal contents", e)
            manager.contents.toList()
        }
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
    fun findIdleTab(project: Project, title: String): TerminalHandle? =
        contents(project).asSequence().filter { it.displayName == title }
            .mapNotNull { terminalOf(project, it) }.firstOrNull { it.state() == TerminalState.IDLE }

    fun terminalOf(project: Project, content: Content): TerminalHandle? {
        if (content.manager == null) return null
        return reworked.find(project, content) ?: widgetOf(content)?.let { classic(it, content) }
    }

    internal fun classic(widget: TerminalWidget, content: Content?): TerminalHandle = object : TerminalHandle {
        override val content = content
        override fun shellPid() = TerminalTabs.shellPid(widget)
        override fun state() = TerminalTabs.state(widget)
        override fun execute(command: String) = widget.sendCommandToExecute(command)
        override fun rename(title: String) {
            widget.terminalTitle.change { userDefinedTitle = title }
            super.rename(title)
        }
    }

    /** The widget hosted by [content], or null when the tab is gone or not a terminal. */
    fun widgetOf(content: Content): TerminalWidget? = try {
        if (content.manager == null) null else TerminalToolWindowManager.findWidgetByContent(content)
    } catch (_: Throwable) {
        null
    }

    /** True when the shell in [terminal] is running something (e.g. `claude`). */
    fun isBusy(terminal: TerminalHandle): Boolean = terminal.state() == TerminalState.BUSY

    /**
     * Resume [session] in the tab we already own for it (focus it when busy, run the command in it
     * when idle), else reuse an idle tab with the session's title, else open a new tab via
     * [TerminalLauncher]. Either way the tab is registered with [OwnedTerminalTabs]. Returns the
     * terminal the session runs in.
     */
    fun resume(project: Project, session: Session, command: String): TerminalHandle {
        val owned = OwnedTerminalTabs.getInstance(project)
        owned.terminalFor(session.id)?.let { widget ->
            if (widget.state() != TerminalState.IDLE) {
                LOG.debug("Session ${session.id} already runs in its tab; focusing")
                owned.focus(session.id)
            } else {
                LOG.debug("Resuming ${session.id} in its own idle tab")
                widget.execute("cd ${ShellQuote.quote(session.cwd.toString())} && $command")
                owned.focus(session.id)
            }
            return widget
        }
        val idle = findIdleTab(project, session.title)
        val widget = if (idle != null) {
            LOG.debug("Reusing idle terminal tab '${session.title}' for $command")
            idle.execute("cd ${ShellQuote.quote(session.cwd.toString())} && $command")
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
    fun fork(project: Project, session: Session, command: String): TerminalHandle =
        TerminalLauncher.launch(project, session.cwd, forkTitle(session.title), command)

    fun forkTitle(title: String): String = "$title (fork)"

    private fun widgets(project: Project): Collection<TerminalWidget> = try {
        TerminalToolWindowManager.getInstance(project).terminalWidgets
    } catch (t: Throwable) {
        LOG.debug("Cannot list terminal widgets", t)
        emptyList()
    }

    fun shellPid(widget: TerminalWidget): Long? = try {
        val classic = runCatching { ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget) }.getOrNull()
        classic?.processTtyConnector?.process?.pid()
            ?: (widget.ttyConnector as? com.jediterm.terminal.ProcessTtyConnector)?.process?.pid()
    } catch (_: Throwable) {
        null // Not a local process connector, or not started yet.
    }

    fun state(widget: TerminalWidget): TerminalState = TerminalState.inspect {
        val classic = runCatching { ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget) }.getOrNull()
        if (classic != null) classic.hasRunningCommands()
        else {
            // TerminalWidget's newer default returns false even when it cannot inspect the
            // shell. Only trust an engine implementation, never that default's apparent idle.
            check(!widget.javaClass.getMethod("isCommandRunning").declaringClass.isInterface)
            ReworkedTerminal.call(widget, "isCommandRunning") as Boolean
        }
    }
}
