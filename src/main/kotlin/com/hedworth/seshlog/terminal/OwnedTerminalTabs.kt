package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.restore.ProcessTree
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.util.messages.Topic
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level, in-memory map of session id → Terminal tool window tab: the tabs Seshlog opened
 * or reused, plus tabs adopted from live PIDs or explicit agent resume arguments. Polling retries
 * discovery independently of index updates. Lets "Resume" focus its tab and keeps titles in step with the
 * session titles Claude writes later (`ai-title`, `custom-title`).
 */
@Service(Service.Level.PROJECT)
class OwnedTerminalTabs(private val project: Project) : Disposable {
    private val LOG = logger<OwnedTerminalTabs>()

    private val registry = TabRegistry<Content>()
    private val agents = ObservedAgents<ProcessHandle>()
    // A tab's session after its agent exited: no longer attached, but copy/fork still act on it.
    private val endedSessions = java.util.WeakHashMap<Content, String>()
    private val started = AtomicBoolean(false)
    var activeSessionId: String? = null
        private set

    private val tabObserver = TerminalTabObserver(
        rootManager = { ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager },
        openContents = { TerminalTabs.contents(project) },
        selectionChanged = { content -> updateActiveSession(content) },
        tabClosed = { content -> registry.forget(content); endedSessions.remove(content) },
    )

    val sessionIds: Set<String> get() = registry.sessionIds

    fun owns(sessionId: String): Boolean = registry.owns(sessionId)

    /** Dispose just this session's terminal tab. Must run on the EDT. */
    fun close(sessionId: String): Boolean {
        val content = undisposedContent(sessionId) ?: return true
        // A drag is in flight: do not claim the tab was closed or discard its association.
        val manager = content.manager ?: return false
        if (manager.isDisposed || !manager.removeContent(content, true)) return false
        registry.forget(content)
        tabObserver.refresh()
        return true
    }

    /** The id of the session running in terminal tab [content], if we know one. */
    fun sessionFor(content: Content): String? = registry.sessionFor(content)

    /** The attached session, else the one whose agent last exited in [content]. EDT only. */
    fun lastSessionFor(content: Content): String? = sessionFor(content) ?: endedSessions[content]

    /** Includes temporarily detached tabs; disposal is the only definitive end of ownership. */
    internal fun knownContents(): List<Content> = registry.sessionIds.mapNotNull(::undisposedContent).distinct()

    /** Repair a missed adoption before an action, without waiting for transcript activity. EDT only. */
    fun resolveSession(content: Content): String? {
        sessionFor(content)?.let { return it }
        sync(SessionIndex.getInstance().sessions)
        return sessionFor(content)
    }

    private fun executables(): Map<AgentKind, String> {
        val settings = SeshlogSettings.getInstance()
        return mapOf(AgentKind.CLAUDE_CODE to settings.claudeExecutable,
            AgentKind.CODEX to settings.codexExecutable, AgentKind.OPENCODE to settings.opencodeExecutable,
            AgentKind.PI to settings.piExecutable)
    }

