package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class ResumeCommandTest {

    private fun session(id: String) = Session(
        kind = AgentKind.CLAUDE_CODE, id = id, title = "t", cwd = Paths.get("/tmp/a b"), gitBranch = null,
        startedAt = null, lastActivityAt = Instant.EPOCH, transcriptPath = Paths.get("/tmp/x.jsonl"),
        isLive = false, livePid = null, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
    )

    @Test
    fun `plain arguments are left unquoted`() {
        assertEquals("claude", ShellQuote.quote("claude"))
        assertEquals("/usr/local/bin/claude", ShellQuote.quote("/usr/local/bin/claude"))
        assertEquals("''", ShellQuote.quote(""))
    }

    @Test
    fun `spaces and quotes are single-quote escaped`() {
        assertEquals("'/Users/me/My Projects'", ShellQuote.quote("/Users/me/My Projects"))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertEquals("'a\$b'", ShellQuote.quote("a\$b"))
    }

    @Test
    fun `resume command uses configured executable and quotes the id`() {
        val provider = ClaudeCodeSessionProvider({ Paths.get("/nowhere") }, { "/opt/claude bin/claude" })
        assertEquals(
            "'/opt/claude bin/claude' --resume 0953a942-db19-4fbc-9548-daa0a5c466b7",
            provider.resumeCommand(session("0953a942-db19-4fbc-9548-daa0a5c466b7")),
        )
        assertEquals("claude --resume 'x;rm -rf'", ClaudeCodeSessionProvider({ Paths.get("/n") }, { "claude" }).resumeCommand(session("x;rm -rf")))
    }

    @Test
    fun `fork command is the resume command plus --fork-session`() {
        val provider = ClaudeCodeSessionProvider({ Paths.get("/nowhere") }, { "/opt/claude bin/claude" })
        assertEquals(
            "'/opt/claude bin/claude' --resume 0953a942-db19-4fbc-9548-daa0a5c466b7 --fork-session",
            provider.forkCommand(session("0953a942-db19-4fbc-9548-daa0a5c466b7")),
        )
        assertEquals("claude --resume 'x;rm -rf' --fork-session", ClaudeCodeSessionProvider({ Paths.get("/n") }, { "claude" }).forkCommand(session("x;rm -rf")))
        assertEquals("Fix the flaky test (fork)", TerminalTabs.forkTitle("Fix the flaky test"))
    }
}
