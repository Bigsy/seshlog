package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.nio.file.Paths
import java.time.Instant

class SessionFilterActionsTest : BasePlatformTestCase() {
    fun testFilterToggleActionsChangeVisibleRows() {
        val disposable = Disposer.newDisposable()
        val organisation = SessionOrganisation.getInstance()
        val filters = AgentFilterState.getInstance(project)
        val oldMode = filters.mode
        val oldWorktrees = filters.includeWorktrees
        val settings = SeshlogSettings.getInstance()
        val oldAllProjects = settings.showAllProjects
        val session = Session(
            AgentKind.CLAUDE_CODE, "filter-action", "Synthetic", Paths.get(project.basePath!!),
            null, null, Instant.EPOCH, null, false, null, null, 2, true,
        )
        try {
            filters.mode = com.hedworth.seshlog.settings.AgentFilterMode.All
            settings.showAllProjects = true
            organisation.edit(session.id) { it.hidden = true }
            val panel = SessionTreePanel(project, disposable)
            panel.render(listOf(session))
            assertTrue(panel.visibleSessions.isEmpty())

            val actions = panel.createFilterActions().getChildren(null)
            val hidden = actions.filterIsInstance<ToggleAction>().first { it.templatePresentation.text == "Show hidden" }
            val worktrees = actions.filterIsInstance<ToggleAction>().first { it.templatePresentation.text == "Sibling worktrees" }
            val event = AnActionEvent.createFromDataContext("test", null, DataContext { null })
            hidden.setSelected(event, true)
            assertTrue(hidden.isSelected(event))
            panel.render(listOf(session))
            assertEquals(listOf(session), panel.visibleSessions)
            worktrees.setSelected(event, false)
            assertFalse(worktrees.isSelected(event))
        } finally {
            organisation.edit(session.id) { it.hidden = false }
            filters.mode = oldMode
            filters.includeWorktrees = oldWorktrees
            settings.showAllProjects = oldAllProjects
            Disposer.dispose(disposable)
            organisation.loadState(SessionOrganisation.State())
        }
    }
}
