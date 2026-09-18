package com.hedworth.seshlog.terminal

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import javax.swing.JPanel

/** Run on 2024.1, 2026.1 and 2026.2: exercise the installed API and its plugin/module classloader. */
class ReworkedTerminalPlatformTest : BasePlatformTestCase() {
    private fun api(name: String) = ReworkedTerminal.loadApiClass(name)
    private fun proxy(name: String, answer: (String) -> Any?): Any {
        val type = api(name)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answer(method.name) }
    }

    fun `test actual reworked tab API or safe absence on older IDEs`() {
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val adapter = ReworkedTerminal()
        if (ApplicationInfo.getInstance().build.baselineVersion < 261) {
            assertNull(adapter.find(project, content))
            assertNull(adapter.contextView(com.intellij.openapi.actionSystem.DataContext { null }))
            return
        }
        val local = api("com.intellij.platform.eel.provider.LocalEelDescriptor").getField("INSTANCE").get(null)
        val session = proxy("org.jetbrains.plugins.terminal.session.impl.TerminalSession") {
            when (it) { "isClosed" -> false; "getProcessId" -> 110L; "getEelDescriptor" -> local; else -> null }
        }
        val status = api("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus\$TypingCommand")
            .getField("INSTANCE").get(null)
        val integration = proxy("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration") {
            if (it == "getOutputStatus") MutableStateFlow(status) else null
        }
        val legacy = ApplicationInfo.getInstance().build.baselineVersion < 262
        if (legacy) {
            // Verify the real older implementation supports the fallback, not just our proxy.
            val impl = api("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
            impl.getMethod("getSessionState")
            impl.getMethod("getStartupOptionsDeferred")
            api("org.jetbrains.plugins.terminal.session.TerminalStartupOptions").getMethod("getPid")
        }
        val running = api("com.intellij.terminal.frontend.view.TerminalViewSessionState\$Running").getField("INSTANCE").get(null)
        val options = proxy("org.jetbrains.plugins.terminal.session.TerminalStartupOptions") { if (it == "getPid") 110L else null }
        val view = proxy("com.intellij.terminal.frontend.view.TerminalView") {
            when (it) {
                "getSessionDeferred" -> CompletableDeferred(session)
                "getSessionState" -> MutableStateFlow(running)
                "getStartupOptionsDeferred" -> CompletableDeferred(options)
                "getShellIntegrationDeferred" -> CompletableDeferred(integration)
                else -> null
            }
        }
        val tabType = api("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab")
        val tab = proxy(tabType.name) {
            when (it) { "getView" -> view; "getContent" -> content; else -> null }
        }
        tabType.fields.firstOrNull { it.name == "Companion" }?.get(null)?.let { companion ->
            @Suppress("UNCHECKED_CAST")
            val tabKey = ReworkedTerminal.call(companion, "getKEY") as com.intellij.openapi.util.Key<Any>
            content.putUserData(tabKey, tab)
            assertSame(view, adapter.viewOf(content))
        }
        val manager = proxy("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager") {
            if (it == "getTabs") listOf(tab) else null
        }
        // An action can carry the terminal view without any Swing context component.
        val viewType = api("com.intellij.terminal.frontend.view.TerminalView")
        val key = ReworkedTerminal.call(viewType.getField("Companion").get(null), "getDATA_KEY")
            as com.intellij.openapi.actionSystem.DataKey<*>
        val context = com.intellij.openapi.actionSystem.DataContext { id -> if (key.`is`(id)) view else null }
        assertSame(view, adapter.contextView(context))
        assertSame(content, adapter.contentForViewIn(manager, view))
        assertNull(adapter.contentForViewIn(manager, Any()))
        assertNull(adapter.contextView(com.intellij.openapi.actionSystem.DataContext { null }))
        val terminal = requireNotNull(adapter.findIn(manager, content) { true })
        assertSame(content, terminal.content)
        assertEquals(110L, terminal.shellPid())
        assertEquals(TerminalState.IDLE, terminal.state())
        // Older APIs need the cached view; newer ones can also read the platform's tab key.
        assertNull(content.manager)
        assertSame(view, adapter.viewOf(content))
        assertSame(content, adapter.contentForView(project, view, listOf(content)))
        assertNotNull(adapter.find(project, content))

        val owned = OwnedTerminalTabs.getInstance(project)
        val tracked = com.hedworth.seshlog.model.Session(com.hedworth.seshlog.model.AgentKind.CODEX,
            "tracked", "tracked", java.nio.file.Path.of("/project"), null, null, java.time.Instant.EPOCH,
            null, false, null, null, 1, true)
        owned.track(tracked, terminal)
        // Exercise the real action path when getTabs() cannot see a moved terminal.
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("test", null, context)
        val target = TerminalTabs.actionTarget(project, event)
        assertTrue(target.isTerminal)
        assertSame(content, target.content)
        assertEquals(tracked.id, owned.resolveSession(requireNotNull(target.content)))

        api("com.intellij.terminal.frontend.toolwindow.impl.TerminalToolWindowTabsManagerImpl")
            .getDeclaredField("tabsRestoredDeferred")
        val builder = api("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder")
        for (name in listOf("workingDirectory", "tabName")) builder.getMethod(name, String::class.java)
        for (name in listOf("requestFocus", "deferSessionStartUntilUiShown")) builder.getMethod(name, Boolean::class.javaPrimitiveType)
        builder.getMethod("createTab")
    }
}
