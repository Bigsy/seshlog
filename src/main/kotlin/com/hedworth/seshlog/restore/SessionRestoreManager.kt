package com.hedworth.seshlog.restore

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.RestoreMode
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remembers which sessions run in *this project's* terminal tabs (snapshot on every index update
 * and on project close) and, on project open, offers to bring back the ones that were live at
 * shutdown. Restoring means running `claude --resume <id>` in a terminal tab — preferably the
 * idle, same-named tab the terminal plugin itself restores, else a new one.
 */
@Service(Service.Level.PROJECT)
class SessionRestoreManager(private val project: Project) : Disposable {
    private val LOG = logger<SessionRestoreManager>()

    private val settings get() = SeshlogSettings.getInstance()
    private val state get() = RestoreState.getInstance(project)
    private val index get() = SessionIndex.getInstance()

    /** Session ids Seshlog launched from this project (this IDE run). */
    private val launched: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val started = AtomicBoolean(false)

    /** Ids that were live when the project was last open; consumed by [offerRestore]. */
    @Volatile
    private var pending: List<String> = emptyList()

    fun recordLaunch(session: Session) {
        launched += session.id
        snapshot(index.sessions)
    }

    /** Called once from the startup activity, on a background thread. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Take the shutdown snapshot out before any new scan overwrites it.
        pending = state.liveSessionIds
        state.liveSessionIds = emptyList()
        LOG.debug("Pending restore for ${project.name}: $pending")

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) {
                snapshot(sessions)
                offerRestore(sessions)
            }
        })
        connection.subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosing(closing: Project) {
                if (closing == project) snapshot(index.sessions)
            }
        })

        // Sessions already scanned before we subscribed (another project's tool window, say).
        val current = index.sessions
        if (current.isNotEmpty() && index.lastScanMillis > 0) {
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) offerRestore(current) })
        }
        index.refresh()
    }

    /** Persist what runs in this project's terminals now. Called on the EDT (index updates arrive there). */
    private fun snapshot(sessions: List<Session>) {
        if (project.isDisposed) return
        val ids = RestoreCandidates.snapshot(sessions, launched.toSet(), TerminalTabs.shellPids(project), ProcessTree.System)
        if (ids != state.liveSessionIds) {
            LOG.debug("Live sessions for ${project.name}: $ids")
            state.liveSessionIds = ids
        }
    }

    private fun offerRestore(sessions: List<Session>) {
        val ids = pending
        if (ids.isEmpty()) return
        pending = emptyList()
        val plan = RestoreCandidates.plan(ids, sessions, ProcessTree.System)
        LOG.debug("Restore plan for ${project.name}: restore=${plan.restore.map { it.id }} orphans=${plan.orphans.map { it.id }} running=${plan.running.map { it.id }}")
        if (plan.restore.isEmpty()) return
        when (settings.restoreMode) {
            RestoreMode.NEVER -> Unit
            RestoreMode.ALWAYS -> whenTerminalReady {
                restore(plan)
                notification("Restored ${describe(plan.restore.size)}", NotificationType.INFORMATION).notify(project)
            }
            RestoreMode.ASK -> {
                val n = notification("Restore ${describe(plan.restore.size)}?", NotificationType.INFORMATION)
                n.setSubtitle(plan.restore.joinToString(", ") { it.title })
                if (plan.orphans.isNotEmpty()) {
                    n.setContent("${plan.orphans.size} left-over claude process${if (plan.orphans.size == 1) "" else "es"} from closed tabs will be stopped first.")
                }
                n.addAction(NotificationAction.createSimpleExpiring("Restore") { whenTerminalReady { restore(plan) } })
                n.addAction(NotificationAction.createSimpleExpiring("Not now") {})
                n.addAction(NotificationAction.createSimpleExpiring("Always") {
                    settings.restoreMode = RestoreMode.ALWAYS
                    whenTerminalReady { restore(plan) }
                })
                n.notify(project)
            }
        }
    }

    /**
     * Run [action] once tool windows are initialised, so the tabs the terminal plugin restores on
     * its own already exist and can be reused instead of duplicated.
     */
    private fun whenTerminalReady(action: () -> Unit) {
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            // Touching the content manager makes the Terminal tool window create its restored tabs.
            ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.contentManager
            action()
        }
    }

    /** Must run on the EDT. */
    fun restore(plan: RestorePlan) {
        for (session in plan.orphans) {
            val pid = session.livePid ?: continue
            LOG.info("Stopping orphaned claude process $pid before resuming session ${session.id}")
            ProcessTree.System.terminate(pid)
        }
        for (session in plan.restore) {
            try {
                TerminalTabs.resume(project, session, index.resumeCommand(session))
                launched += session.id
            } catch (t: Throwable) {
                LOG.warn("Could not restore session ${session.id}", t)
                notification("Could not restore '${session.title}': ${t.message}", NotificationType.ERROR).notify(project)
            }
        }
    }

    private fun describe(n: Int) = if (n == 1) "1 Claude session" else "$n Claude sessions"

    private fun notification(content: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("Seshlog").createNotification(content, type)

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): SessionRestoreManager = project.getService(SessionRestoreManager::class.java)
    }
}

/** Kicks off [SessionRestoreManager] once the project is open. */
class SeshlogStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        OwnedTerminalTabs.getInstance(project).start()
        SessionRestoreManager.getInstance(project).start()
    }
}
