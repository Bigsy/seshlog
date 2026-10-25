package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode

class WorktreePresentationTest : BasePlatformTestCase() {
    private fun session(kind: AgentKind, root: Path) = Session(kind, "synthetic", "Title", root.resolve("src"), "old-branch",
        null, Instant.EPOCH, null, false, null, null, 2, true,
        location = SessionLocation(LocationSource.INITIAL),
        checkout = Checkout(root, Path.of("/repo/.git/worktrees/topic"), Path.of("/repo/.git"),
            Checkout.Kind.LINKED, null, "1234567890abcdef1234567890abcdef12345678"))

    fun `test both agents show recorded worktree in browse search tooltip and accessibility without IO`() {
        for (kind in listOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX)) {
            // These paths do not exist. All presentation comes from immutable observations.
            val item = session(kind, Path.of("/synthetic/<repo>&/topic"))
            val renderer = SessionCellRenderer()
            val tree = com.intellij.ui.treeStructure.Tree()
            fun render() = renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(item), false, false, true, 0, false)
            render()
            val details = renderer.detailsLabel.text
            assertTrue(details.contains("worktree topic"))
            assertTrue(details.contains("detached HEAD (12345678)"))
            assertFalse(details.contains("old-branch"))
            assertTrue(renderer.toolTipText.contains("&lt;repo&gt;&amp;"))
            assertTrue(renderer.toolTipText.contains("Initial recorded directory"))
            assertTrue(renderer.toolTipText.contains("Session-reported branch: old-branch"))
            assertTrue(renderer.accessibleContext.accessibleDescription.contains(item.cwd.toString()))
            assertFalse(renderer.activityLabel.isVisible)
            renderer.hits = mapOf(item.id to SearchHit(item, 1, false, "a match"))
            render()
            assertEquals(details, renderer.detailsLabel.text)
            assertTrue(renderer.accessibleContext.accessibleName.contains("detached HEAD"))
            val other = item.copy(checkout = item.checkout!!.copy(root = Path.of("/different/topic")))
            assertFalse(WorktreePresentation.description(other) == WorktreePresentation.description(item))
        }
    }

    fun `test uncertain missing and historical checkout labels do not imply activity`() {
        val base = session(AgentKind.CODEX, Path.of("/synthetic/topic"))
        val item = base.copy(checkout = base.checkout!!.unavailable("removed"),
            location = base.location!!.copy(uncertain = true, explanation = "conflicting context"))
        assertTrue(WorktreePresentation.details(item)!!.contains("unavailable"))
        assertTrue(WorktreePresentation.details(item)!!.contains("location uncertain"))
        assertTrue(WorktreePresentation.description(item).any { it.contains("conflicting context") })
        assertTrue(WorktreePresentation.description(item).any { it.contains("Last observed Git branch") })
        val main = base.copy(checkout = base.checkout.copy(kind = Checkout.Kind.MAIN, branch = "current"))
        assertEquals("current", WorktreePresentation.details(main))
        assertEquals("old-branch", WorktreePresentation.details(base.copy(checkout = null)))
    }
}
