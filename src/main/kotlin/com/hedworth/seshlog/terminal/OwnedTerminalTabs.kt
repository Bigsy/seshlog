package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import com.hedworth.seshlog.restore.SessionRestoreManager
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.ui.CompletionViewObserver
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.util.messages.Topic
import java.awt.KeyboardFocusManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Project-level, in-memory map of session id → Terminal tool window tab: the tabs Seshlog opened
 * or reused, plus tabs adopted from live PIDs or explicit agent resume arguments. Polling retries
 * discovery independently of index updates. Lets "Resume" focus its tab and keeps titles in step with the
 * session titles Claude writes later (`ai-title`, `custom-title`).
 */
@Service(Service.Level.PROJECT)
class OwnedTerminalTabs(private val project: Project) : Disposable {
    private val LOG = logger<OwnedTerminalTabs>()
    private val trace = SessionDiagnostics.trace("tabs", "project" to DiagnosticRef.of(project.basePath), "tracker" to SessionDiagnostics.nextId())
    private var inspectionTrace = trace

    private val registry = TabRegistry<Content>()
    private val pendingSessions = PendingSessionAssociations<Content>(onExpired = {
        trace.event("pending_expired", "tab" to DiagnosticRef.tab(it))
    })
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
        tabClosed = { content ->
            trace.event("tab_closed", "tab" to DiagnosticRef.tab(content), "session" to DiagnosticRef.of(registry.sessionFor(content)))
            registry.forget(content); endedSessions.remove(content); pendingSessions.forget(content)
        },
    )

    val sessionIds: Set<String> get() = registry.sessionIds

    fun owns(sessionId: String): Boolean = registry.owns(sessionId)

    /** Dispose just this session's terminal tab. Must run on the EDT. */
    fun close(sessionId: String): Boolean {
        trace.event("close_requested", "session" to DiagnosticRef.of(sessionId))
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
        trace.event("pending_launch", "tab" to DiagnosticRef.tab(terminal.content), "engine" to terminal.diagnosticEngine, "existingSessions" to existingIds.size)
        terminal.content?.let { pendingSessions.mark(it, existingIds) }
        tabObserver.refresh()
    }

    internal fun cancelPending(terminal: TerminalHandle) {
        trace.event("pending_cancelled", "tab" to DiagnosticRef.tab(terminal.content))
        terminal.content?.let(pendingSessions::forget)
    }

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
    internal fun copySessionResolver(content: Content, requestTrace: DiagnosticTrace = trace): () -> Session? {
        val previous = sessionFor(content)
        val previousGeneration = registry.generation(content)
        val last = lastSessionFor(content)
        val terminal = TerminalTabs.terminalOf(project, content)
        val shell = terminal?.shellPid()
        val title = terminal?.applicationTitle()
        val executables = executables()
        val codexHome = SeshlogSettings.getInstance().resolvedCodexDataDir()
        requestTrace.event("copy_captured", "tab" to DiagnosticRef.tab(content), "shell" to shell,
            "previous" to DiagnosticRef.of(previous), "ended" to DiagnosticRef.of(last), "generation" to previousGeneration)
        return {
            val index = SessionIndex.getInstance()
            val id = if (shell == null) last else SessionProcess.copySession(last, index.sessions,
                inspect = { SessionProcess.inspect(shell, it, executables, codexHome, title) }, rescan = { index.scanNow() })
            requestTrace.event("copy_resolved", "tab" to DiagnosticRef.tab(content), "shell" to shell,
                "previous" to DiagnosticRef.of(previous), "ended" to DiagnosticRef.of(last), "session" to DiagnosticRef.of(id))
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
        trace.event("disposed_tab_forgotten", "tab" to DiagnosticRef.tab(content), "session" to DiagnosticRef.of(sessionId))
        registry.forget(content)
        return null
    }

    private var running = emptySet<String>()
    private val processInspection = ProcessInspection(
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        ui = { ApplicationManager.getApplication().invokeLater(it) },
        failure = { inspectionTrace.failure("poll_failed", it); LOG.debug("Terminal process inspection failed", it) },
    )
    private var disposed = false
    private var pollingState = ProcessPollingPolicy.State()
    private val batchInspector = ProcessBatchInspector()
    private val processClock = Timer(2_000) { refreshRunning() }

    /** Cached process evidence; collecting it must never block the EDT. */
    fun runningSessionIds(): Set<String> = running.intersect(registry.sessionIds)

    /** Capture on EDT, inspect on a worker, then validate again on EDT before focusing. */
    internal class NavigationTarget(
        val inspect: () -> Boolean,
        val current: () -> Boolean,
        val focus: () -> Boolean,
    )

    internal fun navigationTarget(session: Session, sessionCurrent: () -> Boolean): NavigationTarget? {
        val expected = registry.association(session.id) ?: return null
        val terminal = terminalFor(session.id) ?: return null
        val shell = terminal.shellPid() ?: return null
        val title = terminal.applicationTitle()
        val commands = executables()
        val codexHome = SeshlogSettings.getInstance().resolvedCodexDataDir()
        val current = {
            !disposed && !project.isDisposed && sessionCurrent() && !Disposer.isDisposed(expected.tab) && registry.matches(expected) &&
                terminalFor(session.id)?.shellPid() == shell
        }
        return NavigationTarget(
            inspect = {
                // Duplicate provider ids cannot be safely addressed by the older id-only tab registry.
                val sessions = SessionIndex.getInstance().sessions
                sessions.count { it.id == session.id } == 1 &&
                    SessionProcess.inspect(shell, sessions, commands, codexHome, title).sessionIds.singleOrNull() == session.id
            },
            current = current,
            focus = { current() && focus(session.id, current) },
        )
    }

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
        val poll = trace.with("poll" to SessionDiagnostics.nextId())
        val pollStart = System.nanoTime()
        inspectionTrace = poll
        val tabs = TerminalTabs.tabsWithShellPids(project)
        val inspected = tabs.filterValues { it != null }
        // Titles are UI state: read them here with the shell pids, never on the worker.
        val titles = inspected.entries.associate { (tab, shell) -> shell!! to TerminalTabs.terminalOf(project, tab)?.applicationTitle() }
        poll.event("poll_started", "indexedSessions" to snapshot.size, "tabs" to tabs.size, "owned" to registry.sessionIds.size, "delayMs" to processClock.delay)
        (tabs.keys + knownContents()).distinct().forEach { tab ->
            val terminal = TerminalTabs.terminalOf(project, tab)
            poll.event("tab_snapshot", "tab" to DiagnosticRef.tab(tab), "enumerated" to tabs.containsKey(tab),
                "attached" to (tab.manager != null), "disposed" to Disposer.isDisposed(tab), "adapter" to (terminal != null),
                "engine" to terminal?.diagnosticEngine, "shell" to tabs[tab], "session" to DiagnosticRef.of(registry.sessionFor(tab)),
                "generation" to registry.generation(tab), "pending" to pendingSessions.isPending(tab), "ended" to DiagnosticRef.of(endedSessions[tab]))
        }
        val previousOwners = inspected.keys.associateWith(registry::sessionFor)
        val previousGenerations = registry.generations(inspected.keys)
        val executables = executables()
        val codexHome = SeshlogSettings.getInstance().resolvedCodexDataDir()
        val watched = agents.snapshot()
        processInspection.run(inspect = {
            val batch = batchInspector.inspect(inspected.values.filterNotNull().toSet(), snapshot, executables, codexHome, poll, titles)
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
                (inspected.keys - stableTabs).forEach { poll.event("tab_result_rejected", "tab" to DiagnosticRef.tab(it), "reason" to "disposed_or_shell_changed") }
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
                    val usable = !pendingSessions.isPending(discovery.tab) ||
                        pendingSessions.candidate(discovery.tab, idsByTab[discovery.tab].orEmpty()) != null
                    if (!usable) poll.event("tab_result_rejected", "tab" to DiagnosticRef.tab(discovery.tab), "reason" to "pending_requires_fresh_identity")
                    usable
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
                        displayTitle = SessionOrganisation.getInstance()::title,
                        generationsAtInspection = previousGenerations,
                        generationsNow = generationNow,
                        runningProcesses = validFound,
                    ), poll,
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
                    if (!registry.adoptDiscovered(id, tab, previousOwners[tab], previousGenerations[tab])) {
                        poll.event("adoption_rejected", "tab" to DiagnosticRef.tab(tab), "session" to DiagnosticRef.of(id), "reason" to "ownership_or_generation_changed")
                        continue
                    }
                    poll.event("tab_adopted", "tab" to DiagnosticRef.tab(tab), "session" to DiagnosticRef.of(id),
                        "previous" to DiagnosticRef.of(previousOwners[tab]), "generation" to registry.generation(tab), "pending" to pending)
                    if (pending) {
                        pendingSessions.resolve(tab, candidateIds)
                        SessionIndex.getInstance().sessionById(id)?.let {
                            SessionRestoreManager.getInstance(project).recordLaunch(it)
                        }
                    }
                }
                for ((id, tab) in reconciliation.releaseTabs) {
                    if (Disposer.isDisposed(tab)) continue
                    if (TerminalTabs.terminalOf(project, tab)?.shellPid() != inspected[tab]) continue
                    if (registry.release(id, tab, previousGenerations[tab])) {
                        endedSessions[tab] = id
                        poll.event("tab_released", "tab" to DiagnosticRef.tab(tab), "session" to DiagnosticRef.of(id), "reason" to "observed_process_exited")
                    }
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
                    poll.event("tab_retitled", "tab" to DiagnosticRef.tab(content))
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
                val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
                val cadence = ProcessPollingPolicy.next(pollingState, active, changed)
                pollingState = cadence.state
                processClock.delay = cadence.delayMillis.toInt()
                poll.event("poll_applied", "runningBefore" to DiagnosticRef.ids(running), "runningAfter" to DiagnosticRef.ids(valid),
                    "elapsedMs" to (System.nanoTime() - pollStart) / 1_000_000,
                    "exited" to DiagnosticRef.ids(exited), "found" to validFound.mapKeys { DiagnosticRef.of(it.key)!! }.mapValues { it.value.pid() },
                    "owned" to registry.snapshot().mapKeys { DiagnosticRef.of(it.key)!! }.mapValues { DiagnosticRef.tab(it.value) },
                    "nextDelayMs" to cadence.delayMillis)
                if (running != valid) {
                    running = valid
                    project.messageBus.syncPublisher(RUNNING_TOPIC).runningChanged(runningSessionIds())
                }
            } else poll.event("poll_discarded", "reason" to "project_disposed")
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
            trace.event("tracking_failed", "session" to DiagnosticRef.of(session.id), "reason" to "no_content")
            LOG.debug("No tab content for session ${session.id}; not tracking")
            return
        }
        trace.event("tab_tracked", "tab" to DiagnosticRef.tab(content), "session" to DiagnosticRef.of(session.id), "kind" to session.kind,
            "previous" to DiagnosticRef.of(registry.sessionFor(content)), "engine" to widget.diagnosticEngine)
        registry.register(session.id, content)
        agents.forget(session.id)
        tabObserver.refresh()
        refreshRunning()
    }

    /** Bring the tab running [sessionId] to the front. Returns false when we do not own one. */
    fun focus(sessionId: String): Boolean = focus(sessionId) { true }

    private fun focus(sessionId: String, stillCurrent: () -> Boolean): Boolean {
        val content = undisposedContent(sessionId) ?: return false.also { trace.event("focus_failed", "session" to DiagnosticRef.of(sessionId), "reason" to "no_owned_tab") }
        val expected = registry.association(sessionId) ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            ?: return false
        val manager = content.manager
        if (manager == null || manager.isDisposed) return false.also { trace.event("focus_failed", "session" to DiagnosticRef.of(sessionId), "reason" to "manager_unavailable") }
        trace.event("tab_focused", "session" to DiagnosticRef.of(sessionId), "tab" to DiagnosticRef.tab(content))
        toolWindow.activate({
            if (stillCurrent() && !project.isDisposed && !Disposer.isDisposed(content) &&
                registry.matches(expected) && content.manager === manager && !manager.isDisposed)
                manager.setSelectedContent(content, true)
        }, true)
        return true
    }

    /** Subscribe to index updates; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        trace.event("tracking_started")
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) = sync(sessions)
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SessionOrganisation.TOPIC,
            object : SessionOrganisation.Listener {
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
        val organisation = SessionOrganisation.getInstance()
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
        if (!CompletionViewObserver.isViewed(content.component)) return
        lastSessionFor(content)?.let {
            SessionAttentionState.getInstance().viewedTerminal(it)
        }
    }

    override fun dispose() {
        trace.event("tracking_stopped", "owned" to DiagnosticRef.ids(registry.sessionIds), "running" to DiagnosticRef.ids(running))
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
