package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class SessionListViewModelTest {
    @Test
    fun `continued sessions show only current entry in list and attention surfaces`() {
        val original = Session(AgentKind.CLAUDE_CODE, "original", "Same title", Paths.get("/project"), "old",
            null, Instant.EPOCH, null, false, null, null, 2, true, continuationId = "current")
        val current = original.copy(id = "current", gitBranch = "main", continuationId = null)
        val fork = current.copy(id = "fork", forkedFromId = original.id)
        val all = listOf(original, current, fork)
        val filters = SessionListFilters(true, false, false, 1, AgentFilterMode.All)
        val result = SessionListViewModel.build(all, emptyList(), ResolvedPaths.EMPTY, filters)
        assertEquals(listOf(current, fork), result.visible)
        assertEquals(result.visible, result.scoped)
        assertEquals(result.visible, AttentionProjectFilter.apply(all, emptyList(), true, false,
            AgentFilterMode.All, emptySet(), minPrompts = 1, canonical = { it }, repository = { null }))
    }

    @Test
    fun `pure list model applies hidden project agent and date filters`() {
        val session = Session(AgentKind.CLAUDE_CODE, "visible", "Visible", Paths.get("/project/app"), null,
            null, Instant.parse("2026-09-20T00:00:00Z"), null, false, null, null, 2, true)
        val filters = SessionListFilters(false, false, false, 1, AgentFilterMode.All)
        val result = SessionListViewModel.visible(
            listOf(session), listOf(Paths.get("/project")), ResolvedPaths.resolve(listOf(Paths.get("/project"), session.cwd)), filters,
        )
        assertEquals(listOf(session), result)
        assertEquals(SessionListViewModel.EmptyReason.Hidden,
            SessionListViewModel.emptyReason(listOf(session), emptyList(), emptyList(), filters.copy(showAllProjects = true)))
    }
    @Test
    fun `auto agent selection stays consistent with the menu before date filtering`() {
        val old = Session(AgentKind.CODEX, "old", "Old", Paths.get("/project"), null,
            null, Instant.parse("2026-09-01T12:00:00Z"), null, false, null, null, 2, true)
        val recent = old.copy(id = "recent", kind = AgentKind.CLAUDE_CODE, lastActivityAt = Instant.parse("2026-09-20T12:00:00Z"))
        val all = listOf(old, old.copy(id = "old-2"), recent)
        val date = com.hedworth.seshlog.index.SessionDateFilter(com.hedworth.seshlog.index.DatePeriod.CUSTOM,
            java.time.LocalDate.parse("2026-09-20"), java.time.LocalDate.parse("2026-09-20"))
        val filters = SessionListFilters(true, false, false, 1, AgentFilterMode.Auto, date)
        val visible = SessionListViewModel.visible(all, emptyList(), ResolvedPaths.EMPTY, filters)
        assertEquals(emptyList<Session>(), visible)
        assertEquals(visible, AttentionProjectFilter.apply(all, emptyList(), true, false,
            AgentFilterMode.Auto, emptySet(), minPrompts = 1, dateBounds = date.bounds(),
            canonical = { it }, repository = { null }))
    }

    @Test
    fun `view model returns stable pinned project groups`() {
        val old = Session(AgentKind.CODEX, "pinned", "Pinned", Paths.get("/project"), null,
            null, Instant.EPOCH, null, false, null, null, 2, true)
        val recent = old.copy(id = "recent", lastActivityAt = Instant.EPOCH.plusSeconds(10))
        val filters = SessionListFilters(true, false, false, 1, AgentFilterMode.All)
        val result = SessionListViewModel.build(listOf(recent, old), emptyList(), ResolvedPaths.EMPTY, filters,
            pinned = { it.id == "pinned" })
        assertEquals(listOf("pinned", "recent"), result.groups.single().sessions.map { it.id })
        assertEquals(result, SessionListViewModel.build(listOf(recent, old), emptyList(), ResolvedPaths.EMPTY, filters,
            pinned = { it.id == "pinned" }))
    }

}
