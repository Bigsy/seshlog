package com.hedworth.seshlog.terminal

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import javax.swing.JPanel

/** Run on both 2024.1 and 2026.2: exercise the installed API and its plugin/module classloader. */
class ReworkedTerminalPlatformTest : BasePlatformTestCase() {
    private fun api(name: String) = ReworkedTerminal.loadApiClass(name)
    private fun proxy(name: String, answer: (String) -> Any?): Any {
        val type = api(name)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answer(method.name) }
    }

    fun `test actual reworked tab API or safe absence on older IDEs`() {
        val content = ContentFactory.getInstance().createContent(JPanel(), "agent", false)
        val adapter = ReworkedTerminal()
        if (ApplicationInfo.getInstance().build.baselineVersion < 262) {
            assertNull(adapter.find(project, content))
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
        val view = proxy("com.intellij.terminal.frontend.view.TerminalView") {
            when (it) {
                "getSessionDeferred" -> CompletableDeferred(session)
                "getShellIntegrationDeferred" -> CompletableDeferred(integration)
                else -> null
            }
        }
        val tabType = api("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab")
        val tab = proxy(tabType.name) {
            when (it) { "getView" -> view; "getContent" -> content; else -> null }
        }
        val manager = proxy("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager") {
            if (it == "getTabs") listOf(tab) else null
        }
        val terminal = requireNotNull(adapter.findIn(manager, content))
        assertSame(content, terminal.content)
        assertEquals(110L, terminal.shellPid())
        assertEquals(TerminalState.IDLE, terminal.state())

        val builder = api("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder")
        for (name in listOf("workingDirectory", "tabName")) builder.getMethod(name, String::class.java)
        for (name in listOf("requestFocus", "deferSessionStartUntilUiShown")) builder.getMethod(name, Boolean::class.javaPrimitiveType)
        builder.getMethod("createTab")
    }
}
