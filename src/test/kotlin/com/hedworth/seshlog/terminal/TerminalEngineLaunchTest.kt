package com.hedworth.seshlog.terminal

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.ContentFactory
import org.jetbrains.plugins.terminal.TerminalEngine
import java.lang.reflect.Proxy
import javax.swing.JPanel

class TerminalEngineLaunchTest : BasePlatformTestCase() {
    class Builder {
        var directory: String? = null
        var title: String? = null
        var focused = false
        var deferred = true
        var fail = false
        var created = 0
        private val content = ContentFactory.getInstance().createContent(JPanel(), "new", false)
        private val view: TerminalView = Proxy.newProxyInstance(
            TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java),
        ) { _, _, _ -> null } as TerminalView
        private val tab: TerminalToolWindowTab = Proxy.newProxyInstance(
            TerminalToolWindowTab::class.java.classLoader, arrayOf(TerminalToolWindowTab::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getView" -> view
                "getContent" -> content
                else -> null
            }
        } as TerminalToolWindowTab
        private lateinit var proxy: TerminalToolWindowTabBuilder
        val api: TerminalToolWindowTabBuilder
            get() = proxy

        init {
            proxy = Proxy.newProxyInstance(
                TerminalToolWindowTabBuilder::class.java.classLoader, arrayOf(TerminalToolWindowTabBuilder::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "workingDirectory" -> { directory = args?.singleOrNull() as String?; proxy }
                    "tabName" -> { title = args?.singleOrNull() as String?; proxy }
                    "requestFocus" -> { focused = args?.singleOrNull() as Boolean; proxy }
                    "deferSessionStartUntilUiShown" -> { deferred = args?.singleOrNull() as Boolean; proxy }
                    "createTab" -> {
                        created++
                        check(!fail) { "Creation failed" }
                        tab
                    }
                    else -> proxy
                }
            } as TerminalToolWindowTabBuilder
        }
    }

    class Manager {
        var builder = Builder()
        val api: TerminalToolWindowTabsManager = Proxy.newProxyInstance(
            TerminalToolWindowTabsManager::class.java.classLoader, arrayOf(TerminalToolWindowTabsManager::class.java),
        ) { _, method, _ -> if (method.name == "createTabBuilder") builder.api else null } as TerminalToolWindowTabsManager
    }

    private var engine = TerminalEngine.CLASSIC
    private lateinit var manager: Manager
    private val adapter = ReworkedTerminal(
        terminalEngine = { engine },
        tabsManager = { manager.api },
    )

    override fun setUp() {
        super.setUp()
        manager = Manager()
        engine = TerminalEngine.CLASSIC
    }

    fun `test classic preference leaves launch to the classic adapter`() {
        assertNull(adapter.launch(project, "/project", "session"))
        assertEquals(0, manager.builder.created)
    }

    fun `test reworked preference creates and configures a new-engine tab`() {
        engine = TerminalEngine.REWORKED
        assertNotNull(adapter.launch(project, "/my project", "session"))
        with(manager.builder) {
            assertEquals("/my project", directory)
            assertEquals("session", title)
            assertTrue(focused)
            assertFalse(deferred)
            assertEquals(1, created)
        }
    }

    fun `test failure after starting new tab creation is surfaced instead of triggering fallback`() {
        engine = TerminalEngine.REWORKED
        manager.builder.fail = true
        try {
            adapter.launch(project, "/project", "session")
            fail("Expected creation failure")
        } catch (_: IllegalStateException) {
            assertEquals(1, manager.builder.created)
        }
    }
}
