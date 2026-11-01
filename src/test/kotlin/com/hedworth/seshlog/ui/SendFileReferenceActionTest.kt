package com.hedworth.seshlog.ui

import com.hedworth.seshlog.ui.actions.SendFileReferenceAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import java.lang.reflect.Proxy
import javax.swing.JPanel

/** Real action, editor, terminal selection and adapter, with no agent session or local process. */
class SendFileReferenceActionTest : BasePlatformTestCase() {
    private class Input(var accepts: Boolean = true, var throws: Boolean = false) : TerminalSendTextBuilder {
        var draft = "existing question "
        var attempts = 0
        var bracketed = false
        override fun shouldExecute(): TerminalSendTextBuilder = error("Must not send Enter")
        override fun useBracketedPasteMode() = this
        override fun requireBracketedPasteMode() = apply { bracketed = true }
        override fun send(text: String) = error("Must use guarded paste")
        override fun trySend(text: String): Boolean {
            check(bracketed)
            attempts++
            if (throws) error("Synthetic uncertain paste")
            if (accepts) draft += text
            return accepts
        }
    }

    private fun tab(window: ToolWindow, name: String, input: Input): Content {
        val content = ContentFactory.getInstance().createContent(JPanel(), name, false)
        val view = Proxy.newProxyInstance(TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java)) { _, method, _ ->
            when (method.name) {
                "getComponent", "getPreferredFocusableComponent" -> content.component
                "createSendTextBuilder" -> input
                "getSessionDeferred", "getShellIntegrationDeferred", "getTitle" -> error("Paste must not inspect the running program")
                else -> null
            }
        } as TerminalView
        val tab = Proxy.newProxyInstance(TerminalToolWindowTab::class.java.classLoader, arrayOf(TerminalToolWindowTab::class.java)) { _, method, _ ->
            when (method.name) {
                "getContent" -> content
                "getView" -> view
                else -> null
            }
        } as TerminalToolWindowTab
        content.putUserData(TerminalToolWindowTab.KEY, tab)
        window.contentManager.addContent(content)
        return content
    }

    private fun withTerminal(body: (ToolWindow) -> Unit) {
        val manager = ToolWindowManager.getInstance(project)
        val existing = manager.getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val window = existing ?: manager.registerToolWindow(RegisterToolWindowTask(
            id = TerminalToolWindowFactory.TOOL_WINDOW_ID, anchor = ToolWindowAnchor.BOTTOM, canCloseContent = false,
        ))
        try { body(window) } finally {
            window.contentManager.removeAllContents(true)
            if (existing == null) manager.unregisterToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        }
    }

    private fun event(): AnActionEvent {
        val file = myFixture.addFileToProject("src/example.txt", "one\ntwo\nthree\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.selectionModel.setSelection(0, 8)
        return AnActionEvent.createFromDataContext("test", null, DataContext { id ->
            when {
                CommonDataKeys.PROJECT.`is`(id) -> project
                CommonDataKeys.EDITOR.`is`(id) -> myFixture.editor
                else -> null
            }
        })
    }

    fun testSelectedUnregisteredTerminalReceivesOnlyFileAndLinesWithoutEnter() = withTerminal { window ->
        val other = Input()
        val selected = Input()
        tab(window, "other terminal", other)
        val content = tab(window, "plain shell or unknown tool", selected)
        window.contentManager.setSelectedContent(content)
        SendFileReferenceAction().actionPerformed(event())
        assertEquals(1, selected.attempts)
        assertTrue(selected.bracketed)
        assertEquals("existing question \"${myFixture.file.virtualFile.path}\":1-2 ", selected.draft)
        assertEquals(0, other.attempts)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(content, window.contentManager.selectedContent)
        assertEquals(1, selected.attempts)
    }

    fun testUnavailablePasteDoesNotRetryOrSubstituteAnotherTerminal() = withTerminal { window ->
        val other = Input()
        val selected = Input(accepts = false)
        tab(window, "other terminal", other)
        window.contentManager.setSelectedContent(tab(window, "unsupported paste", selected))
        SendFileReferenceAction().actionPerformed(event())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, selected.attempts)
        assertEquals("existing question ", selected.draft)
        assertEquals(0, other.attempts)
    }

    fun testUncertainPasteDoesNotRetry() = withTerminal { window ->
        val selected = Input(throws = true)
        window.contentManager.setSelectedContent(tab(window, "uncertain paste", selected))
        SendFileReferenceAction().actionPerformed(event())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, selected.attempts)
    }

    fun testDelayedFocusCannotReselectATabAfterTheUserSwitches() = withTerminal { window ->
        val first = Input()
        val second = Input()
        val a = tab(window, "first", first)
        val b = tab(window, "second", second)
        window.contentManager.setSelectedContent(a)
        SendFileReferenceAction().actionPerformed(event())
        assertEquals(1, first.attempts)
        window.contentManager.setSelectedContent(b)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(b, window.contentManager.selectedContent)
        assertEquals(0, second.attempts)
    }

    fun testClosingThePastedTabCannotRedirectOrRepeatThePaste() = withTerminal { window ->
        val first = Input()
        val second = Input()
        val a = tab(window, "first", first)
        val b = tab(window, "second", second)
        window.contentManager.setSelectedContent(a)
        SendFileReferenceAction().actionPerformed(event())
        window.contentManager.removeContent(a, true)
        window.contentManager.setSelectedContent(b)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(b, window.contentManager.selectedContent)
        assertEquals(1, first.attempts)
        assertEquals(0, second.attempts)
    }
}
