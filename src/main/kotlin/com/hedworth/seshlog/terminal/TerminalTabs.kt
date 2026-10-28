package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.copy.CopyTarget
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.RestoreTabReadiness
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.jediterm.terminal.ProcessTtyConnector
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.SwingUtilities
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

    data class ActionTarget(val isTerminal: Boolean, val content: Content?)

    /** Resolve the actual invoking view; selected/last-active tabs are never substitutes for focus. */
    fun actionTarget(project: Project, event: AnActionEvent): ActionTarget = actionTarget(project, event,
        SessionDiagnostics.trace("terminal_action", "project" to DiagnosticRef.of(project.basePath), "request" to SessionDiagnostics.nextId()))

    internal fun actionTarget(project: Project, event: AnActionEvent, trace: DiagnosticTrace): ActionTarget {
        // The manager's enumeration excludes tabs while moving and terminals hosted in an
        // editor. Retain those known contents as candidates, but require exact view/focus evidence.
        val candidates = (contents(project) + OwnedTerminalTabs.getInstance(project).knownContents()).distinct()
        val context = event.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val keySource = (event.inputEvent as? KeyEvent)?.component
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val focus = CopyTarget.invocationComponent(context, keySource, focusOwner)
        val view = reworked.contextView(event.dataContext)
        // Log before either lookup branch. Previously a missing invoking tab returned without
        // any copy diagnostics, making a focus failure look like a missing session in the index.
        fun componentRef(component: java.awt.Component?) = component?.let {
            mapOf("ref" to DiagnosticRef.tab(it), "class" to it.javaClass.name)
        }
        trace.event("action_context", "dataContext" to event.dataContext.javaClass.name,
            "context" to componentRef(context), "keySource" to componentRef(keySource),
            "focusOwner" to componentRef(focusOwner), "invocation" to componentRef(focus),
            "view" to DiagnosticRef.tab(view), "candidateCount" to candidates.size,
            "candidates" to candidates.map { mapOf("tab" to DiagnosticRef.tab(it),
                "component" to componentRef(it.component), "view" to DiagnosticRef.tab(reworked.viewOf(it))) })
        if (view != null) {
            val content = reworked.contentForView(project, view, candidates)
            trace.event("action_target", "method" to "native_view", "terminal" to true,
                "tab" to DiagnosticRef.tab(content), "reason" to if (content == null) "view_not_found" else "matched")
            return ActionTarget(true, content)
        }
        val content = CopyTarget.focusedContent(focus, candidates) { it.component }
        val window = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val terminal = content != null || (focus != null && window != null &&
            SwingUtilities.isDescendingFrom(focus, window.component)) ||
            event.getData(PlatformDataKeys.TOOL_WINDOW)?.id == TerminalToolWindowFactory.TOOL_WINDOW_ID
        trace.event("action_target", "method" to "component", "terminal" to terminal,
            "tab" to DiagnosticRef.tab(content), "reason" to when {
                content != null -> "matched"
                terminal -> "terminal_content_not_found"
                else -> "outside_terminal"
            })
        return ActionTarget(terminal, content)
    }

    /** Pids of the shell processes behind every terminal tab in [project]'s Terminal tool window. */
    fun shellPids(project: Project): Set<Long> = tabsWithShellPids(project).values.filterNotNull().toSet()

    /** Enumerate both engines, including tabs in split panes. */
    fun tabsWithShellPids(project: Project): Map<Content, Long?> =
        contents(project).associateWith { terminalOf(project, it)?.shellPid() }

    /** Include unowned/new-engine tabs too, so focusing one clears the active session. */
    internal fun contents(project: Project): List<Content> {
        val root = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.getContentManagerIfCreated()
        val tabs = root?.let(::contentsRecursively).orEmpty()
        return (tabs + widgets(project).mapNotNull { contentOf(project, it) }).distinct()
    }

    private fun contentsRecursively(manager: ContentManager): List<Content> =
        manager.contentsRecursively.toList()

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
    fun findIdleTab(project: Project, title: String, originalTitle: String? = null): TerminalHandle? =
        contents(project).asSequence().filter { it.displayName in listOfNotNull(title, originalTitle) && !OwnedTerminalTabs.getInstance(project).ownsTab(it) }
            .mapNotNull { terminalOf(project, it) }.firstOrNull { TerminalCommands.state(it) == TerminalState.IDLE }

    fun terminalOf(project: Project, content: Content): TerminalHandle? {
        if (content.manager == null) return null
        return reworked.find(project, content) ?: widgetOf(content)?.let { classic(it, content) }
    }

    internal fun classic(widget: TerminalWidget, content: Content?): TerminalHandle = object : TerminalHandle {
        override val content = content
        override val diagnosticEngine = "classic"
        override fun shellPid() = TerminalTabs.shellPid(widget)
        override fun applicationTitle(): String? = widget.terminalTitle.applicationTitle
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
    fun isBusy(terminal: TerminalHandle): Boolean = TerminalCommands.state(terminal) == TerminalState.BUSY

    /**
     * Resume [session] in the tab we already own for it (focus it when busy, run the command in it
     * when idle), else reuse the idle tab its agent last exited in or one with the session's title,
     * else open a new tab via [TerminalLauncher]. Either way the tab is registered with
     * [OwnedTerminalTabs]. Returns the terminal the session runs in.
     */
    fun resume(project: Project, session: Session, command: String): TerminalHandle {
        val owned = OwnedTerminalTabs.getInstance(project)
        val title = SessionOrganisation.getInstance().title(session)
        val trace = SessionDiagnostics.trace("resume", "project" to DiagnosticRef.of(project.basePath), "session" to DiagnosticRef.of(session.id))
        return ResumeTerminal.resume(
            session.id, owned.terminalFor(session.id),
            contents(project).filter { it.displayName == title || it.displayName == session.title }.mapNotNull { terminalOf(project, it) },
            owns = { it.content?.let(owned::ownsTab) == true },
            idle = { terminal ->
                val state = TerminalCommands.state(terminal)
                trace.event("resume_terminal_state", "tab" to DiagnosticRef.tab(terminal.content), "engine" to terminal.diagnosticEngine, "state" to state)
                state == TerminalState.IDLE
            },
            execute = { TerminalCommands.execute(it, "cd ${ShellQuote.quote(session.cwd.toString())} && $command") },
            launch = { TerminalLauncher.launch(project, session.cwd, title, command) },
            track = { _, terminal -> owned.track(session, terminal) },
            focus = { owned.focus(session.id) },
            ended = contents(project).firstOrNull { owned.lastSessionFor(it) == session.id }?.let { terminalOf(project, it) },
            trace = trace,
        )
    }

    /** Restore only after tab enumeration and the matching shell are ready. Called on EDT. */
    fun prepareRestore(project: Project, title: String, originalTitle: String? = null): Boolean {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            ?: return false
        window.contentManager // Initialize the terminal's asynchronous restoration.
        // Legacy/classic restoration is synchronous. Do not instantiate the reworked manager
        // for classic-only IDEs; a missing API is the normal baseline path.
        return RestoreTabReadiness.ready(
            restored = reworked.tabsRestored(project) != false,
            matching = { contents(project).filter { it.displayName in listOfNotNull(title, originalTitle) } },
            state = { terminalOf(project, it)?.let(TerminalCommands::state) ?: TerminalState.UNKNOWN },
            // Restored shells start lazily when their component is shown, including inactive tabs.
            show = { waiting -> window.show { waiting.manager?.setSelectedContent(waiting, false) } },
        )
    }

    /**
     * Fork [session]: always a fresh tab titled "<title> (fork)" in the session's cwd. The agent mints
     * a new session id, so the tab is never registered for [session]; like a new session it is
     * pending until exact evidence finds an id outside [existingIds] running in it.
     */
    internal fun fork(project: Project, session: Session, command: String, existingIds: Set<String>,
                      launch: Launcher = TerminalLauncher::launch): TerminalHandle =
        launchPending(project, session.cwd, forkTitle(session), command, existingIds, launch)

    /**
     * Open a tab for an agent that mints its own session id. [OwnedTerminalTabs] owns the tab from
     * creation, before the command runs, and resolves it only from exact process evidence for it.
     */
    internal fun launchPending(project: Project, cwd: Path, title: String, command: String, existingIds: Set<String>,
                               launch: Launcher = TerminalLauncher::launch): TerminalHandle {
        val owned = OwnedTerminalTabs.getInstance(project)
        var created: TerminalHandle? = null
        return try {
            launch(project, cwd, title, command) { terminal ->
                created = terminal
                owned.trackPending(terminal, existingIds)
            }
        } catch (t: Throwable) {
            created?.let(owned::cancelPending)
            throw t
        }
    }

    internal fun forkTitle(session: Session): String = forkTitle(SessionOrganisation.getInstance().title(session))

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
            ?: (widget.ttyConnector as? ProcessTtyConnector)?.process?.pid()
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
            widget.isCommandRunning()
        }
    }
}
