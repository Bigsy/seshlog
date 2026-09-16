package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import com.hedworth.seshlog.restore.RestoreCandidates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class ReworkedTerminalTest {
    class Local
    class Remote
    class TypingCommand
    class ExecutingCommand
    class WaitingForPrompt
    class SessionApi(val processId: Long = 110, val eelDescriptor: Any = Local(), private val closed: Boolean = false) {
        fun isClosed() = closed
    }
    class Integration(status: Any) { val outputStatus = MutableStateFlow(status) }
    interface Sender {
        fun shouldExecute(): Sender
        fun send(command: String)
    }
    class View(
        val sessionDeferred: Deferred<Any> = CompletableDeferred(SessionApi()),
        val shellIntegrationDeferred: Deferred<Any> = CompletableDeferred(Integration(TypingCommand())),
    ) {
        val title = com.intellij.terminal.TerminalTitle()
        var executed = false
        var sent: String? = null
        // A non-public implementation tests reflection through a public interface.
        fun createSendTextBuilder(): Sender = object : Sender {
            override fun shouldExecute(): Sender { executed = true; return this }
            override fun send(command: String) { sent = command }
        }
    }
    private val adapter = ReworkedTerminal { name ->
        if (name == "com.intellij.platform.eel.provider.LocalEelDescriptor") Local::class.java
        else throw ClassNotFoundException(name)
    }
    private fun handle(view: View = View()) = adapter.handle(null, view)

    @Test fun `local reworked session exposes shell pid and prompt state`() {
        val terminal = handle()
        assertEquals(110L, terminal.shellPid())
        assertEquals(TerminalState.IDLE, terminal.state())
        for (status in listOf(ExecutingCommand(), WaitingForPrompt())) {
            assertEquals(TerminalState.BUSY, handle(View(shellIntegrationDeferred = CompletableDeferred(Integration(status)))).state())
        }
    }

    @Test fun `starting failed and closed sessions are not treated as idle`() {
        val failed = CompletableDeferred<Any>().apply { completeExceptionally(IllegalStateException("failed")) }
        val pending = CompletableDeferred<Any>()
        for (session in listOf(pending, failed, CompletableDeferred(SessionApi(closed = true)))) {
            val terminal = handle(View(sessionDeferred = session))
            assertNull(terminal.shellPid())
            assertEquals(TerminalState.UNKNOWN, terminal.state())
        }
        assertEquals(TerminalState.UNKNOWN, handle(View(shellIntegrationDeferred = pending)).state())
        assertEquals(TerminalState.UNKNOWN, adapter.handle(null, Any()).state())
    }

    @Test fun `remote and invalid pids never enter the local process tree`() {
        for (session in listOf(SessionApi(eelDescriptor = Remote()), SessionApi(processId = -1), SessionApi(processId = 0))) {
            assertNull(handle(View(sessionDeferred = CompletableDeferred(session))).shellPid())
        }
    }

    @Test fun `commands use the execute builder and preserve shell quoting`() {
        val view = View()
        val command = "cd '/some project' && claude --resume 'session-id'"
        handle(view).execute(command)
        assertTrue(view.executed)
        assertEquals(command, view.sent)
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
