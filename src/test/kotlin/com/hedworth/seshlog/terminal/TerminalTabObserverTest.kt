package com.hedworth.seshlog.terminal

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentUI
import javax.swing.JPanel

class TerminalTabObserverTest : BasePlatformTestCase() {
    // Real ContentManager events, without native tab painting or starting shell processes.
    private fun manager(): ContentManager {
        val ui = object : ContentUI {
            private val panel = JPanel()
            override fun getComponent() = panel
            override fun setManager(manager: ContentManager) = Unit
            override fun isSingleSelection() = true
            override fun isToSelectAddedContent() = true
            override fun canBeEmptySelection() = true
            override fun canChangeSelectionTo(content: Content, implicit: Boolean) = true
            override fun getCloseActionName() = "Close"
            override fun getCloseAllButThisActionName() = "Close others"
            override fun getPreviousContentActionName() = "Previous"
            override fun getNextContentActionName() = "Next"
        }
        return ContentFactory.getInstance().createContentManager(ui, true, project).also {
            Disposer.register(testRootDisposable, it)
        }
    }

    private fun tab(manager: ContentManager, name: String): Content =
        ContentFactory.getInstance().createContent(JPanel(), name, false).also { manager.addContent(it) }

    fun `test two tabs survive third tab pane migration and return to two`() {
        val root = manager()
        val left = manager()
        val right = manager()
        val a = tab(root, "a")
        val b = tab(root, "b")
        val tabs = mutableListOf(a, b)
        val registry = TabRegistry<Content>()
        registry.register("a", a)
        registry.register("b", b)
        var active: String? = null
        val pending = ArrayDeque<() -> Unit>()
        fun flush() { while (pending.isNotEmpty()) pending.removeFirst()() }
        val observer = TerminalTabObserver({ root }, { tabs },
            { active = it?.let(registry::sessionFor) }, registry::forget, pending::addLast)
        try {
            observer.refresh()
            root.setSelectedContent(a)
            assertEquals("a", active)
            root.setSelectedContent(b)
            assertEquals("b", active)

            // Opening another pane reparents existing contents; removal does not mean closure.
            root.removeContent(a, false)
            left.addContent(a)
            root.removeContent(b, false)
            left.addContent(b)
            val c = tab(right, "c")
            tabs += c
            registry.register("c", c)
            observer.refresh() // Seshlog registers the newly launched session.
            flush()
            assertEquals(setOf("a", "b", "c"), registry.sessionIds)
            assertNull(root.selectedContent)
            observer.focusChanged(c.component)
            assertEquals("c", active)
            left.setSelectedContent(a)
            observer.focusChanged(a.component)
            assertEquals("a", active)
            left.setSelectedContent(b)
            assertEquals("b", active)
            observer.focusChanged(c.component)
            assertEquals("c", active)

            val unowned = tab(right, "unowned")
            tabs += unowned
            observer.focusChanged(unowned.component)
            assertNull(active)
            right.removeContent(unowned, true)
            tabs.remove(unowned)
            flush()
            right.setSelectedContent(c)
            observer.focusChanged(c.component)
            assertEquals("c", active)

            // Close the third tab, then merge the remaining pane back into the root.
            right.removeContent(c, true)
            tabs.remove(c)
            flush()
            assertFalse(registry.owns("c"))
            left.removeContent(a, false)
            root.addContent(a)
            left.removeContent(b, false)
            root.addContent(b)
            flush()
            assertEquals(setOf("a", "b"), registry.sessionIds)
            root.setSelectedContent(a)
            assertEquals("a", active)
            root.setSelectedContent(b)
            assertEquals("b", active)

            val unknown = tab(root, "unknown")
            tabs += unknown
            flush()
            root.setSelectedContent(unknown)
            assertNull(active)
            root.setSelectedContent(a)
            assertEquals("a", active)
        } finally {
            observer.dispose()
        }
    }

    fun `test dragged third tab keeps ownership across event turns before drop`() {
        val root = manager()
        val right = manager()
        val a = tab(root, "a")
        val b = tab(root, "b")
        val c = tab(root, "c")
        val tabs = listOf(a, b, c)
        val registry = TabRegistry<Content>()
        tabs.forEach { registry.register(it.displayName, it) }
        var active: String? = null
        val pending = ArrayDeque<() -> Unit>()
        fun flush() { while (pending.isNotEmpty()) pending.removeFirst()() }
        val observer = TerminalTabObserver({ root }, { tabs },
            { active = it?.let(registry::sessionFor) }, registry::forget, pending::addLast)
        try {
            observer.refresh()
            root.setSelectedContent(c)
            assertEquals("c", active)
            // Mouse-down starts the drag. Other UI events run before the eventual drop.
            root.removeContent(c, false)
            flush()
            observer.refresh()
            assertTrue("A detached but undisposed tab is still ours", registry.owns("c"))
            assertNull(c.manager)

            // Mouse-up drops the existing tab into a newly created right pane.
            right.addContent(c)
            observer.focusChanged(c.component)
            assertEquals("c", active)
            root.setSelectedContent(a)
            observer.focusChanged(a.component)
            assertEquals("a", active)
            observer.focusChanged(c.component)
            assertEquals("c", active)
            root.setSelectedContent(b)
            assertEquals("b", active)

            right.removeContent(c, true)
            flush()
            assertFalse("Actually closing the tab forgets it", registry.owns("c"))
            root.setSelectedContent(a)
            assertEquals("a", active)
        } finally {
            observer.dispose()
        }
    }

    fun `test ordinary third tab closure and disposed queued callbacks`() {
        val root = manager()
        val a = tab(root, "a")
        val b = tab(root, "b")
        val tabs = mutableListOf(a, b)
        val pending = ArrayDeque<() -> Unit>()
        val closed = mutableListOf<Content>()
        var active: Content? = null
        val observer = TerminalTabObserver({ root }, { tabs }, { active = it }, closed::add, pending::addLast)
        try {
            observer.refresh()
            val c = tab(root, "c")
            tabs += c
            while (pending.isNotEmpty()) pending.removeFirst()()
            root.setSelectedContent(c)
            assertSame(c, active)
            root.removeContent(c, true)
            tabs.remove(c)
            while (pending.isNotEmpty()) pending.removeFirst()()
            assertEquals(listOf(c), closed)
            root.setSelectedContent(a)
            assertSame(a, active)
            root.setSelectedContent(b)
            assertSame(b, active)
            observer.focusChanged(JPanel()) // Editor/Seshlog focus keeps the terminal selection.
            assertSame(b, active)
            root.removeContent(b, true)
            observer.dispose()
            val before = active
            while (pending.isNotEmpty()) pending.removeFirst()()
            root.setSelectedContent(a)
            assertSame(before, active)
            assertEquals(listOf(c), closed)
        } finally {
            observer.dispose()
        }
    }
}
