package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.copy.CopyRequest
import com.hedworth.seshlog.copy.CopyTarget
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

@Service(Service.Level.PROJECT)
class SessionClipboard(project: Project) {
    val requests = CopyRequest(
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        later = { ApplicationManager.getApplication().invokeLater(it) },
        disposed = { project.isDisposed },
        copy = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
        feedback = { notify(project, it, NotificationType.INFORMATION) },
    )
}

class CopyLastAssistantMessageAction : CopySessionContentAction("Seshlog: Copy Last Assistant Message")

class CopyLatestPlanAction : CopySessionContentAction("Seshlog: Copy Latest Plan", plan = true)

open class CopySessionContentAction(text: String, private val plan: Boolean = false) : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val trace = SessionDiagnostics.trace("copy", "project" to DiagnosticRef.of(project.basePath),
            "request" to SessionDiagnostics.nextId(), "content" to if (plan) "plan" else "last_assistant_message")
        trace.event("copy_requested")
        val target = TerminalTabs.actionTarget(project, e, trace)
        val index = SessionIndex.getInstance()
        val resolve = target.content?.let { OwnedTerminalTabs.getInstance(project).copySessionResolver(it, trace) }
        val session = CopyTarget.resolve(target.isTerminal, null, sessionFrom(e))
        trace.event("copy_target", "terminal" to target.isTerminal, "tab" to DiagnosticRef.tab(target.content),
            "session" to DiagnosticRef.of(session?.id), "resolver" to (resolve != null),
            "reason" to when {
                resolve != null -> "inspect_terminal"
                session != null -> "context_session"
                target.isTerminal -> "terminal_content_not_found"
                else -> "no_session_in_context"
            })
        project.getService(SessionClipboard::class.java).requests.start(session, resolve = resolve) {
            try {
                val provider = index.providerFor(it)
                (if (plan) provider.latestPlan(it) else provider.lastAssistantMessage(it)).also { result ->
                    trace.event("copy_loaded", "session" to DiagnosticRef.of(it.id), "result" to result.javaClass.simpleName)
                }
            } catch (e: Exception) {
                trace.failure("copy_load_failed", e)
                throw e
            }
        }
    }
}
