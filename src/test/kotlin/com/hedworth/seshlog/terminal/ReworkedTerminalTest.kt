package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import com.hedworth.seshlog.restore.RestoreCandidates
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.session.impl.TerminalSession
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.lang.reflect.Proxy
import javax.swing.JPanel

class ReworkedTerminalTest {
    private class RemoteDescriptor : EelDescriptor {
        override val name = "Remote"
        override val osFamily = EelOsFamily.Posix
    }

    private class Sender : TerminalSendTextBuilder {
        var sent: String? = null
        override fun shouldExecute() = this
        override fun send(text: String) { sent = text }
        override fun useBracketedPasteMode() = this
    }

    private class View(
        sessionDeferred: Deferred<TerminalSession> = CompletableDeferred(session()),
        shellIntegrationDeferred: Deferred<TerminalShellIntegration> = CompletableDeferred(integration(TerminalOutputStatus.TypingCommand)),
    ) {
        val title = com.intellij.terminal.TerminalTitle()
        val sender = Sender()
        val component = JPanel()
        val api: TerminalView = Proxy.newProxyInstance(
            TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDeferred" -> sessionDeferred
                "getShellIntegrationDeferred" -> shellIntegrationDeferred
                "getTitle" -> title
                "getComponent", "getPreferredFocusableComponent" -> component
                "createSendTextBuilder" -> sender
                else -> null
            }
        } as TerminalView
    }

    private companion object {
        fun session(processId: Long = 110, descriptor: EelDescriptor = LocalEelDescriptor, closed: Boolean = false): TerminalSession =
            Proxy.newProxyInstance(TerminalSession::class.java.classLoader, arrayOf(TerminalSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getProcessId" -> processId
                    "getEelDescriptor" -> descriptor
                    "isClosed" -> closed
                    else -> null
                }
            } as TerminalSession

        fun integration(status: TerminalOutputStatus): TerminalShellIntegration =
            Proxy.newProxyInstance(TerminalShellIntegration::class.java.classLoader, arrayOf(TerminalShellIntegration::class.java)) { _, method, _ ->
                if (method.name == "getOutputStatus") MutableStateFlow(status) else null
            } as TerminalShellIntegration
    }

    class RestoringManager(private val tabsRestoredDeferred: Deferred<Unit>)

    @Test fun `view without the baseline session API is unknown`() {
        val terminal = adapter.handle(null, Any())
        assertNull(terminal.shellPid())
        assertEquals(TerminalState.UNKNOWN, terminal.state())
    }

    @Test fun `restoration readiness waits for successful completion`() {
        val deferred = CompletableDeferred<Unit>()
        val manager = RestoringManager(deferred)
        assertFalse(adapter.restored(manager))
        deferred.complete(Unit)
        assertTrue(adapter.restored(manager))
        assertFalse(adapter.restored(RestoringManager(CompletableDeferred<Unit>().apply { cancel() })))
        assertFalse(adapter.restored(Any()))
    }

    private val adapter = ReworkedTerminal()
    private fun handle(view: View = View()) = adapter.handle(null, view.api)

    @Test fun `local reworked session exposes shell pid and prompt state`() {
        val terminal = handle()
        assertEquals(110L, terminal.shellPid())
        assertEquals(TerminalState.IDLE, terminal.state())
        for (status in listOf(TerminalOutputStatus.ExecutingCommand, TerminalOutputStatus.WaitingForPrompt)) {
            assertEquals(TerminalState.BUSY, handle(View(shellIntegrationDeferred = CompletableDeferred(integration(status)))).state())
        }
    }

    @Test fun `starting failed and closed sessions are not treated as idle`() {
        val failed = CompletableDeferred<TerminalSession>().apply { completeExceptionally(IllegalStateException("failed")) }
        val pending = CompletableDeferred<TerminalSession>()
        for (session in listOf(pending, failed, CompletableDeferred(session(closed = true)))) {
            val terminal = handle(View(sessionDeferred = session))
            assertNull(terminal.shellPid())
            assertEquals(TerminalState.UNKNOWN, terminal.state())
        }
        assertEquals(TerminalState.UNKNOWN, handle(View(shellIntegrationDeferred = CompletableDeferred<TerminalShellIntegration>())).state())
        assertEquals(TerminalState.UNKNOWN, adapter.handle(null, Any()).state())
    }

    @Test fun `remote and invalid pids never enter the local process tree`() {
        for (session in listOf(session(descriptor = RemoteDescriptor()), session(processId = -1), session(processId = 0))) {
            assertNull(handle(View(sessionDeferred = CompletableDeferred(session))).shellPid())
        }
    }

    @Test fun `commands use the execute builder and preserve shell quoting`() {
        val view = View()
        val command = "cd '/some project' && claude --resume 'session-id'"
        handle(view).execute(command)
        assertEquals(command, view.sender.sent)
    }

    @Test fun `rename updates the persistent view title`() {
        val view = View()
        handle(view).rename("Agent session")
        view.title.change { applicationTitle = "shell prompt" }
        assertEquals("Agent session", view.title.buildTitle())
    }

    @Test fun `mixed terminal shells adopt and remember only their own agents`() {
        val tree = object : ProcessTree {
            override fun isAlive(pid: Long) = true
            override fun parentPid(pid: Long) = mapOf(111L to 110L, 211L to 210L, 311L to 310L)[pid]
        }
        val tabs = mapOf("reworked" to handle().shellPid(), "classic" to 210L)
        fun session(id: String, pid: Long) = Session(
            kind = AgentKind.CLAUDE_CODE, id = id, title = id, cwd = Path.of("/project"), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = null,
            isLive = true, livePid = pid, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
        val sessions = listOf(session("new-agent", 111), session("old-agent", 211), session("other-ide", 311))
        val registry = TabRegistry<String>()
        registry.sync(sessions, tabs, tree) { it }
        assertEquals("reworked", registry.tabFor("new-agent"))
        assertEquals("classic", registry.tabFor("old-agent"))
        assertFalse(registry.owns("other-ide"))
        assertEquals(listOf("new-agent", "old-agent"), RestoreCandidates.snapshot(sessions, emptySet(), tabs.values.filterNotNull().toSet(), tree))
    }
}
