package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.terminal.AgentAvailability
import com.hedworth.seshlog.ui.actions.TerminalNewSessionAction
import com.hedworth.seshlog.ui.actions.TerminalNewSessionGroup
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/** The real actions and settings with a stand-in terminal view, PATH lookup and launcher. */
class TerminalNewSessionActionTest : BasePlatformTestCase() {
    private val found = AgentAvailability(finder = { Path.of("/usr/local/bin", it) })
    private val missing = AgentAvailability(finder = { null })
    private val launched = mutableListOf<Pair<AgentKind, Path>>()
    private lateinit var previous: Map<AgentKind, Boolean>

    override fun setUp() {
        super.setUp()
        val settings = SeshlogSettings.getInstance()
        previous = AgentKind.entries.associateWith(settings::inTerminalMenu)
        AgentKind.entries.forEach { settings.setInTerminalMenu(it, true) }
    }

    override fun tearDown() {
        try {
            previous.forEach(SeshlogSettings.getInstance()::setInTerminalMenu)
        } finally {
            super.tearDown()
        }
    }

    private fun action(kind: AgentKind, availability: AgentAvailability = found) =
        object : TerminalNewSessionAction(kind, availability, { _, k, cwd -> launched += k to cwd }) {}

    private fun view(directory: Path): TerminalView =
        Proxy.newProxyInstance(TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java)) { _, method, _ ->
            when (method.name) {
                "getWorkingDirectoryFlow" -> MutableStateFlow(directory)
                "createSendTextBuilder", "sendText" -> error("Must open a new tab, not type into the clicked terminal")
                else -> null
            }
        } as TerminalView

    private fun event(action: AnAction, view: TerminalView?): AnActionEvent {
        val context = DataContext { id ->
            when {
                CommonDataKeys.PROJECT.`is`(id) -> project
                TerminalView.DATA_KEY.`is`(id) -> view
                else -> null
            }
        }
        return AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), "test", ActionUiKind.POPUP, null)
    }

    private fun updated(action: AnAction, view: TerminalView?) = event(action, view).also(action::update).presentation

    fun `test the menu entry follows New Tab in the reworked terminal menu with one action per agent`() {
        val manager = ActionManager.getInstance()
        val menu = manager.getAction("Terminal.ReworkedTerminalContextMenu") as ActionGroup
        val ids = menu.getChildren(null).map(manager::getId)
        assertEquals(ids.indexOf("Terminal.NewTab") + 1, ids.indexOf("Seshlog.TerminalNewSession"))
        val group = manager.getAction("Seshlog.TerminalNewSession") as ActionGroup
        val children = group.getChildren(null).toList()
        assertEquals(
            listOf("Seshlog.TerminalNewClaudeSession", "Seshlog.TerminalNewCodexSession", "Seshlog.TerminalNewOpenCodeSession", "Seshlog.TerminalNewPiSession"),
            children.map(manager::getId),
        )
        assertEquals(
            AgentKind.entries.map { "New ${it.displayName} Session" },
            children.map { it.templatePresentation.text },
        )
    }

    fun `test an agent is offered only in a terminal with its setting on and its executable found`() {
        val terminal = view(Path.of(project.basePath!!))
        assertTrue(updated(action(AgentKind.CLAUDE_CODE), terminal).isEnabledAndVisible)
        assertFalse(updated(action(AgentKind.CLAUDE_CODE), null).isVisible)
        assertFalse(updated(action(AgentKind.CLAUDE_CODE, missing), terminal).isVisible)
        SeshlogSettings.getInstance().setInTerminalMenu(AgentKind.CLAUDE_CODE, false)
        assertFalse(updated(action(AgentKind.CLAUDE_CODE), terminal).isVisible)
        assertTrue(updated(action(AgentKind.CODEX), terminal).isVisible)
    }

    fun `test the group is hidden, inline or a submenu by how many agents are offered`() {
        val terminal = view(Path.of(project.basePath!!))
        fun group(vararg actions: AnAction) = TerminalNewSessionGroup().apply { actions.forEach(::add) }

        val none = updated(group(action(AgentKind.CLAUDE_CODE, missing), action(AgentKind.CODEX, missing)), terminal)
        assertFalse(none.isVisible)

        val one = updated(group(action(AgentKind.CLAUDE_CODE), action(AgentKind.CODEX, missing)), terminal)
        assertTrue(one.isVisible)
        assertFalse(one.isPopupGroup)

        val two = updated(group(action(AgentKind.CLAUDE_CODE), action(AgentKind.CODEX, missing), action(AgentKind.PI)), terminal)
        assertTrue(two.isVisible)
        assertTrue(two.isPopupGroup)

        SeshlogSettings.getInstance().setInTerminalMenu(AgentKind.PI, false)
        assertFalse(updated(group(action(AgentKind.CLAUDE_CODE), action(AgentKind.PI)), terminal).isPopupGroup)
    }

    fun `test the session opens in the clicked terminal's directory, else the project root`() {
        val directory = Files.createTempDirectory("seshlog-terminal-cwd")
        try {
            val claude = action(AgentKind.CLAUDE_CODE)
            claude.actionPerformed(event(claude, view(directory)))
            val codex = action(AgentKind.CODEX)
            codex.actionPerformed(event(codex, view(directory.resolve("removed"))))
            assertEquals(
                listOf(AgentKind.CLAUDE_CODE to directory, AgentKind.CODEX to Path.of(project.basePath!!)),
                launched,
            )
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    fun `test the launch directory falls back to the project root and then to nothing`() {
        val terminal = Path.of("/work/repo")
        assertEquals(terminal, TerminalNewSessionAction.launchDirectory(terminal, "/project") { true })
        assertEquals(Path.of("/project"), TerminalNewSessionAction.launchDirectory(terminal, "/project") { false })
        assertEquals(Path.of("/project"), TerminalNewSessionAction.launchDirectory(null, "/project") { true })
        assertNull(TerminalNewSessionAction.launchDirectory(null, null) { true })
    }
}
