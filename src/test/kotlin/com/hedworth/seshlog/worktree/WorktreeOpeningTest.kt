package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class WorktreeOpeningTest {
    private class Harness {
        val root = Path.of("/synthetic/worktree")
        val checkout = Checkout(root, Path.of("/synthetic/main/.git/worktrees/topic"), Path.of("/synthetic/main/.git"), Checkout.Kind.LINKED, "topic", null)
        val selected = Session(AgentKind.CODEX, "id", "Session", root.resolve("src"), "old", null,
            Instant.EPOCH, null, false, null, null, 1, true,
            location = SessionLocation(LocationSource.CODEX_CONTEXT, 2), checkout = checkout)
        var latest: Session? = selected
        var verified: Checkout? = checkout
        var alive = true
        var block: String? = null
        var succeed = true
        var throwOpening = false
        var refreshEffect: () -> Unit = {}
        var resolveEffect: () -> Unit = {}
        var openProjects: Map<String, Path> = emptyMap()
        val workers = ArrayDeque<() -> Unit>()
        val deliveries = ArrayDeque<() -> Unit>()
        val opened = mutableListOf<Path>()
        val focused = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val controller = WorktreeOpening(
            current = { latest }, refresh = { refreshEffect() }, resolve = { path, _ ->
                assertEquals(root, path); resolveEffect(); verified
            },
            projects = { OpenProjectSnapshot(openProjects.keys, openProjects.toMap()) },
            projectSet = { openProjects.keys }, storageProblem = { block },
            focus = { focused += it }, open = {
                if (throwOpening) error("opening failure")
                opened.add(it); succeed
            }, worker = { workers += it }, ui = { deliveries += it }, active = { alive }, report = { reports += it },
        )
        fun drain() {
            while (workers.isNotEmpty() || deliveries.isNotEmpty()) {
                while (workers.isNotEmpty()) workers.removeFirst()()
                while (deliveries.isNotEmpty()) deliveries.removeFirst()()
            }
        }
    }

    @Test fun `opens checkout root and coalesces repeated clicks while validating`() {
        val h = Harness()
        h.controller.request(h.selected)
        h.controller.request(h.selected)
        assertEquals(1, h.workers.size)
        h.drain()
        assertEquals(listOf(h.root), h.opened)
        assertTrue(h.reports.isEmpty())
    }

    @Test fun `focuses existing checkout including source window instead of opening`() {
        val h = Harness()
        h.openProjects = mapOf("source-or-canonical-alias" to h.root)
        h.block = STORAGE_CONFLICT
        h.controller.request(h.selected)
        h.drain()
        assertEquals(listOf("source-or-canonical-alias"), h.focused)
        assertTrue(h.opened.isEmpty())
        assertTrue(h.reports.isEmpty())
    }

    @Test fun `rechecks projects that open between validation and effect`() {
        val h = Harness()
        h.controller.request(h.selected)
        h.workers.removeFirst()()
        h.openProjects = mapOf("just-opened" to h.root)
        h.drain()
        assertEquals(listOf("just-opened"), h.focused)
        assertTrue(h.opened.isEmpty())
    }

    @Test fun `disposal before validation or before delivery has no effects`() {
        for (beforeDelivery in listOf(false, true)) {
            val h = Harness()
            h.controller.request(h.selected)
            if (beforeDelivery) h.workers.removeFirst()()
            h.alive = false
            h.drain()
            assertTrue(h.opened.isEmpty())
            assertTrue(h.reports.isEmpty())
        }
    }

    @Test fun `missing replaced or removed session never opens main or replacement directory`() {
        for (case in 0..3) {
            val h = Harness()
            when (case) {
                0 -> h.verified = null
                1 -> h.verified = h.checkout.unavailable("deleted")
                2 -> h.verified = h.checkout.copy(gitDir = Path.of("/unrelated/.git"))
                3 -> h.latest = null
            }
            h.controller.request(h.selected)
            h.drain()
            assertTrue(h.opened.isEmpty())
            assertEquals(1, h.reports.size)
        }
    }

    @Test fun `move discovered on scan or during validation rejects stale target`() {
        for (stage in 0..2) {
            val h = Harness()
            val move = { h.latest = h.selected.copy(cwd = Path.of("/synthetic/other")) }
            if (stage == 0) h.refreshEffect = move
            if (stage == 1) h.resolveEffect = move
            h.controller.request(h.selected)
            if (stage == 2) { h.workers.removeFirst()(); move() }
            h.drain()
            assertTrue(h.opened.isEmpty())
            assertEquals(1, h.reports.size)
        }
    }

    @Test fun `uncertainty and protected data disable new opens`() {
        val h = Harness()
        val uncertain = h.selected.copy(location = h.selected.location!!.copy(uncertain = true))
        h.controller.request(uncertain)
        assertTrue(h.workers.isEmpty())
        h.block = STORAGE_CONFLICT
        h.controller.request(h.selected)
        h.drain()
        assertTrue(h.opened.isEmpty())
        assertEquals(listOf(STORAGE_CONFLICT), h.reports)
        val protected = listOf(Path.of("/synthetic"))
        assertFalse(WorktreeActionPresentation.of(h.selected, emptyList(), protected).enabled)
        assertTrue(WorktreeActionPresentation.of(h.selected, listOf(h.root), protected).enabled)
        assertFalse(WorktreeActionPresentation.of(uncertain, listOf(h.root), emptyList()).enabled)
        assertFalse(WorktreeActionPresentation.of(h.selected.copy(checkout = null), emptyList(), emptyList()).visible)
    }

    @Test fun `failure and cancellation clear pending state for retry`() {
        for (throws in listOf(false, true)) {
            val h = Harness()
            h.throwOpening = throws
            h.succeed = false
            h.controller.request(h.selected); h.drain()
            assertEquals(1, h.reports.size)
            h.throwOpening = false
            h.succeed = true
            h.controller.request(h.selected); h.drain()
            assertEquals(h.root, h.opened.last())
            assertEquals(1, h.reports.size)
        }
    }
}
