package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.util.messages.Topic
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level, in-memory map of session id → Terminal tool window tab: the tabs Seshlog opened
 * or reused, plus tabs adopted on every index update because their shell owns a live `claude`
 * process. Lets "Resume" on a live session focus its tab and keeps tab titles in step with the
 * session titles Claude writes later (`ai-title`, `custom-title`).
 */
@Service(Service.Level.PROJECT)
class OwnedTerminalTabs(private val project: Project) : Disposable {
    private val LOG = logger<OwnedTerminalTabs>()

    private val registry = TabRegistry<Content>()
    private val started = AtomicBoolean(false)
    var activeSessionId: String? = null
        private set

    private val tabObserver = TerminalTabObserver(
        rootManager = { ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.contentManager },
        openContents = { TerminalTabs.contents(project) },
        selectionChanged = { content -> updateActiveSession(content) },
        tabClosed = { content -> registry.forget(content) },
    )

    fun owns(sessionId: String): Boolean = registry.owns(sessionId)

    /** Dispose just this session's terminal tab. Must run on the EDT. */
    fun close(sessionId: String): Boolean {
        val content = registry.tabFor(sessionId) ?: return true
        val manager = content.manager
        if (manager != null && !manager.removeContent(content, true)) return false
        registry.forget(content)
        tabObserver.refresh()
        return true
    }

    /** The id of the session running in terminal tab [content], if we know one. */
    fun sessionFor(content: Content): String? = registry.sessionFor(content)

    /** The widget of the tab we own for [sessionId], if the tab still exists. Must be called on the EDT. */
    fun widgetFor(sessionId: String): TerminalWidget? {
        val content = registry.tabFor(sessionId) ?: return null
        val widget = TerminalTabs.widgetOf(content)
        if (widget == null) registry.forget(content)
        return widget
    }

    /** Remember that [widget]'s tab runs [session]. Must be called on the EDT. */
    fun track(session: Session, widget: TerminalWidget) {
        val content = TerminalTabs.contentOf(project, widget) ?: run {
            LOG.debug("No tab content for session ${session.id}; not tracking")
            return
        }
        registry.register(session.id, content)
        tabObserver.refresh()
    }

    /** Bring the tab running [sessionId] to the front. Returns false when we do not own one. */
    fun focus(sessionId: String): Boolean {
        val content = registry.tabFor(sessionId) ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            ?: return false
        val manager = content.manager
        if (manager == null || manager.isDisposed) {
            registry.forget(content)
            return false
        }
        toolWindow.activate({ manager.setSelectedContent(content, true) }, true)
        return true
    }

    /** Subscribe to index updates; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) = sync(sessions)
        })
    }

    /** Adopt tabs by process ancestry and retitle owned tabs. Runs on the EDT (index updates arrive there). */
    fun sync(sessions: List<Session>) {
        if (project.isDisposed) return
        val openTabs = TerminalTabs.tabsWithShellPids(project)
        if (openTabs.isEmpty() && registry.sessionIds.isEmpty()) return
        val retitles = registry.sync(sessions, openTabs, ProcessTree.System) { it.displayName }
        for ((content, title) in retitles) {
            LOG.debug("Retitling terminal tab '${content.displayName}' -> '$title'")
            content.displayName = title
        }
        tabObserver.refresh()
    }

    private fun updateActiveSession(content: Content?) {
        val sessionId = content?.let(registry::sessionFor)
        if (sessionId == activeSessionId) return
        activeSessionId = sessionId
        project.messageBus.syncPublisher(ACTIVE_SESSION_TOPIC).activeSessionChanged(sessionId)
    }

    override fun dispose() = tabObserver.dispose()

    interface ActiveSessionListener {
        fun activeSessionChanged(sessionId: String?)
    }

    companion object {
        val ACTIVE_SESSION_TOPIC = Topic.create("Seshlog active terminal", ActiveSessionListener::class.java)
        fun getInstance(project: Project): OwnedTerminalTabs = project.getService(OwnedTerminalTabs::class.java)
    }
}
