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
    private val pendingSessions = PendingSessionAssociations<Content>()
    private val agents = ObservedAgents<ProcessHandle>()
    // A tab's session after its agent exited: no longer attached, but copy/fork still act on it.
    private val endedSessions = EndedSessions<Content>()
    private val started = AtomicBoolean(false)
    private var selectedContent: Content? = null
    var activeSessionId: String? = null
        private set

    private val tabObserver = TerminalTabObserver(
        project = project,
        rootManager = { ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.getContentManagerIfCreated() },
        openContents = { TerminalTabs.contents(project) },
        selectionChanged = { content -> updateActiveSession(content) },
        tabClosed = { content -> registry.forget(content); endedSessions.remove(content); pendingSessions.forget(content) },
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

    internal fun ownsTab(content: Content): Boolean = sessionFor(content) != null || pendingSessions.isPending(content)

    internal fun trackPending(terminal: TerminalHandle, existingIds: Set<String>) {
        terminal.content?.let { pendingSessions.mark(it, existingIds) }
        tabObserver.refresh()
    }

    internal fun cancelPending(terminal: TerminalHandle) { terminal.content?.let(pendingSessions::forget) }

    /** The attached session, else the one whose agent last exited in [content]. EDT only. */
    fun lastSessionFor(content: Content): String? = sessionFor(content) ?: endedSessions[content]

    /** Includes temporarily detached tabs; disposal is the only definitive end of ownership. */
    internal fun knownContents(): List<Content> =
        (registry.sessionIds.mapNotNull(::undisposedContent) + pendingSessions.tabs).filter { !Disposer.isDisposed(it) }.distinct()

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
        val previousGeneration = registry.generation(content)
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
                    registry.adoptDiscovered(id, content, previous, previousGeneration)) tabObserver.refresh()
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
    private val processInspection = ProcessInspection(
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        ui = { ApplicationManager.getApplication().invokeLater(it) },
        failure = { LOG.debug("Terminal process inspection failed", it) },
    )
    private var disposed = false
    private var pollingState = ProcessPollingPolicy.State()
    private val batchInspector = ProcessBatchInspector()
    private val processClock = javax.swing.Timer(2_000) { refreshRunning() }

    /** Cached process evidence; collecting it must never block the EDT. */
    fun runningSessionIds(): Set<String> = running.intersect(registry.sessionIds)

    private fun refreshRunning() {
        if (disposed || processInspection.isRunning) return
        acknowledgeVisibleCompletion()
        val snapshot = SessionIndex.getInstance().sessions
        // Shell startup and pane changes do not necessarily produce an index update. Retry
        // adoption and reattach observers even when every transcript is idle.
        retitleOwned(snapshot)
        tabObserver.refresh()
        val sessions = snapshot.associateBy { it.id }
        val candidates = registry.sessionIds.mapNotNull { id ->
            val session = sessions[id] ?: return@mapNotNull null
            val shell = terminalFor(id)?.shellPid() ?: return@mapNotNull null
            Triple(id, shell, session)
        }
        val inspected = TerminalTabs.tabsWithShellPids(project).filterValues { it != null }
        val previousOwners = inspected.keys.associateWith(registry::sessionFor)
        val previousGenerations = registry.generations(inspected.keys)
        val executables = executables()
        val watched = agents.snapshot()
        processInspection.run(inspect = {
            val batch = batchInspector.inspect(inspected.values.filterNotNull().toSet(), snapshot, executables)
            val found = candidates.mapNotNull { (id, shell, _) ->
                val discovery = batch.discoveries[shell]?.discovery ?: return@mapNotNull null
                val handle = discovery.processes[id]?.let(batch.handles::get)
                    ?: watched[id]?.takeIf { discovery.unknown && it.isAlive }
                handle?.let { id to it }
            }.toMap()
            val inspections = inspected.mapNotNull { (content, shell) ->
                val discovery = batch.discoveries[shell]?.discovery ?: return@mapNotNull null
                val id = discovery.sessionIds.singleOrNull() ?: return@mapNotNull null
                Triple(content, id, discovery.processes[id]?.let(batch.handles::get))
            }
            val discoveries = inspections.groupBy({ it.second }, { it.first })
            val handles = inspections.mapNotNull { (_, id, handle) -> handle?.let { id to it } }.toMap()
            val exited = watched.filterValues { !it.isAlive }.keys
            ProcessObservations(found, discoveries, handles, exited)
        }, apply = { result ->
            val (found, discoveries, handles, exited) = result
            if (!disposed && !project.isDisposed) {
                val ownershipBefore = registry.snapshot()
                val generationNow = registry.generations(inspected.keys)
                val stableTabs = inspected.filter { (tab, shell) ->
                    !Disposer.isDisposed(tab) &&
                        TerminalTabs.terminalOf(project, tab)?.shellPid() == shell
                }.keys
                val validFound = found.filter { (id, _) ->
                    val candidate = candidates.firstOrNull { it.first == id } ?: return@filter false
                    val tab = ownershipBefore[id] ?: return@filter false
                    tab in stableTabs && inspected[tab] == candidate.second
                }
                val allDiscoveries = discoveries.flatMap { (id, tabs) ->
                    tabs.map { tab -> TabProcessDiscovery(tab, id, handles[id]) }
                }.filter { it.tab in stableTabs }
                val idsByTab = allDiscoveries.groupBy { it.tab }
                    .mapValues { (_, values) -> values.mapTo(LinkedHashSet()) { it.sessionId } }
                // A pending launch may resolve only when its tab has exactly one fresh identity.
                // Do this before the pure reconciler so ambiguity cannot be consumed one id at a time.
                val usableDiscoveries = allDiscoveries.filter { discovery ->
                    !pendingSessions.isPending(discovery.tab) ||
                        pendingSessions.candidate(discovery.tab, idsByTab[discovery.tab].orEmpty()) != null
                }
                val reconciliation = TerminalReconciler.reconcile(
                    TerminalReconcileInput(
                        sessions = snapshot,
                        ownership = ownershipBefore,
                        ownershipAtInspection = previousOwners,
                        discoveries = usableDiscoveries,
                        running = validFound.keys,
                        observedBefore = watched,
                        observedNow = agents.snapshot(),
                        exited = exited,
                        tabTitles = stableTabs.associateWith { it.displayName },
                        displayTitle = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()::title,
                        generationsAtInspection = previousGenerations,
                        generationsNow = generationNow,
                        runningProcesses = validFound,
                    ),
                )
                for ((id, tab) in reconciliation.adopt) {
                    if (Disposer.isDisposed(tab)) continue
                    if (TerminalTabs.terminalOf(project, tab)?.shellPid() != inspected[tab]) continue
                    val pending = pendingSessions.isPending(tab)
                    val candidateIds = idsByTab[tab].orEmpty()
                    // Recheck the pending association immediately before adoption. The
                    // injected expiry clock may have advanced while the worker result was
                    // waiting for the EDT; never leave a registry entry without resolving it.
                    if (pending && pendingSessions.candidate(tab, candidateIds) != id) continue
                    if (!registry.adoptDiscovered(id, tab, previousOwners[tab], previousGenerations[tab])) continue
                    if (pending) {
                        pendingSessions.resolve(tab, candidateIds)
                        SessionIndex.getInstance().sessionById(id)?.let {
                            com.hedworth.seshlog.restore.SessionRestoreManager.getInstance(project).recordLaunch(it)
                        }
                    }
                }
                for ((id, tab) in reconciliation.releaseTabs) {
                    if (Disposer.isDisposed(tab)) continue
                    if (TerminalTabs.terminalOf(project, tab)?.shellPid() != inspected[tab]) continue
                    if (registry.release(id, tab, previousGenerations[tab])) endedSessions[tab] = id
                }
                for ((id, process) in reconciliation.observe) {
                    val tab = registry.tabFor(id) ?: continue
                    if (Disposer.isDisposed(tab)) continue
                    if (TerminalTabs.terminalOf(project, tab)?.shellPid() != inspected[tab]) continue
                    agents.observe(id, process)
                    TerminalCommands.observed(tab)
                }
                for ((content, title) in reconciliation.retitle) {
                    if (Disposer.isDisposed(content)) continue
                    if (TerminalTabs.terminalOf(project, content)?.shellPid() != inspected[content]) continue
                    LOG.debug("Retitling terminal tab '${content.displayName}' -> '$title'")
                    TerminalTabs.terminalOf(project, content)?.rename(title)
                }
                agents.retainOnly(registry.sessionIds)
                tabObserver.refresh()
                val valid = reconciliation.running.filterTo(HashSet()) { id ->
                    val tab = registry.tabFor(id) ?: return@filterTo false
                    !Disposer.isDisposed(tab) &&
                        TerminalTabs.terminalOf(project, tab)?.shellPid() == inspected[tab]
                }
                val changed = running != valid || ownershipBefore != registry.snapshot()
                val active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
                val cadence = ProcessPollingPolicy.next(pollingState, active, changed)
                pollingState = cadence.state
                processClock.delay = cadence.delayMillis.toInt()
                if (running != valid) {
                    running = valid
                    project.messageBus.syncPublisher(RUNNING_TOPIC).runningChanged(runningSessionIds())
                }
            }
        })
    }

    private data class ProcessObservations(
        val found: Map<String, ProcessHandle>,
        val discoveries: Map<String, List<Content>>,
        val handles: Map<String, ProcessHandle>,
        val exited: Set<String>,
    )

    /** Remember that [widget]'s tab runs [session]. Must be called on the EDT. */
    fun track(session: Session, widget: TerminalHandle) {
        val content = widget.content ?: run {
            LOG.debug("No tab content for session ${session.id}; not tracking")
            return
        }
        registry.register(session.id, content)
        agents.forget(session.id)
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
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) = sync(sessions)
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            com.hedworth.seshlog.settings.SessionOrganisation.TOPIC,
            object : com.hedworth.seshlog.settings.SessionOrganisation.Listener {
                override fun changed() { sync(SessionIndex.getInstance().sessions) }
            },
        )
        // ProjectActivity starts on a background thread; terminal APIs require the EDT.
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) { processClock.start(); sync(SessionIndex.getInstance().sessions) }
        }
    }

    /** Retitle immediately; process ancestry and discovery are collected only on workers. */
    fun sync(sessions: List<Session>) {
        if (project.isDisposed || disposed) return
        retitleOwned(sessions)
        tabObserver.refresh()
        refreshRunning()
    }

    private fun retitleOwned(sessions: List<Session>) {
        val organisation = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()
        for (session in sessions) {
            val content = undisposedContent(session.id) ?: continue
            val title = organisation.title(session)
            if (content.displayName != title) TerminalTabs.terminalOf(project, content)?.rename(title)
        }
    }

    private fun updateActiveSession(content: Content?) {
        selectedContent = content
        acknowledgeVisibleCompletion()
        val sessionId = content?.let(registry::sessionFor)
        if (sessionId == activeSessionId) return
        activeSessionId = sessionId
        project.messageBus.syncPublisher(ACTIVE_SESSION_TOPIC).activeSessionChanged(sessionId)
    }

    private fun acknowledgeVisibleCompletion() {
        val content = selectedContent ?: return
        if (Disposer.isDisposed(content) || content.manager?.isSelected(content) != true) return
        if (!com.hedworth.seshlog.ui.CompletionViewObserver.isViewed(content.component)) return
        lastSessionFor(content)?.let {
            com.hedworth.seshlog.settings.SessionAttentionState.getInstance().viewedTerminal(it)
        }
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
