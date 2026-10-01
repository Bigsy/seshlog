package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionContinuations
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.ui.ConversationEditorTabs
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction

/** Retain read-only access to the transcripts folded into the latest continuation's row. */
class EarlierSessionsAction : ActionGroup("Earlier Sessions", true), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun history(e: AnActionEvent) = sessionFrom(e)?.let {
        SessionContinuations(SessionIndex.getInstance().sessions).history(it)
    }.orEmpty()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && history(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return emptyArray()
        return history(e).map { previous ->
            val title = SessionOrganisation.getInstance().title(previous)
            object : DumbAwareAction("$title (${previous.id.take(8)})") {
                override fun actionPerformed(e: AnActionEvent) {
                    e.project?.let { ConversationEditorTabs.open(it, previous) }
                }
            }
        }.toTypedArray()
    }
}
