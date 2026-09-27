package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.ui.ConversationEditorTabs
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Opens the successor recorded by Claude's `continued-in` record in another editor tab. */
class OpenContinuationAction : DumbAwareAction("Open Continuation", "Open the successor session", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val session = sessionFrom(e)
        e.presentation.isEnabledAndVisible = e.project != null &&
            session?.continuationId?.let { SessionIndex.getInstance().sessionById(it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = sessionFrom(e) ?: return
        val successor = session.continuationId?.let { SessionIndex.getInstance().sessionById(it) } ?: return
        ConversationEditorTabs.open(project, successor)
    }
}
