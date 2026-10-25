package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

class OpenSessionDiagnosticsAction : DumbAwareAction("Seshlog: Open Session Diagnostics") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val tab = e.project?.let { TerminalTabs.actionTarget(it, e).content }
        val id = sessionFrom(e)?.id ?: e.project?.let { project -> tab?.let { OwnedTerminalTabs.getInstance(project).lastSessionFor(it) } }
        show(e.project, id, tab)
    }

    companion object {
        /** Flush off EDT before revealing, so the incident marker is available for copying. */
        fun show(project: Project? = null, sessionId: String? = null, tab: Any? = null) {
            SessionDiagnostics.trace("report").event("report_requested", "project" to DiagnosticRef.of(project?.basePath),
                "session" to DiagnosticRef.of(sessionId), "tab" to DiagnosticRef.tab(tab))
            val app = ApplicationManager.getApplication()
            app.executeOnPooledThread {
                val diagnostics = SessionDiagnostics.getInstance()
                val written = diagnostics.flush()
                app.invokeLater {
                    if (!app.isDisposed && project?.isDisposed != true) {
                        if (written) RevealFileAction.openFile(diagnostics.files.current)
                        else NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
                            .createNotification("Session diagnostics could not be flushed. Try again, or check idea.log for a diagnostics write failure.", NotificationType.WARNING)
                            .notify(project)
                    }
                }
            }
        }
    }
}
