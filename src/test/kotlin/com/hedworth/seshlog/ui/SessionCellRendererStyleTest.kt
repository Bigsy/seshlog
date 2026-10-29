package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SearchMatchSource
import com.hedworth.seshlog.index.SearchPrimaryMatch
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.nio.file.Path
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode

class SessionCellRendererStyleTest : BasePlatformTestCase() {
    private val session = Session(AgentKind.CODEX, "synthetic-colours", "Retry retry_budget <b>literal</b>",
        Path.of("/synthetic/harbor-retry"), "feature/retry-backoff", null, Instant.EPOCH, null,
        false, null, null, 3, true)

    private fun render(renderer: SessionCellRenderer, tree: Tree, item: Any = session, selected: Boolean = false) =
        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(item), selected, false, item is Session, 0, false)

    fun `test main list colours agent branch and path and bolds query text in title location and snippet`() {
        val renderer = SessionCellRenderer().apply { query = "RETRY budget"; showFolder = true }
        val tree = Tree()
        val hit = SearchHit(session, 1.0, false, "A RETRY budget for retries.", primary = SearchPrimaryMatch(SearchMatchSource.USER))
        renderer.hits = mapOf(hit.key to hit)
        render(renderer, tree)
        assertEquals(session.title, renderer.titleLabel.text)
        assertEquals(listOf("Retry", "retry", "budget"), bold(renderer.titleLabel))
        assertEquals(listOf("retry", "retry", "retry"), bold(renderer.folderLabel))
        assertEquals(listOf("RETRY", "budget"), bold(renderer.matchLabel))
        assertEquals(SessionTextColors.AGENT, fragments(renderer.detailsLabel).first { it.first == "Codex" }.second.fgColor)
        assertEquals(SessionTextColors.BRANCH, fragments(renderer.folderLabel).first { it.first == "feature/" }.second.fgColor)
        assertEquals(SessionTextColors.SECONDARY, fragments(renderer.folderLabel).first { it.first.startsWith("/synthetic/") }.second.fgColor)
        assertEquals(tree.foreground, fragments(renderer.matchLabel).first { it.first == "A " }.second.fgColor)
        assertTrue(renderer.accessibleContext.accessibleName.contains(session.title))
        assertTrue(renderer.accessibleContext.accessibleDescription.contains("Matched You"))
        assertTrue(renderer.toolTipText.contains("&lt;b&gt;literal&lt;/b&gt;"))
        assertFalse(renderer.folderLabel.text.contains("worktree harbor-retry"))
    }

    fun `test selection keeps query emphasis with native contrast and no text movement`() {
        val renderer = SessionCellRenderer().apply { query = "retry"; showFolder = true }
        val tree = Tree()
        val hit = SearchHit(session, 1.0, false, "retry budget")
        renderer.hits = mapOf(hit.key to hit)
        render(renderer, tree)
        val components = listOf(renderer.titleLabel, renderer.folderLabel, renderer.detailsLabel, renderer.matchLabel)
        val text = components.map { it.text }
        val size = renderer.preferredSize
        val insets = renderer.insets
        val bold = components.map(::bold)
        render(renderer, tree, selected = true)
        assertEquals(text, components.map { it.text })
        assertEquals(bold, components.map(::bold))
        assertEquals(size, renderer.preferredSize)
        assertEquals(insets, renderer.insets)
        components.flatMap(::fragments).forEach { (_, attributes) ->
            assertEquals(UIUtil.getTreeSelectionForeground(tree.hasFocus()), attributes.fgColor)
        }
    }

    fun `test phrases overlaps unicode and markup stay literal and renderer reuse clears emphasis`() {
        val renderer = SessionCellRenderer().apply { query = "\"database is locked\" ana İSTANBUL <b>"; showFolder = true }
        val tree = Tree()
        val title = "İstanbul banana BANANA <b>literal</b> database is locked"
        render(renderer, tree, session.copy(title = title))
        assertEquals(title, renderer.titleLabel.text)
        assertEquals(listOf("İstanbul", "anana", "ANANA", "<b>", "database is locked"), bold(renderer.titleLabel))
        renderer.query = ""
        render(renderer, tree, session.copy(title = "Another row"))
        assertTrue(bold(renderer.titleLabel).isEmpty())
        assertTrue(bold(renderer.folderLabel).isEmpty())
        assertEquals("Another row", renderer.titleLabel.text)
        assertFalse(renderer.matchLabel.isVisible)
        render(renderer, tree, ProjectGroup(session.cwd, listOf(session)))
        assertEquals("harbor-retry", renderer.titleLabel.text)
        assertFalse(renderer.folderLabel.isVisible)
        assertFalse(renderer.detailsLabel.parent.isVisible)
        renderer.query = "a".repeat(4097)
        render(renderer, tree)
        assertTrue(bold(renderer.titleLabel).isEmpty())
    }

    fun `test grouped rows also emphasise the matching branch and excerpt`() {
        val renderer = SessionCellRenderer().apply { query = "retry" }
        val tree = Tree()
        val hit = SearchHit(session, 1.0, false, "retry budget")
        renderer.hits = mapOf(hit.key to hit)
        render(renderer, tree)
        assertFalse(renderer.folderLabel.isVisible)
        assertEquals(listOf("retry"), bold(renderer.detailsLabel))
        assertEquals(listOf("retry"), bold(renderer.matchLabel))
        assertEquals(SessionTextColors.BRANCH, fragments(renderer.detailsLabel).first { it.first == "feature/" }.second.fgColor)
    }

    fun `test fragment styles retain bold folder headings and active terminal underline`() {
        val renderer = SessionCellRenderer()
        val tree = Tree()
        render(renderer, tree, ProjectGroup(session.cwd, listOf(session)))
        assertTrue(fragments(renderer.titleLabel).all { it.second.fontStyle and Font.BOLD != 0 })
        renderer.activeSessionId = session.id
        renderer.query = "retry"
        render(renderer, tree)
        assertTrue(fragments(renderer.titleLabel).all { it.second.fontStyle and Font.BOLD != 0 })
        render(renderer, tree, selected = true)
        assertTrue(fragments(renderer.titleLabel).all { it.second.style and SimpleTextAttributes.STYLE_UNDERLINE != 0 })
    }

    private fun fragments(component: SimpleColoredComponent): List<Pair<String, SimpleTextAttributes>> = buildList {
        val iterator = component.iterator()
        while (iterator.hasNext()) add(iterator.next() to iterator.textAttributes)
    }
    private fun bold(component: SimpleColoredComponent) = fragments(component)
        .filter { it.second.fontStyle and Font.BOLD != 0 }.map { it.first }
}
