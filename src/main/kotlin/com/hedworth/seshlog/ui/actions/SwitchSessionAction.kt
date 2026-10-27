package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.switcher.SessionSwitcher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class SwitchSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.isDisposed == false }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let(SessionSwitcher::show) }
}
