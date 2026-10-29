package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.ui.AttentionInboxDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class AttentionInboxAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.isDisposed == false }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { AttentionInboxDialog(it).show() } }
}
