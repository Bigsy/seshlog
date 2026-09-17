package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.copy.CopyRequest
import com.hedworth.seshlog.copy.CopyTarget
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.notification.NotificationType
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

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
        // The action's component also works for popup menus and modeless conversation windows.
        val focus = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val window = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val terminal = focus != null && window != null && SwingUtilities.isDescendingFrom(focus, window.component)
        val content = if (terminal) CopyTarget.focusedContent(focus, TerminalTabs.contents(project)) { it.component } else null
        val index = SessionIndex.getInstance()
        val associated = content?.let { OwnedTerminalTabs.getInstance(project).sessionFor(it) }?.let(index::sessionById)
        val session = CopyTarget.resolve(terminal, associated, e.getData(SeshlogDataKeys.SESSION))
        project.getService(SessionClipboard::class.java).requests.start(session) {
            val provider = index.providerFor(it)
            if (plan) provider.latestPlan(it) else provider.lastAssistantMessage(it)
        }
    }
}
