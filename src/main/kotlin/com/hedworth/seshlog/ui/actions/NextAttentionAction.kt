package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.ui.SessionTreePanel
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class NextAttentionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val panel = e.getData(SeshlogDataKeys.PANEL)
        e.presentation.isEnabled = e.project != null && if (panel != null) panel.nextAttentionSession() != null
            else com.hedworth.seshlog.settings.SessionAttentionState.getInstance().unreadIds.isNotEmpty()
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("Seshlog") ?: return
        window.activate({
            if (!project.isDisposed) {
                val panel = window.contentManager.contents.mapNotNull { it.component as? SessionTreePanel }.firstOrNull()
                panel?.showNextAttention()
            }
        })
    }
}
