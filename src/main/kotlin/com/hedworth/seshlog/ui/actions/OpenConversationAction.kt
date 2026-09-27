package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.ui.ConversationEditorTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.icons.AllIcons

class OpenConversationAction : DumbAwareAction("Open Conversation", "Read the conversation and navigate search matches", AllIcons.Actions.Preview) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && sessionFrom(e) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = sessionFrom(e) ?: return
        ConversationEditorTabs.open(project, session, e.getData(SeshlogDataKeys.PANEL)?.activeQuery.orEmpty())
    }
}
