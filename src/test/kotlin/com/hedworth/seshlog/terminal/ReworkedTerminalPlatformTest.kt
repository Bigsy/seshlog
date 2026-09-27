package com.hedworth.seshlog.terminal

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.session.impl.TerminalSession
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
import java.lang.reflect.Proxy
import javax.swing.JPanel

/** Exercises the bundled 2026.2 reworked-terminal API and its plugin classloader. */
class ReworkedTerminalPlatformTest : BasePlatformTestCase() {
    fun `test starting terminal ownership does not create an unopened Terminal tool window`() {
        val manager = ToolWindowManager.getInstance(project)
        var window = manager.getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        var registered = false
        var contentCreations = 0
        if (window == null) {
            window = manager.registerToolWindow(RegisterToolWindowTask(
                id = TerminalToolWindowFactory.TOOL_WINDOW_ID,
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = false,
                contentFactory = object : ToolWindowFactory {
                    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) { contentCreations++ }
                },
            ))
            registered = true
        }
        val actualWindow = requireNotNull(window)
        try {
            val getIfCreated = actualWindow.javaClass.methods.firstOrNull { it.name == "getContentManagerIfCreated" }
                ?: error("Current platform must expose getContentManagerIfCreated")
            val existing = getIfCreated.invoke(window)
            OwnedTerminalTabs.getInstance(project).start()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertSame("Ownership polling must not replace/create content", existing, getIfCreated.invoke(window))
            assertEquals("Ownership polling must not invoke the content factory", 0, contentCreations)
        } finally {
            if (registered) manager.unregisterToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        }
    }

    fun `test direct reworked tab API resolves the exact content and state`() {
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val session = Proxy.newProxyInstance(
            TerminalSession::class.java.classLoader, arrayOf(TerminalSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getProcessId" -> 110L
                "getEelDescriptor" -> com.intellij.platform.eel.provider.LocalEelDescriptor
                "isClosed" -> false
                else -> null
            }
        } as TerminalSession
        val integration = Proxy.newProxyInstance(
            TerminalShellIntegration::class.java.classLoader, arrayOf(TerminalShellIntegration::class.java),
        ) { _, method, _ ->
            if (method.name == "getOutputStatus") MutableStateFlow(TerminalOutputStatus.TypingCommand) else null
        } as TerminalShellIntegration
        val view = Proxy.newProxyInstance(
            TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDeferred" -> CompletableDeferred(session)
                "getShellIntegrationDeferred" -> CompletableDeferred(integration)
                "getComponent", "getPreferredFocusableComponent" -> content.component
                else -> null
            }
        } as TerminalView
        val tab = Proxy.newProxyInstance(
            TerminalToolWindowTab::class.java.classLoader, arrayOf(TerminalToolWindowTab::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getView" -> view
                "getContent" -> content
                else -> null
            }
        } as TerminalToolWindowTab
        content.putUserData(TerminalToolWindowTab.KEY, tab)
        val manager = Proxy.newProxyInstance(
            TerminalToolWindowTabsManager::class.java.classLoader, arrayOf(TerminalToolWindowTabsManager::class.java),
        ) { _, method, _ -> if (method.name == "getTabs") listOf(tab) else null }

        val adapter = ReworkedTerminal()
        val context = DataContext { id -> if (TerminalView.DATA_KEY.`is`(id)) view else null }
        assertSame(view, adapter.contextView(context))
        assertSame(content, adapter.contentForViewIn(manager, view))
        assertSame(view, adapter.viewOf(content))
        assertSame(content, adapter.findIn(manager, content)?.content)
        assertEquals(110L, adapter.findIn(manager, content)?.shellPid())
        assertEquals(TerminalState.IDLE, adapter.findIn(manager, content)?.state())
        assertNull(adapter.contentForViewIn(manager, Any()))
        assertNull(adapter.contextView(DataContext { null }))
    }
}
