package com.hedworth.seshlog.terminal

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class SelectedTerminalTest : BasePlatformTestCase() {
    fun testOnlyTheHighlightedTabCanReceiveAnEditorReference() {
        val factory = ContentFactory.getInstance()
        val manager = factory.createContentManager(false, project).also { Disposer.register(testRootDisposable, it) }
        val first = factory.createContent(JPanel(), "first agent", false)
        val second = factory.createContent(JPanel(), "highlighted shell", false)
        manager.addContent(first)
        manager.addContent(second)
        manager.setSelectedContent(second)
        assertSame(second, TerminalTabs.selectedIn(second, listOf(first, second)))
        // A missing terminal adapter cannot substitute another tab for the highlighted one.
        assertNull(TerminalTabs.selectedIn(second, listOf(first)))
        manager.setSelectedContent(first)
        assertSame(first, TerminalTabs.selectedIn(first, listOf(first, second)))
        manager.removeContent(first, false)
        assertNull(TerminalTabs.selectedIn(first, listOf(first, second)))
        Disposer.dispose(first)
    }

    fun testAmbiguousSplitPanesAndDetachedViewsAreUnavailable() {
        val factory = ContentFactory.getInstance()
        val parent = JPanel()
        val selected = factory.createContent(parent, "split", false)
        val first = factory.createContent(JPanel(), "left", false)
        val second = factory.createContent(JPanel(), "right", false)
        val left = factory.createContentManager(false, project).also { Disposer.register(testRootDisposable, it) }
        val right = factory.createContentManager(false, project).also { Disposer.register(testRootDisposable, it) }
        left.addContent(first)
        right.addContent(second)
        left.setSelectedContent(first)
        right.setSelectedContent(second)
        parent.add(first.component)
        parent.add(second.component)
        assertSame(first, TerminalTabs.selectedIn(selected, listOf(first)))
        assertNull(TerminalTabs.selectedIn(selected, listOf(first, second)))
        right.removeContent(second, false)
        assertSame(first, TerminalTabs.selectedIn(selected, listOf(first, second)))
        Disposer.dispose(second)
        Disposer.dispose(selected)
    }
}
