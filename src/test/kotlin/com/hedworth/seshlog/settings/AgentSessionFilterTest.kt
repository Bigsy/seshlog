package com.hedworth.seshlog.settings

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class AgentSessionFilterTest {
    private fun session(id: String, kind: AgentKind) = Session(
        kind = kind,
        id = id,
        title = id,
        cwd = Paths.get("/tmp/project"),
        gitBranch = null,
        startedAt = null,
        lastActivityAt = Instant.EPOCH,
        transcriptPath = Paths.get("/tmp/$id.jsonl"),
        isLive = false,
        livePid = null,
        promptTitle = id,
        promptCount = 1,
        hasExplicitTitle = false,
    )

    @Test
    fun `auto shows the provider with the most sessions`() {
        val sessions = listOf(
            session("claude", AgentKind.CLAUDE_CODE),
            session("codex-1", AgentKind.CODEX),
            session("codex-2", AgentKind.CODEX),
        )
        assertEquals(listOf("codex-1", "codex-2"), AgentSessionFilter.apply(sessions, AgentFilterMode.AUTO).map { it.id })
        assertEquals(listOf("claude"), AgentSessionFilter.apply(sessions, AgentFilterMode.CLAUDE_CODE).map { it.id })
        assertEquals(sessions, AgentSessionFilter.apply(sessions, AgentFilterMode.ALL))
    }

    @Test
    fun `auto tie is deterministic`() {
        val sessions = listOf(session("codex", AgentKind.CODEX), session("claude", AgentKind.CLAUDE_CODE))
        assertEquals(listOf("claude"), AgentSessionFilter.apply(sessions, AgentFilterMode.AUTO).map { it.id })
    }

    @Test
    fun `forced mode round trips through project state`() {
        val original = AgentFilterState().apply { mode = AgentFilterMode.CODEX }
        val restored = AgentFilterState().apply {
            loadState(AgentFilterState.State().also { it.mode = original.state.mode })
        }
        assertEquals(AgentFilterMode.CODEX, restored.mode)
    }
}
