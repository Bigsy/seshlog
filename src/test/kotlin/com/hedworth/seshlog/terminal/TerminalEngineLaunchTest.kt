package com.hedworth.seshlog.terminal

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TerminalEngineLaunchTest : BasePlatformTestCase() {
    enum class Engine { REWORKED, CLASSIC }
    class Options {
        fun getTerminalEngine() = engine
        companion object {
            var engine = Engine.CLASSIC
            @JvmStatic fun getInstance() = Options()
        }
    }
    class Builder {
        var directory: String? = null
        var title: String? = null
        var focused = false
        var deferred = true
        var fail = false
        var created = 0
        fun workingDirectory(value: String): Builder { directory = value; return this }
        fun tabName(value: String): Builder { title = value; return this }
        fun requestFocus(value: Boolean): Builder { focused = value; return this }
        fun deferSessionStartUntilUiShown(value: Boolean): Builder { deferred = value; return this }
        fun createTab(): Tab {
            created++
            check(!fail) { "Creation failed" }
            return Tab()
        }
    }
    class Tab {
        fun getView() = ReworkedTerminalTest.View()
        fun getContent() = com.intellij.ui.content.ContentFactory.getInstance().createContent(javax.swing.JPanel(), "new", false)
    }
    class Manager {
        fun createTabBuilder() = builder
        companion object {
            var builder = Builder()
            @JvmStatic fun getInstance(@Suppress("UNUSED_PARAMETER") project: Project) = Manager()
        }
    }
    private val adapter = ReworkedTerminal {
        when (it) {
            "org.jetbrains.plugins.terminal.TerminalOptionsProvider" -> Options::class.java
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager" -> Manager::class.java
            else -> throw ClassNotFoundException(it)
        }
    }

    override fun setUp() {
        super.setUp()
        Manager.builder = Builder()
        Options.engine = Engine.CLASSIC
    }

    fun `test classic preference leaves launch to the classic adapter`() {
        assertNull(adapter.launch(project, "/project", "session"))
        assertEquals(0, Manager.builder.created)
    }

    fun `test reworked preference creates and configures a new-engine tab`() {
        Options.engine = Engine.REWORKED
        assertNotNull(adapter.launch(project, "/my project", "session"))
        with(Manager.builder) {
            assertEquals("/my project", directory)
            assertEquals("session", title)
            assertTrue(focused)
            assertFalse(deferred)
            assertEquals(1, created)
        }
    }

    fun `test failure after starting new tab creation is surfaced instead of triggering fallback`() {
        Options.engine = Engine.REWORKED
        Manager.builder.fail = true
        try {
            adapter.launch(project, "/project", "session")
            fail("Expected creation failure")
        } catch (_: java.lang.reflect.InvocationTargetException) {
            assertEquals(1, Manager.builder.created)
        }
    }
}
