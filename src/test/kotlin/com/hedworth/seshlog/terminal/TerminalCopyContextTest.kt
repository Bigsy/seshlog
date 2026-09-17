package com.hedworth.seshlog.terminal

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class TerminalCopyContextTest : BasePlatformTestCase() {
    class View {
        companion object { val DATA_KEY = DataKey.create<View>("seshlog.test.terminalView") }
    }
    class Tab(val view: View, val content: Content)
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
}