    /** Capture the invoking tab on EDT; verify its current agent on the clipboard worker. */
    fun copySessionResolver(content: Content): () -> Session? {
        val previous = sessionFor(content)
        val last = lastSessionFor(content)
        val shell = TerminalTabs.terminalOf(project, content)?.shellPid()
        val executables = executables()
        return {
            val index = SessionIndex.getInstance()
            val id = if (shell == null) last else SessionProcess.copySession(last, index.sessions,
                inspect = { SessionProcess.inspect(shell, it, executables) }, rescan = { index.scanNow() })
            // Copying from an agent that already exited must not re-attach it to the tab.
            if (id != null && shell != null && (id == previous || id != last)) ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed && !Disposer.isDisposed(content) &&
                    TerminalTabs.terminalOf(project, content)?.shellPid() == shell &&
                    registry.adoptDiscovered(id, content, previous)) tabObserver.refresh()
            }
            id?.let(index::sessionById)
        }
    }

    /** The terminal we own for [sessionId], if its tab still exists. Must be called on the EDT. */
    fun terminalFor(sessionId: String): TerminalHandle? {
        val content = undisposedContent(sessionId) ?: return null
        // Missing manager/view is expected between mouse-down and drop. Activity/restore
        // polling must not turn a transient lookup failure into permanent loss of ownership.
        return TerminalTabs.terminalOf(project, content)
    }

    /** Only Content disposal establishes closure; detachment and adapter availability do not. */
    private fun undisposedContent(sessionId: String): Content? {
        val content = registry.tabFor(sessionId) ?: return null
        if (!Disposer.isDisposed(content)) return content
        registry.forget(content)
        return null
    }

    private var running = emptySet<String>()
    private var checkingProcesses = false
    private var disposed = false
    private val processClock = javax.swing.Timer(2_000) { refreshRunning() }

    /** Cached process evidence; collecting it must never block the EDT. */
    fun runningSessionIds(): Set<String> = running.intersect(registry.sessionIds)

    private fun refreshRunning() {
        if (disposed || checkingProcesses) return
        val snapshot = SessionIndex.getInstance().sessions
        // Shell startup and pane changes do not necessarily produce an index update. Retry
        // adoption and reattach observers even when every transcript is idle.
        sync(snapshot)
        val sessions = snapshot.associateBy { it.id }
        val candidates = registry.sessionIds.mapNotNull { id ->
            val session = sessions[id] ?: return@mapNotNull null
            val shell = terminalFor(id)?.shellPid() ?: return@mapNotNull null
            Triple(id, shell, session)
        }
        val inspected = TerminalTabs.tabsWithShellPids(project).filterValues { it != null }
        val previousOwners = inspected.keys.associateWith(registry::sessionFor)
        val executables = executables()
        val watched = agents.snapshot()
        checkingProcesses = true
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val found = candidates.mapNotNull { (id, shell, session) ->
                SessionProcess.runningProcess(shell, session)?.let { id to it }
            }.toMap()
            val inspections = inspected.mapNotNull { (content, shell) ->
                val discovery = SessionProcess.inspect(requireNotNull(shell), snapshot, executables)
                val id = discovery.sessionIds.singleOrNull() ?: return@mapNotNull null
                Triple(content, id, discovery.processes[id]?.let { ProcessHandle.of(it).orElse(null) })
            }
            val discoveries = inspections.groupBy({ it.second }, { it.first })
            val handles = inspections.mapNotNull { (_, id, handle) -> handle?.let { id to it } }.toMap()
            val exited = watched.filterValues { !it.isAlive }.keys
            app.invokeLater {
                checkingProcesses = false
                if (!disposed && !project.isDisposed) {
                    // Ignore results for tabs replaced or closed while the check ran.
                    val valid = found.keys.filterTo(HashSet()) { id ->
                        val shell = candidates.first { it.first == id }.second
                        terminalFor(id)?.shellPid() == shell
                    }
                    for (id in valid) agents.observe(id, found.getValue(id))
                    for ((id, tabs) in discoveries) {
                        // Never guess between two terminals or overwrite ownership changed
                        // while this background inspection was running.
                        val content = tabs.singleOrNull() ?: continue
                        if (Disposer.isDisposed(content)) continue
                        if (TerminalTabs.terminalOf(project, content)?.shellPid() != inspected[content]) continue
                        if (SessionIndex.getInstance().sessionById(id) == null) continue
                        if (!registry.adoptDiscovered(id, content, previousOwners[content])) continue
                        previousOwners[content]?.let(valid::remove)
                        valid += id
                        handles[id]?.let { agents.observe(id, it) }
                    }
                    // The exact process seen running a tab's session exited: detach it from that tab.
                    for (id in agents.ended(watched, exited)) {
                        val content = registry.tabFor(id) ?: continue
                        if (registry.release(id, content)) endedSessions[content] = id
                        valid -= id
                    }
                    agents.retainOnly(registry.sessionIds)
                    tabObserver.refresh()
                    if (running != valid) {
                        running = valid
                        project.messageBus.syncPublisher(RUNNING_TOPIC).runningChanged(runningSessionIds())
                    }
                }
            }
        }
    }

    /** Remember that [widget]'s tab runs [session]. Must be called on the EDT. */
    fun track(session: Session, widget: TerminalHandle) {
        val content = widget.content ?: run {
            LOG.debug("No tab content for session ${session.id}; not tracking")
            return
        }
        registry.register(session.id, content)
        tabObserver.refresh()
        refreshRunning()
    }

    /** Bring the tab running [sessionId] to the front. Returns false when we do not own one. */
    fun focus(sessionId: String): Boolean {
        val content = undisposedContent(sessionId) ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            ?: return false
        val manager = content.manager
        if (manager == null || manager.isDisposed) return false
        toolWindow.activate({ manager.setSelectedContent(content, true) }, true)
        return true
    }

    /** Subscribe to index updates; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        processClock.start()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) = sync(sessions)
        })
        // ProjectActivity starts on a background thread; terminal APIs require the EDT.
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) sync(SessionIndex.getInstance().sessions)
        }
    }

    /** Adopt tabs by process ancestry and retitle owned tabs. Runs on the EDT (index updates arrive there). */
    fun sync(sessions: List<Session>) {
        if (project.isDisposed) return
        val openTabs = TerminalTabs.tabsWithShellPids(project)
        val retitles = registry.sync(sessions, openTabs, ProcessTree.System) { it.displayName }
        for ((content, title) in retitles) {
            LOG.debug("Retitling terminal tab '${content.displayName}' -> '$title'")
            TerminalTabs.terminalOf(project, content)?.rename(title)
        }
        tabObserver.refresh()
    }

    private fun updateActiveSession(content: Content?) {
        val sessionId = content?.let(registry::sessionFor)
        if (sessionId == activeSessionId) return
        activeSessionId = sessionId
        project.messageBus.syncPublisher(ACTIVE_SESSION_TOPIC).activeSessionChanged(sessionId)
    }

    override fun dispose() {
        disposed = true
        processClock.stop()
        tabObserver.dispose()
    }

    interface RunningListener {
        fun runningChanged(ids: Set<String>)
    }

    interface ActiveSessionListener {
        fun activeSessionChanged(sessionId: String?)
    }

    companion object {
        val RUNNING_TOPIC = Topic.create("Seshlog running terminals", RunningListener::class.java)
        val ACTIVE_SESSION_TOPIC = Topic.create("Seshlog active terminal", ActiveSessionListener::class.java)
        fun getInstance(project: Project): OwnedTerminalTabs = project.getService(OwnedTerminalTabs::class.java)
    }
}
