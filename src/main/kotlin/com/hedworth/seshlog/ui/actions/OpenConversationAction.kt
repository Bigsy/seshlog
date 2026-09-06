package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.ui.ConversationDialog
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class OpenConversationAction : DumbAwareAction("Open Conversation", "Read the conversation and navigate search matches", com.intellij.icons.AllIcons.Actions.Preview) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(SeshlogDataKeys.SESSION) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = e.getData(SeshlogDataKeys.SESSION) ?: return
        ConversationDialog(project, session, e.getData(SeshlogDataKeys.PANEL)?.activeQuery.orEmpty()).show()
    }
}
