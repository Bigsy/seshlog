package com.hedworth.seshlog.terminal

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.lang.reflect.Proxy
import javax.swing.JPanel

class TerminalCopyContextTest : BasePlatformTestCase() {
    class View {
        val component = JPanel()
        val api: TerminalView = Proxy.newProxyInstance(
            TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getComponent", "getPreferredFocusableComponent" -> component
                else -> null
            }
        } as TerminalView
    }

    class Tab(val view: View, val content: Content) {
        val api: TerminalToolWindowTab = Proxy.newProxyInstance(
            TerminalToolWindowTab::class.java.classLoader, arrayOf(TerminalToolWindowTab::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getView" -> view.api
                "getContent" -> content
                else -> null
            }
        } as TerminalToolWindowTab
    }

    class Manager(tabs: List<Tab>) {
        val api: TerminalToolWindowTabsManager = Proxy.newProxyInstance(
            TerminalToolWindowTabsManager::class.java.classLoader, arrayOf(TerminalToolWindowTabsManager::class.java),
        ) { _, method, _ ->
            if (method.name == "getTabs") tabs.map { it.api } else null
        } as TerminalToolWindowTabsManager
    }

    fun testNativeViewIdentifiesTheInvokingSplitWithoutSwingContextOrSelectedTab() {
        val left = Tab(View(), ContentFactory.getInstance().createContent(JPanel(), "left", false))
        val right = Tab(View(), ContentFactory.getInstance().createContent(JPanel(), "right", false))
        val manager = Manager(listOf(left, right))
        val adapter = ReworkedTerminal(tabsManager = { manager.api })
        // No CONTEXT_COMPONENT; the terminal itself supplies the exact view for its shortcut.
        fun context(view: Any?) = DataContext { id -> if (TerminalView.DATA_KEY.`is`(id)) view else null }
        assertSame(right.view.api, adapter.contextView(context(right.view.api)))
        assertSame(right.content, adapter.contentForViewIn(manager.api, right.view.api))
        assertSame(left.content, adapter.contentForViewIn(manager.api, left.view.api))
        // Unknown/detached views cannot borrow either recognised tab, even with the same cwd.
        assertNull(adapter.contentForViewIn(manager.api, View().api))
        assertNull(adapter.contextView(context(null)))
    }

    fun testDetachedViewResolvesThroughItsContentKeyWithoutTheGlobalManager() {
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val view = View()
        val tab = Tab(view, content)
        content.putUserData(TerminalToolWindowTab.KEY, tab.api)
        val adapter = ReworkedTerminal(tabsManager = { error("manager should not be queried") })
        assertNull(content.manager) // Detached during a drag or moved into the editor.
        assertSame(view.api, adapter.viewOf(content))
        assertSame(content, adapter.contentForView(project, view.api, listOf(content)))
        assertNotNull(adapter.find(project, content))
        assertNull(adapter.contentForView(project, View().api, listOf(content)))
    }

    fun testViewComponentFindsTheExactPaneWhenTheTabApiIsUnavailable() {
        val adapter = ReworkedTerminal(tabsManager = { error("manager should not be queried") })
        val view = View()
        val inner = ContentFactory.getInstance().createContent(view.component, "agent", false)
        val outer = ContentFactory.getInstance().createContent(JPanel().also { it.add(view.component) }, "split", false)
        assertSame(inner, adapter.contentForView(project, view.api, listOf(outer, inner)))
        assertNull(adapter.contentForView(project, View().api, listOf(outer, inner)))
    }

    fun testRememberedViewSurvivesReparentingOnOlderApisWithoutATabKey() {
        val adapter = ReworkedTerminal(tabsManager = { error("manager should not be queried") })
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val view = View()
        adapter.handle(content, view.api)
        // No manager, native tab key, or shared component ancestry after the view is reparented.
        assertSame(view.api, adapter.viewOf(content))
        assertSame(content, adapter.contentForView(project, view.api, listOf(content)))
        assertNull(adapter.contentForView(project, View().api, listOf(content)))
    }
}
