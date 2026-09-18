package com.hedworth.seshlog.terminal

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class TerminalCopyContextTest : BasePlatformTestCase() {
    class View {
        val component = JPanel()
        companion object { val DATA_KEY = DataKey.create<View>("seshlog.test.terminalView") }
    }
    class Tab(val view: View, val content: Content) {
        companion object { val KEY = com.intellij.openapi.util.Key.create<Tab>("seshlog.test.terminalTab") }
    }
    class Manager(val tabs: List<Tab>)

    fun testNativeViewIdentifiesTheInvokingSplitWithoutSwingContextOrSelectedTab() {
        val left = Tab(View(), ContentFactory.getInstance().createContent(JPanel(), "left", false))
        val right = Tab(View(), ContentFactory.getInstance().createContent(JPanel(), "right", false))
        val manager = Manager(listOf(left, right))
        val adapter = ReworkedTerminal { name ->
            if (name == "com.intellij.terminal.frontend.view.TerminalView") View::class.java
            else throw ClassNotFoundException(name)
        }
        // No CONTEXT_COMPONENT; the terminal itself supplies the exact view for its shortcut.
        fun context(view: Any?) = DataContext { id -> if (View.DATA_KEY.`is`(id)) view else null }
        assertSame(right.view, adapter.contextView(context(right.view)))
        assertSame(right.content, adapter.contentForViewIn(manager, right.view))
        assertSame(left.content, adapter.contentForViewIn(manager, left.view))
        // Unknown/detached views cannot borrow either recognised tab, even with the same cwd.
        assertNull(adapter.contentForViewIn(manager, View()))
        assertNull(adapter.contextView(context(Any())))
        assertNull(adapter.contextView(context(null)))
    }

    fun testDetachedViewResolvesThroughItsContentKeyWithoutTheGlobalManager() {
        val adapter = ReworkedTerminal { name ->
            if (name == "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab") Tab::class.java
            else throw ClassNotFoundException(name)
        }
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val view = View()
        content.putUserData(Tab.KEY, Tab(view, content))
        assertNull(content.manager) // Detached during a drag or moved into the editor.
        assertSame(view, adapter.viewOf(content))
        assertSame(content, adapter.contentForView(project, view, listOf(content)))
        assertNotNull(adapter.find(project, content))
        assertNull(adapter.contentForView(project, View(), listOf(content)))
    }

    fun testViewComponentFindsTheExactPaneWhenTheTabApiIsUnavailable() {
        val adapter = ReworkedTerminal { throw ClassNotFoundException(it) }
        val view = View()
        val inner = ContentFactory.getInstance().createContent(view.component, "agent", false)
        val outer = ContentFactory.getInstance().createContent(JPanel().also { it.add(view.component) }, "split", false)
        assertSame(inner, adapter.contentForView(project, view, listOf(outer, inner)))
        assertNull(adapter.contentForView(project, View(), listOf(outer, inner)))
    }

    fun testRememberedViewSurvivesReparentingOnOlderApisWithoutATabKey() {
        val adapter = ReworkedTerminal { throw ClassNotFoundException(it) }
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val view = View()
        adapter.handle(content, view)
        // No manager, native tab key, or shared component ancestry after the view is reparented.
        assertSame(view, adapter.viewOf(content))
        assertSame(content, adapter.contentForView(project, view, listOf(content)))
        assertNull(adapter.contentForView(project, View(), listOf(content)))
    }
}
