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
        assertEquals(listOf("codex-1", "codex-2"), AgentSessionFilter.apply(sessions, AgentFilterMode.Auto).map { it.id })
        assertEquals(listOf("claude"), AgentSessionFilter.apply(sessions, AgentFilterMode.Only(AgentKind.CLAUDE_CODE)).map { it.id })
        assertEquals(sessions, AgentSessionFilter.apply(sessions, AgentFilterMode.All))
    }

    @Test
    fun `auto tie is deterministic`() {
        val sessions = listOf(session("codex", AgentKind.CODEX), session("claude", AgentKind.CLAUDE_CODE))
        assertEquals(listOf("claude"), AgentSessionFilter.apply(sessions, AgentFilterMode.Auto).map { it.id })
    }

    @Test
    fun `forced mode round trips through project state`() {
        val original = AgentFilterState().apply { mode = AgentFilterMode.Only(AgentKind.CODEX) }
        assertEquals("CODEX", original.state.mode) // the persisted form predates the sealed class
        val restored = AgentFilterState().apply {
            loadState(AgentFilterState.State().also { it.mode = original.state.mode })
        }
        assertEquals(AgentFilterMode.Only(AgentKind.CODEX), restored.mode)
    }

    @Test
    fun `persisted form round trips for every mode and unknown values fall back to Auto`() {
        for (mode in AgentFilterMode.entries) assertEquals(mode, AgentFilterMode.parse(mode.serialize()))
        assertEquals("AUTO", AgentFilterMode.Auto.serialize())
        assertEquals("ALL", AgentFilterMode.All.serialize())
        assertEquals(AgentFilterMode.Auto, AgentFilterMode.parse("GONE_AGENT"))
        assertEquals(listOf(AgentFilterMode.Auto, AgentFilterMode.All), AgentFilterMode.entries.take(2))
        assertEquals(AgentKind.entries.map { AgentFilterMode.Only(it) }, AgentFilterMode.entries.drop(2))
    }
}
