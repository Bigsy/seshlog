package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class TerminalReconcilerTest {
    private class Tab(val id: String) {
        override fun toString() = id
    }

    private class Process(val id: String)

    private val tab = Tab("tab")
    private val process = Process("agent")

    private fun session(id: String, title: String = "Agent title") = Session(
        AgentKind.CODEX, id, title, Path.of("/synthetic"), null, null, Instant.EPOCH, null,
        true, 11L, null, 1, true,
    )

    private fun input(
        sessions: List<Session>,
        ownership: Map<String, Tab> = emptyMap(),
        ownershipAtInspection: Map<Tab, String?> = emptyMap(),
        discoveries: List<TabProcessDiscovery<Tab, Process>> = emptyList(),
        running: Set<String> = emptySet(),
        observedBefore: Map<String, Process> = emptyMap(),
        observedNow: Map<String, Process> = emptyMap(),
        exited: Set<String> = emptySet(),
        tabTitles: Map<Tab, String?> = emptyMap(),
    ) = TerminalReconcileInput(
        sessions, ownership, ownershipAtInspection, discoveries, running,
        observedBefore, observedNow, exited, tabTitles, { "Local ${it.id}" },
    )

    @Test
    fun `adopts discovery observes process and reports local retitle`() {
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s")),
            ownershipAtInspection = mapOf(tab to null),
            discoveries = listOf(TabProcessDiscovery(tab, "s", process)),
            tabTitles = mapOf(tab to "Agent title"),
        ))
        assertEquals(mapOf("s" to tab), result.adopt)
        assertEquals(mapOf("s" to process), result.observe)
        assertEquals(setOf("s"), result.running)
        assertEquals(listOf(TabRegistry.Retitle(tab, "Local s")), result.retitle)
    }

    @Test
    fun `stale discovery cannot evict an ownership change`() {
        val current = mapOf("other" to tab)
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s"), session("other")),
            ownership = current,
            ownershipAtInspection = mapOf(tab to "old"),
            discoveries = listOf(TabProcessDiscovery(tab, "s", process)),
        ))
        assertTrue(result.adopt.isEmpty())
        assertTrue(result.observe.isEmpty())
    }

    @Test
    fun `only the exact observed process exit releases a tab`() {
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s")),
            ownership = mapOf("s" to tab),
            observedBefore = mapOf("s" to process),
            observedNow = mapOf("s" to process),
            exited = setOf("s"),
        ))
        assertEquals(setOf("s"), result.release)
        assertEquals(mapOf(tab to "s"), result.ended)

        val replacement = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s")),
            ownership = mapOf("s" to tab),
            observedBefore = mapOf("s" to process),
            observedNow = mapOf("s" to Process("replacement")),
            exited = setOf("s"),
        ))
        assertTrue(replacement.release.isEmpty())
    }

    @Test
    fun `descriptor evidence adopts providers without a live flag`() {
        val codex = session("codex").copy(isLive = false, livePid = null)
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(codex),
            ownershipAtInspection = mapOf(tab to null),
            discoveries = listOf(TabProcessDiscovery(tab, "codex", process)),
        ))
        assertEquals(mapOf("codex" to tab), result.adopt)
        assertEquals(setOf("codex"), result.running)
    }

    @Test
    fun `ambiguous identities and tabs are left unowned`() {
        val sameTab = TerminalReconciler.reconcile(input(
            sessions = listOf(session("one"), session("two")),
            ownershipAtInspection = mapOf(tab to null),
            discoveries = listOf(
                TabProcessDiscovery(tab, "one", process),
                TabProcessDiscovery(tab, "two", process),
            ),
        ))
        assertTrue(sameTab.adopt.isEmpty())

        val other = Tab("other")
        val twoTabs = TerminalReconciler.reconcile(input(
            sessions = listOf(session("one")),
            ownershipAtInspection = mapOf(tab to null, other to null),
            discoveries = listOf(
                TabProcessDiscovery(tab, "one", process),
                TabProcessDiscovery(other, "one", process),
            ),
        ))
        assertTrue(twoTabs.adopt.isEmpty())
    }

    @Test
    fun `missing current observation preserves ownership after explicit evidence reset`() {
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s")),
            ownership = mapOf("s" to tab),
            ownershipAtInspection = mapOf(tab to "s"),
            observedBefore = mapOf("s" to process),
            observedNow = emptyMap(),
            exited = setOf("s"),
        ))
        assertTrue(result.release.isEmpty())
    }

    @Test
    fun `new incoming process suppresses an old exit and becomes observed`() {
        val replacement = Process("replacement")
        val result = TerminalReconciler.reconcile(input(
            sessions = listOf(session("s")),
            ownership = mapOf("s" to tab),
            ownershipAtInspection = mapOf(tab to "s"),
            running = setOf("s"),
            observedBefore = mapOf("s" to process),
            observedNow = mapOf("s" to process),
            exited = setOf("s"),
        ).copy(runningProcesses = mapOf("s" to replacement)))
        assertTrue(result.release.isEmpty())
        assertEquals(mapOf("s" to replacement), result.observe)
        assertEquals(setOf("s"), result.running)
    }

    @Test
    fun `raw running evidence is rejected after a tab generation change`() {
        val result = TerminalReconciler.reconcile(
            TerminalReconcileInput(
                sessions = listOf(session("s")),
                ownership = mapOf("s" to tab),
                ownershipAtInspection = mapOf(tab to "s"),
                discoveries = emptyList(),
                running = setOf("s"),
                observedBefore = emptyMap(),
                observedNow = emptyMap(),
                exited = emptySet(),
                tabTitles = emptyMap(),
                displayTitle = { it.title },
                generationsAtInspection = mapOf(tab to 1L),
                generationsNow = mapOf(tab to 2L),
            ),
        )
        assertFalse("stale running evidence must not survive reuse", result.running.contains("s"))
    }

    @Test
    fun `generation change rejects delayed adoption and running evidence`() {
        val result = TerminalReconciler.reconcile(
            TerminalReconcileInput(
                sessions = listOf(session("s")),
                ownership = emptyMap(),
                ownershipAtInspection = mapOf(tab to null),
                discoveries = listOf(TabProcessDiscovery(tab, "s", process)),
                running = emptySet(),
                observedBefore = emptyMap(),
                observedNow = emptyMap(),
                exited = emptySet(),
                tabTitles = emptyMap(),
                displayTitle = { it.title },
                generationsAtInspection = mapOf(tab to 1L),
                generationsNow = mapOf(tab to 2L),
            ),
        )
        assertTrue(result.adopt.isEmpty())
    }
}
