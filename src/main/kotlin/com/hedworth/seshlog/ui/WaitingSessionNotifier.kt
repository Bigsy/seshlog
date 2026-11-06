package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ActivityTransitions
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Balloon when a running session's agent stops working and waits for the user - the moment that
 * is easy to miss with several sessions in flight. Transition-based (see [ActivityTransitions]),
 * so a session already waiting when the IDE opens says nothing. Only sessions running in this
 * project's terminal tabs are reported; agents in external terminals are never tracked.
 */
@Service(Service.Level.PROJECT)
class WaitingSessionNotifier(private val project: Project) : Disposable {
    private val started = AtomicBoolean(false)
    private var previous: Map<String, Session> = emptyMap()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        previous = SessionIndex.getInstance().sessions.associateBy { it.id }
        project.messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) = onSessionsUpdated(sessions)
        })
    }

    /** Index updates arrive on the EDT; terminal state is read there too. */
    internal fun onSessionsUpdated(sessions: List<Session>) {
        val before = previous
        previous = sessions.associateBy { it.id }
        if (project.isDisposed || !SeshlogSettings.getInstance().notifyWhenWaiting) return
        val owned = OwnedTerminalTabs.getInstance(project)
        val running = owned.runningSessionIds()
        for (session in ActivityTransitions.turnedWaiting(before, sessions) { it.id in running }) {
            if (!isOnScreen(owned, session.id)) show(session)
        }
    }

    /** The user is looking at that tab already: the Terminal window shows it and the IDE frame is active. */
    private fun isOnScreen(owned: OwnedTerminalTabs, sessionId: String): Boolean {
        if (owned.activeSessionId != sessionId) return false
        val terminal = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        return terminal?.isVisible == true && WindowManager.getInstance().getFrame(project)?.isActive == true
    }

    private fun show(session: Session) {
        val title = if (session.activity == Activity.INTERRUPTED) "Session interrupted" else "Session is waiting for you"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
            .createNotification(title, SessionOrganisation.getInstance().title(session), NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimpleExpiring("Show Tab") {
            if (project.isDisposed) return@createSimpleExpiring
            if (!OwnedTerminalTabs.getInstance(project).focus(session.id)) {
                ToolWindowManager.getInstance(project).getToolWindow("Seshlog")?.activate(null)
            }
        })
        notification.notify(project)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): WaitingSessionNotifier = project.getService(WaitingSessionNotifier::class.java)
    }
}
