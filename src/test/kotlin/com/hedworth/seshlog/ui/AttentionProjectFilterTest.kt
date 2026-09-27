package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.index.DateBounds
import com.hedworth.seshlog.settings.AgentFilterMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class AttentionProjectFilterTest {
    private fun session(id: String, kind: AgentKind = AgentKind.CLAUDE_CODE, cwd: String = "/work", at: Long = 0) =
        Session(kind, id, id, Paths.get(cwd), null, null, Instant.ofEpochSecond(at), null, true, null, null, 1, true,
            activity = Activity.WAITING)

    @Test fun `filter applies project scope hidden sessions and agent selection`() {
        val sessions = listOf(
            session("inside"),
            session("outside", cwd = "/elsewhere"),
            session("codex", AgentKind.CODEX),
            session("hidden"),
        )
        val visible = AttentionProjectFilter.apply(
            sessions = sessions,
            roots = listOf(Paths.get("/work")),
            showAllProjects = false,
            includeWorktrees = false,
            agentMode = AgentFilterMode.Only(AgentKind.CLAUDE_CODE),
            hiddenIds = setOf("hidden"),
            minPrompts = 1,
            canonical = { it.normalize() },
            repository = { null },
        )
        assertEquals(listOf("inside"), visible.map { it.id })
    }

    @Test fun `date bounds and show hidden are part of the same pure scope`() {
        val sessions = listOf(session("hidden", at = 1), session("old", at = 1), session("new", at = 3))
        val visible = AttentionProjectFilter.apply(
            sessions = sessions,
            roots = listOf(Paths.get("/work")),
            showAllProjects = false,
            includeWorktrees = false,
            agentMode = AgentFilterMode.All,
            hiddenIds = setOf("hidden"),
            showHidden = true,
            minPrompts = 1,
            dateBounds = DateBounds(Instant.ofEpochSecond(2), Instant.ofEpochSecond(4)),
            canonical = { it.normalize() },
            repository = { null },
        )
        assertEquals(listOf("new"), visible.map { it.id })
    }

    @Test fun `all projects keeps the same non project filters`() {
        val sessions = listOf(session("inside"), session("outside", cwd = "/elsewhere"))
        val visible = AttentionProjectFilter.apply(
            sessions = sessions,
            roots = listOf(Paths.get("/work")),
            showAllProjects = true,
            includeWorktrees = false,
            agentMode = AgentFilterMode.All,
            hiddenIds = emptySet(),
            minPrompts = 1,
            canonical = { it.normalize() }, repository = { null },
        )
        assertEquals(listOf("inside", "outside"), visible.map { it.id })
    }
}
