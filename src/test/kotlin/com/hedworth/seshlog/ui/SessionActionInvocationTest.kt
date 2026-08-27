package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.ui.actions.SessionAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.KeyEvent
import java.nio.file.Paths
import java.time.Instant
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The tree's Enter and double-click handlers run the Resume action on the current selection. They
 * used to go through the deprecated `ActionUtil.invokeAction`; they now call the action directly,
 * because both non-deprecated alternatives are unusable here — `ActionUtil.performAction` only
 * exists from 2025.2, and `ActionManager.tryToExecute` defers via
 * `IdeFocusManager.doWhenFocusSettlesDown`, which would make these handlers asynchronous.
 *
 * What these tests pin is the behaviour that has to survive that swap: Enter runs the action for the
 * selected session, and does nothing at all when there is no selection.
 */
class SessionActionInvocationTest : BasePlatformTestCase() {

    /** Stands in for ResumeSessionAction: same base class, but touches no terminal. */
    private class ProbeAction : SessionAction("Probe") {
        var performedWith: Session? = null
        var performedCount = 0

        override fun perform(project: Project, session: Session) {
            performedWith = session
            performedCount++
        }
    }

    fun `test the action runs synchronously for the selected session`() {
        withPanel { panel ->
            val session = session("s", Paths.get(project.basePath!!))
            panel.render(listOf(session))
            select(panel, session)
            assertEquals("s", panel.selectedSession()?.id)

            val probe = ProbeAction()
            panel.invoke(probe)

            // Synchronous: no event pumping, no focus settling.
            assertEquals(1, probe.performedCount)
            assertEquals("s", probe.performedWith?.id)
        }
    }

    fun `test the action does not run when nothing is selected`() {
        withPanel { panel ->
            panel.render(listOf(session("s", Paths.get(project.basePath!!))))
            panel.tree.clearSelection()
            assertNull(panel.selectedSession())

            val probe = ProbeAction()
            panel.invoke(probe)

            assertEquals(0, probe.performedCount)
            assertNull(probe.performedWith)
        }
    }

    fun `test Enter on the tree is wired to an action invocation`() {
        withPanel { panel ->
            val session = session("s", Paths.get(project.basePath!!))
            panel.render(listOf(session))
            select(panel, session)

            // The Enter binding exists and targets the selection; the action it runs is Resume,
            // which opens a terminal, so only the wiring is asserted here.
            val enter = panel.tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
            assertNotNull("Enter is not bound on the session tree", enter)
        }
    }

    private fun withPanel(body: (SessionTreePanel) -> Unit) {
        val disposable = Disposer.newDisposable()
        try {
            SeshlogSettings.getInstance().showAllProjects = true
            body(SessionTreePanel(project, disposable))
        } finally {
            SeshlogSettings.getInstance().showAllProjects = false
            Disposer.dispose(disposable)
        }
    }

    private fun select(panel: SessionTreePanel, session: Session) {
        val root = panel.tree.model.root as DefaultMutableTreeNode
        val node = TreeUtil.findNodeWithObject(root, session)!!
        panel.tree.selectionPath = TreePath(node.path)
    }

    private fun session(id: String, cwd: java.nio.file.Path) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = "Title $id", cwd = cwd, gitBranch = "main",
        startedAt = Instant.parse("2026-08-26T10:00:00Z"), lastActivityAt = Instant.parse("2026-08-26T10:00:00Z"),
        transcriptPath = cwd.resolve("$id.jsonl"), isLive = false, livePid = null, promptTitle = null,
        promptCount = 3, hasExplicitTitle = true,
    )
}
