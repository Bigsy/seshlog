package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class SessionAttentionUiTest : BasePlatformTestCase() {
    private lateinit var previousFilter: com.hedworth.seshlog.settings.AgentFilterMode

    override fun setUp() {
        super.setUp()
        val filter = com.hedworth.seshlog.settings.AgentFilterState.getInstance(project)
        previousFilter = filter.mode
        filter.mode = com.hedworth.seshlog.settings.AgentFilterMode.All
    }
    private fun session(id: String, seconds: Long = 1) = Session(
        AgentKind.CODEX, id, id, Paths.get(project.basePath!!), null, null, Instant.ofEpochSecond(seconds),
        null, true, null, null, 1, true, activity = Activity.WAITING, activitySince = Instant.ofEpochSecond(seconds),
    )

    override fun tearDown() {
        try {
            com.hedworth.seshlog.settings.AgentFilterState.getInstance(project).mode = previousFilter
            SessionAttentionState.getInstance().loadState(SessionAttentionState.State())
            SessionOrganisation.getInstance().loadState(SessionOrganisation.State())
        } finally { super.tearDown() }
    }

    fun `test completion and acknowledgement update collapsed group and child without rebuilding selection`() {
        val disposable = Disposer.newDisposable()
        try {
            val attention = SessionAttentionState.getInstance()
            attention.loadState(SessionAttentionState.State())
            val panel = SessionTreePanel(project, disposable)
            val working = session("working").copy(activity = Activity.WORKING)
            val done = session("done", 2)
            attention.observe(listOf(working, done.copy(activity = Activity.WORKING)), emptySet())
            panel.render(listOf(working, done))
            val root = panel.tree.model.root as DefaultMutableTreeNode
            val group = root.firstChild as DefaultMutableTreeNode
            panel.tree.collapsePath(TreePath(group.path))
            panel.tree.selectionPath = TreePath(group.path)
            var changed = 0
            panel.tree.model.addTreeModelListener(object : javax.swing.event.TreeModelListener {
                override fun treeNodesChanged(e: javax.swing.event.TreeModelEvent) { changed++ }
                override fun treeStructureChanged(e: javax.swing.event.TreeModelEvent) { fail("Badges must not rebuild the tree") }
                override fun treeNodesInserted(e: javax.swing.event.TreeModelEvent) = Unit
                override fun treeNodesRemoved(e: javax.swing.event.TreeModelEvent) = Unit
            })
            attention.observe(listOf(working, done), emptySet())
            val renderer = panel.tree.cellRenderer as SessionCellRenderer
            fun label(value: Any): String {
                renderer.getTreeCellRendererComponent(panel.tree, DefaultMutableTreeNode(value), false, false,
                    value is Session, 0, false)
                return renderer.toString()
            }
            assertTrue(label(group.userObject).contains("1 working · 1 unread"))
            assertTrue(label(done).contains("● unread"))
            assertFalse(panel.tree.isExpanded(TreePath(group.path)))
            assertEquals(group, panel.tree.lastSelectedPathComponent)
            attention.viewed(done.id, attention.receipt(done))
            assertFalse(label(done).contains("unread"))
            assertTrue(label(group.userObject).contains("1 working"))
            assertFalse(label(group.userObject).contains("unread"))
            assertTrue(changed >= 6)
        } finally { Disposer.dispose(disposable) }
    }

    fun `test next action respects pinned tree order filters and expands the target group`() {
        val disposable = Disposer.newDisposable()
        val settings = SeshlogSettings.getInstance()
        val oldPreview = settings.showPreview
        try {
            settings.showPreview = true
            val attention = SessionAttentionState.getInstance()
            attention.loadState(SessionAttentionState.State())
            val panel = SessionTreePanel(project, disposable)
            val pinned = session("pinned")
            val newer = session("newer", 2)
            val hidden = session("hidden", 3)
            SessionOrganisation.getInstance().edit(pinned.id) { it.pinned = true }
            SessionOrganisation.getInstance().edit(hidden.id) { it.hidden = true }
            val all = listOf(newer, hidden, pinned)
            attention.observe(all.map { it.copy(activity = Activity.WORKING) }, emptySet())
            attention.observe(all, emptySet())
            panel.render(all)
            val root = panel.tree.model.root as DefaultMutableTreeNode
            val group = root.firstChild as DefaultMutableTreeNode
            panel.tree.collapsePath(TreePath(group.path))
            assertEquals(pinned.id, panel.nextAttentionSession()?.id)
            panel.showNextAttention()
            assertEquals(pinned.id, panel.selectedSession()?.id)
            assertEquals(pinned.id, panel.preview.session?.id)
            assertTrue(panel.tree.isExpanded(TreePath(group.path)))
            panel.showNextAttention()
            assertEquals(newer.id, panel.selectedSession()?.id)
            assertEquals(pinned.id, panel.nextAttentionSession()?.id)
            assertEquals(3, attention.unreadIds.size) // selection/loading alone never acknowledges
            panel.renderSearchResults("newer", listOf(com.hedworth.seshlog.index.SearchHit(newer, 1, false, null)))
            assertEquals(newer.id, panel.nextAttentionSession()?.id)
            attention.viewed(newer.id, attention.receipt(newer))
            assertNull(panel.nextAttentionSession())
            assertNotNull(ActionManager.getInstance().getAction("Seshlog.NextAttention"))
        } finally { settings.showPreview = oldPreview; Disposer.dispose(disposable) }
    }

    fun `test reading old search matches or incomplete content does not count as viewing latest reply`() {
        val entries = listOf(
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "old reply\n".repeat(100), null), "old"),
            ConversationEntry(ConversationMessage(Role.ASSISTANT, "latest reply", null), "latest"),
        )
        val doc = ConversationDocument.buildEntries(entries)
        val editor = javax.swing.JTextArea(doc.text)
        val viewport = javax.swing.JViewport()
        viewport.setSize(300, 100)
        viewport.view = editor
        editor.setSize(300, editor.preferredSize.height)
        assertFalse(CompletionViewObserver.isLatestReplyVisible(doc, editor))
        val last = editor.modelToView2D(doc.messageRanges.last().last)!!.bounds
        viewport.viewPosition = java.awt.Point(0, last.y - 30)
        assertTrue(CompletionViewObserver.isLatestReplyVisible(doc, editor))
        assertFalse(CompletionViewObserver.isViewed(editor)) // hidden/background loading is not viewing
        val partial = doc.copy(entries = entries.map { it.copy(truncated = true) })
        assertFalse(CompletionViewObserver.isLatestReplyVisible(partial, editor))
    }
}
