package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Paths
import java.time.Instant

class SeshlogToolWindowTest : BasePlatformTestCase() {

    fun `test panel renders grouped sessions and filters to the project`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, disposable)
            val base = Paths.get(project.basePath!!)
            val inside = session("in", base.resolve("sub"), Instant.parse("2026-08-25T10:00:00Z"))
            val outside = session("out", Paths.get("/somewhere/else"), Instant.parse("2026-08-26T10:00:00Z"))

            SeshlogSettings.getInstance().showAllProjects = false
            panel.render(listOf(outside, inside))
            assertEquals(listOf("in"), panel.visibleSessions.map { it.id })

            SeshlogSettings.getInstance().showAllProjects = true
            panel.render(listOf(inside, outside))
            assertEquals(listOf("out", "in"), SessionTreeModel.group(panel.visibleSessions).flatMap { g -> g.sessions.map { it.id } })
            assertEquals(2, panel.tree.model.getChildCount(panel.tree.model.root))
        } finally {
            SeshlogSettings.getInstance().showAllProjects = false
            Disposer.dispose(disposable)
        }
    }

    fun `test search results are shown ranked and clearing the query restores the list`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, disposable)
            val base = Paths.get(project.basePath!!)
            val a = session("a", base, Instant.parse("2026-08-26T10:00:00Z"))
            val b = session("b", base.resolve("sub"), Instant.parse("2026-08-25T10:00:00Z"))
            val c = session("c", base, Instant.parse("2026-08-24T10:00:00Z"))
            panel.render(listOf(a, b, c))
            assertEquals(listOf("a", "c", "b"), SessionTreeModel.group(panel.visibleSessions).flatMap { g -> g.sessions.map { it.id } })

            panel.renderSearchResults("needle", listOf(
                SearchHit(c, score = 5, titleMatch = false, snippet = "…the needle…"),
                SearchHit(b, score = 2, titleMatch = false, snippet = null),
            ))
            assertEquals("needle", panel.activeQuery)
            assertEquals(listOf("c", "b"), panel.visibleSessions.map { it.id })
            val root = panel.tree.model.root
            assertEquals(2, panel.tree.model.getChildCount(root))
            val firstGroup = panel.tree.model.getChild(root, 0) as javax.swing.tree.DefaultMutableTreeNode
            assertEquals(base, (firstGroup.userObject as ProjectGroup).cwd)
            assertEquals(1, firstGroup.childCount)

            panel.render(listOf(a, b, c))
            assertEquals(3, panel.visibleSessions.size)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun `test preview loads the tail of the selected session`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, disposable)
            val transcript = Paths.get(javaClass.getResource("/fixtures/custom_and_ai_title.jsonl")!!.toURI())
            val base = Paths.get(project.basePath!!)
            val s = session("p", base, Instant.parse("2026-08-26T10:00:00Z")).copy(transcriptPath = transcript)
            SeshlogSettings.getInstance().previewMessageCount = 2
            SeshlogSettings.getInstance().showAllProjects = true
            panel.render(listOf(s))
            assertNull(panel.preview.session)

            val node = com.intellij.util.ui.tree.TreeUtil.findNodeWithObject(panel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode, s)!!
            panel.tree.selectionPath = javax.swing.tree.TreePath(node.path)
            assertEquals("p", panel.preview.session?.id)

            // A real index scan may land while we pump events, re-render the tree and clear the
            // selection (which cancels the load); test loading on a standalone preview instead.
            val preview = SessionPreviewPanel(disposable)
            preview.showSession(s)
            val deadline = System.currentTimeMillis() + 10_000
            while (panel.preview.messages.isEmpty() && System.currentTimeMillis() < deadline) {
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(20)
            }
            assertEquals(listOf("Sure.", "Now also cover the reverse case."), preview.messages.map { it.text })

            preview.showSession(null)
            assertNull(preview.session)
            assertTrue(preview.messages.isEmpty())

            // The MVP bug this exposed: re-rendering without the selected session must not throw.
            panel.render(emptyList())
        } finally {
            SeshlogSettings.getInstance().showAllProjects = false
            Disposer.dispose(disposable)
        }
    }

    fun `test hidden sessions are excluded and pins sort first`() {
        val disposable = Disposer.newDisposable()
        val organisation = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()
        try {
            val panel = SessionTreePanel(project, disposable)
            val base = Paths.get(project.basePath!!)
            val older = session("pin", base, Instant.parse("2026-08-24T10:00:00Z"))
            val newer = session("new", base, Instant.parse("2026-08-26T10:00:00Z"))
            organisation.edit("pin") { it.pinned = true }
            organisation.edit("new") { it.hidden = true }
            panel.render(listOf(newer, older))
            assertEquals(listOf("pin"), panel.visibleSessions.map { it.id })
            organisation.edit("new") { it.hidden = false }
            panel.render(listOf(newer, older))
            val groups = SessionTreeModel.group(panel.visibleSessions) { organisation.metadata(it.id).pinned }
            assertEquals(listOf("pin", "new"), groups.single().sessions.map { it.id })
            assertEquals(newer.lastActivityAt, groups.single().lastActivityAt)
        } finally {
            Disposer.dispose(disposable)
            organisation.loadState(com.hedworth.seshlog.settings.SessionOrganisation.State())
        }
    }

    fun `test collapsed groups and selection survive refresh and search rebuilds`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, disposable)
            val base = Paths.get(project.basePath!!)
            val a = session("state-a", base, Instant.parse("2026-08-26T10:00:00Z"))
            val b = session("state-b", base.resolve("sub"), Instant.parse("2026-08-25T10:00:00Z"))
            panel.render(listOf(a, b))
            fun groupPath(cwd: java.nio.file.Path): javax.swing.tree.TreePath {
                val root = panel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
                val node = (0 until root.childCount).map { root.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode }
                    .single { (it.userObject as ProjectGroup).cwd == cwd }
                return javax.swing.tree.TreePath(node.path)
            }
            panel.tree.collapsePath(groupPath(base))
            val root = panel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
            val selected = com.intellij.util.ui.tree.TreeUtil.findNodeWithObject(root, b)!!
            panel.tree.selectionPath = javax.swing.tree.TreePath(selected.path)
            panel.render(listOf(a.copy(title = "Updated"), b))
            assertFalse(panel.tree.isExpanded(groupPath(base)))
            assertEquals(b.id, panel.selectedSession()?.id)
            panel.renderSearchResults("needle", listOf(SearchHit(b, 1, false, "needle")))
            panel.render(listOf(a, b))
            assertFalse(panel.tree.isExpanded(groupPath(base)))
            assertEquals(b.id, panel.selectedSession()?.id)
            panel.tree.expandPath(groupPath(base))
            panel.render(listOf(a, b))
            assertTrue(panel.tree.isExpanded(groupPath(base)))
        } finally { Disposer.dispose(disposable) }
    }

    fun `test date filter composes with project and agent filters before extraction`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SessionTreePanel(project, disposable)
            val base = Paths.get(project.basePath!!)
            val inside = session("date-in", base, Instant.parse("2026-08-25T12:00:00Z"))
            val old = session("date-old", base, Instant.parse("2026-08-20T12:00:00Z"))
            val outside = inside.copy(id = "date-out", cwd = Paths.get("/elsewhere"))
            val otherAgent = inside.copy(id = "date-agent", kind = AgentKind.CODEX)
            com.hedworth.seshlog.settings.AgentFilterState.getInstance(project).mode =
                com.hedworth.seshlog.settings.AgentFilterMode.Only(AgentKind.CLAUDE_CODE)
            panel.setDateFilter(com.hedworth.seshlog.index.SessionDateFilter(
                com.hedworth.seshlog.index.DatePeriod.CUSTOM, java.time.LocalDate.parse("2026-08-25"), java.time.LocalDate.parse("2026-08-25")))
            panel.render(listOf(inside, old, outside, otherAgent))
            assertEquals(listOf(inside), panel.visibleSessions)
            val extracted = mutableListOf<String>()
            val search = com.hedworth.seshlog.index.ContentSearchIndex({ extracted += it.id; listOf("needle") }, { 1 })
            search.search("needle", panel.visibleSessions)
            assertEquals(listOf(inside.id), extracted)
            panel.setDateFilter(com.hedworth.seshlog.index.SessionDateFilter())
            panel.render(listOf(inside, old, outside, otherAgent))
            assertEquals(listOf(inside, old), panel.visibleSessions)
        } finally { Disposer.dispose(disposable) }
    }

    private fun session(id: String, cwd: java.nio.file.Path, at: Instant) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = "Title $id", cwd = cwd, gitBranch = "main",
        startedAt = at, lastActivityAt = at, transcriptPath = cwd.resolve("$id.jsonl"),
        isLive = false, livePid = null, promptTitle = null, promptCount = 3, hasExplicitTitle = true,
    )
}
