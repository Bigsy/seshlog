package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.SessionViewState
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.tree.TreeUtil
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class SessionViewControlTest : BasePlatformTestCase() {
    private fun session(id: String, folder: String, at: Long) = Session(
        AgentKind.CLAUDE_CODE, id, "Title $id", Path.of("/synthetic/$folder"), "feature/$folder",
        Instant.EPOCH, Instant.ofEpochSecond(at), null, false, null, null, 3, true,
    )
    private fun event(panel: SessionTreePanel) = AnActionEvent.createFromDataContext("test", null, DataContext(panel::getData))

    fun `test flat order is global and pins retain stable priority in either presentation`() {
        val a = session("best", "one", 1)
        val b = session("second", "two", 3)
        val c = session("weak", "one", 2)
        val hits = listOf(SearchHit(a, 10.0, false, "hit"), SearchHit(b, 5.0, false, "hit"), SearchHit(c, 1.0, false, "hit"))
        assertEquals(listOf(a, b, c), SessionTreeModel.flatRanked(hits))
        assertEquals(listOf(a, c, b), SessionTreeModel.groupRanked(hits).flatMap { it.sessions })
        assertEquals(listOf(c, a, b), SessionTreeModel.flatRanked(hits) { it.id == c.id })
        assertEquals(listOf(c, a, b), SessionTreeModel.groupRanked(hits) { it.id == c.id }.flatMap { it.sessions })
        assertEquals(listOf(b, c, a), SessionTreeModel.flat(listOf(a, b, c)))
        assertEquals(listOf(c, b, a), SessionTreeModel.flat(listOf(a, b, c)) { it.id == c.id })
    }

    fun `test controls preserve session selection snippets native folder context and collapsed folders`() {
        val owner = Disposer.newDisposable()
        val state = SessionViewState.getInstance(project)
        val previous = state.state
        try {
            state.loadState(SessionViewState.State())
            val panel = SessionTreePanel(project, owner)
            val a = session("one", "harbor", 10)
            val b = session("two", "lantern", 20)
            panel.searchField.text = "needle"
            val hits = listOf(SearchHit(a, 10.0, false, "needle one"), SearchHit(b, 1.0, false, "needle two"))
            panel.renderSearchResults("needle", hits)
            assertFalse(panel.grouped)
            assertEquals(SearchSort.BEST_MATCH, panel.selectedSort)
            assertFalse(panel.tree.showsRootHandles)
            var root = panel.tree.model.root as DefaultMutableTreeNode
            assertEquals(listOf(a, b), root.children().toList().map { (it as DefaultMutableTreeNode).userObject })
            panel.tree.selectionPath = TreePath((root.getChildAt(1) as DefaultMutableTreeNode).path)
            assertEquals(b.cwd, panel.getData(SeshlogDataKeys.PROJECT_CWD.name))
            assertNull(panel.getData(SeshlogDataKeys.PROJECT_GROUP.name))
            assertEquals(hits[1], panel.selectedSearchHit())
            val renderer = panel.tree.cellRenderer as SessionCellRenderer
            renderer.getTreeCellRendererComponent(panel.tree, root.getChildAt(1), false, false, true, 1, false)
            assertTrue(renderer.folderLabel.text.contains("lantern"))
            assertTrue(renderer.folderLabel.text.contains("feature/lantern"))
            assertTrue(renderer.folderLabel.text.contains(b.cwd.toString()))

            panel.createGroupAction().setSelected(event(panel), true)
            assertTrue(panel.grouped)
            assertEquals(b.id, panel.selectedSession()!!.id)
            root = panel.tree.model.root as DefaultMutableTreeNode
            val firstFolder = root.getChildAt(0) as DefaultMutableTreeNode
            panel.tree.collapsePath(TreePath(firstFolder.path))
            panel.createGroupAction().setSelected(event(panel), false)
            assertEquals(b.id, panel.selectedSession()!!.id)
            // Native Down navigation addresses the next session directly in the flat tree.
            val first = TreeUtil.findNodeWithObject(panel.tree.model.root as DefaultMutableTreeNode, a)!!
            panel.tree.selectionPath = TreePath(first.path)
            panel.tree.actionMap.get("selectNext").actionPerformed(java.awt.event.ActionEvent(panel.tree, 0, "next"))
            assertEquals(b.id, panel.selectedSession()!!.id)
            panel.createGroupAction().setSelected(event(panel), true)
            root = panel.tree.model.root as DefaultMutableTreeNode
            assertFalse(panel.tree.isExpanded(TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path)))
            assertEquals(b.id, panel.selectedSession()!!.id)
            assertEquals(hits[1], panel.selectedSearchHit())
            panel.searchField.text = ""
            assertEquals(SearchSort.RECENTLY_ACTIVE, panel.selectedSort)
            assertTrue(panel.grouped)
            panel.createGroupAction().setSelected(event(panel), false)
            panel.searchField.text = "needle"
            panel.renderSearchResults("needle", hits)
            assertTrue(panel.grouped) // browsing choice did not overwrite the explicit search choice
        } finally { Disposer.dispose(owner); state.loadState(previous) }
    }

    fun `test sort changes clear old hits and reject a late result with the previous sort`() {
        val owner = Disposer.newDisposable()
        val state = SessionViewState.getInstance(project)
        val previous = state.state
        try {
            state.loadState(SessionViewState.State())
            val panel = SessionTreePanel(project, owner)
            panel.searchField.text = "needle"
            val a = session("older", "harbor", 10)
            val old = SearchResultSnapshot(listOf(SearchHit(a, 1.0, false, "needle")), SearchCoverage(1, 1, 0, 0), 0,
                context = SearchContext("needle"))
            panel.renderSearchResults("needle", old)
            val root = panel.tree.model.root as DefaultMutableTreeNode
            panel.tree.selectionPath = TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path)
            val choices = panel.createSortActions().getChildren(event(panel))
            assertEquals(listOf("Best match", "Recently active"), choices.map { it.templatePresentation.text })
            (choices[1] as ToggleAction).setSelected(event(panel), true)
            assertEquals(SearchSort.RECENTLY_ACTIVE, panel.selectedSort)
            assertTrue(panel.visibleSessions.isEmpty())
            assertNull(panel.selectedSearchHit())
            panel.renderSearchResults("needle", old)
            assertTrue(panel.visibleSessions.isEmpty())
            panel.renderSearchResults("needle", old.copy(context = SearchContext("needle", sort = SearchSort.RECENTLY_ACTIVE)))
            assertEquals(a.id, panel.selectedSession()!!.id)
            panel.searchField.text = ""
            val browseEvent = event(panel)
            choices[0].update(browseEvent)
            assertFalse(browseEvent.presentation.isEnabled)
            panel.searchField.text = "needle"
            assertEquals(SearchSort.RECENTLY_ACTIVE, panel.selectedSort)
        } finally { Disposer.dispose(owner); state.loadState(previous) }
    }
}
