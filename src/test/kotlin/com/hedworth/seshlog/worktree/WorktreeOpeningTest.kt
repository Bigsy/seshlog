package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class WorktreeOpeningTest {
    private class Harness(forkSession: Boolean = false, launchVerb: String = "fork",
                          pending: MutableSet<Path> = mutableSetOf(), id: String = "id") {
        val root = Path.of("/synthetic/worktree")
        val checkout = Checkout(root, Path.of("/synthetic/main/.git/worktrees/topic"), Path.of("/synthetic/main/.git"), Checkout.Kind.LINKED, "topic", null)
        val selected = Session(AgentKind.CODEX, id, "Session", root.resolve("src"), "old", null,
            Instant.EPOCH, null, false, null, null, 1, true,
            location = SessionLocation(LocationSource.CODEX_CONTEXT, 2), checkout = checkout)
        var latest: Session? = selected
        var verified: Checkout? = checkout
        var alive = true
        var block: String? = null
        var succeed = true
        var throwOpening = false
        var throwFork = false
        var directoryProblem: String? = null
        var allowed = true
        var uiAllowed = true
        var openEffect: () -> Unit = {}
        var refreshEffect: () -> Unit = {}
        var resolveEffect: () -> Unit = {}
        var openProjects: Map<String, Path> = emptyMap()
        val workers = ArrayDeque<() -> Unit>()
        val deliveries = ArrayDeque<() -> Unit>()
        val opened = mutableListOf<Path>()
        val focused = mutableListOf<String>()
        val forked = mutableListOf<Pair<String, Session>>()
        val reports = mutableListOf<String>()
        val controller = WorktreeOpening(
            current = { latest }, refresh = { refreshEffect() }, resolve = { path, _ ->
                assertEquals(root, path); resolveEffect(); verified
            },
            projects = { OpenProjectSnapshot(openProjects.keys, openProjects.toMap()) },
            projectSet = { openProjects.keys }, storageProblem = { block },
            focus = { focused += it }, open = {
                if (throwOpening) error("opening failure")
                opened.add(it)
                if (succeed) {
                    openProjects = openProjects + ("destination" to it)
                    openEffect()
                    "destination"
                } else null
            }, worker = { workers += it }, ui = { deliveries += it }, active = { alive }, report = { reports += it },
            launch = if (forkSession) { destination, source ->
                if (throwFork) error("terminal failure")
                forked += destination to source
            } else null,
            launchProblem = { _, _ -> directoryProblem },
            launchVerb = launchVerb,
            allowed = { allowed },
            uiAllowed = { uiAllowed },
            pending = pending,
        )
        fun openWindow() {
            controller.request(selected)
            workers.removeFirst()()
            deliveries.removeFirst()()
        }
        fun drain() {
            while (workers.isNotEmpty() || deliveries.isNotEmpty()) {
                while (workers.isNotEmpty()) workers.removeFirst()()
                while (deliveries.isNotEmpty()) deliveries.removeFirst()()
            }
        }
    }

    @Test fun `opens checkout root and coalesces repeated clicks while validating`() {
        val h = Harness()
        assertTrue(h.controller.request(h.selected))
        assertFalse(h.controller.request(h.selected))
        assertEquals(1, h.reports.size)
        h.reports.clear()
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

    @Test fun `fork opens root then launches once in the destination with the recorded subdirectory`() {
        val h = Harness(forkSession = true)
        h.openWindow()
        assertEquals(listOf(h.root), h.opened)
        assertTrue(h.forked.isEmpty()) // Must revalidate after opening, before launching.
        assertFalse(h.controller.request(h.selected))
        assertEquals(1, h.reports.size)
        h.reports.clear()
        assertEquals(1, h.workers.size) // Still coalesced while the fork is pending.
        h.drain()
        assertEquals(listOf("destination" to h.selected), h.forked)
        assertEquals(h.root.resolve("src"), h.forked.single().second.cwd)
        assertTrue(h.reports.isEmpty())
    }

    @Test fun `fork focuses an existing project and launches there without opening another`() {
        val h = Harness(forkSession = true)
        h.openProjects = mapOf("existing" to h.root)
        h.controller.request(h.selected)
        h.drain()
        assertEquals(listOf("existing"), h.focused)
        assertTrue(h.opened.isEmpty())
        assertEquals(listOf("existing" to h.selected), h.forked)
    }

    @Test fun `cancelled or failed open never forks and can be retried`() {
        for (throws in listOf(false, true)) {
            val h = Harness(forkSession = true)
            h.succeed = false
            h.throwOpening = throws
            h.controller.request(h.selected); h.drain()
            assertTrue(h.forked.isEmpty())
            assertEquals(1, h.reports.size)
            h.succeed = true
            h.throwOpening = false
            h.controller.request(h.selected); h.drain()
            assertEquals(1, h.forked.size)
        }
    }

    @Test fun `fork rejects session moves checkout replacement and missing directories during opening`() {
        for (case in 0..3) {
            val h = Harness(forkSession = true)
            h.openEffect = {
                when (case) {
                    0 -> h.latest = h.selected.copy(cwd = Path.of("/synthetic/other"))
                    1 -> h.verified = h.checkout.copy(directoryKey = "replacement")
                    2 -> h.directoryProblem = "Recorded subdirectory was removed"
                    3 -> h.latest = null
                }
            }
            h.controller.request(h.selected); h.drain()
            assertEquals(1, h.opened.size)
            assertTrue(h.forked.isEmpty())
            assertEquals(1, h.reports.size)
        }
    }

    @Test fun `fork rejects stale delivery and closed or changed destination windows`() {
        for (case in 0..4) {
            val h = Harness(forkSession = true)
            h.openWindow()
            if (case != 4) h.workers.removeFirst()()
            when (case) {
                0 -> h.alive = false
                1 -> h.openProjects = emptyMap()
                2 -> h.latest = h.selected.copy(location = h.selected.location!!.copy(uncertain = true))
                3 -> h.latest = null
                4 -> h.openProjects = mapOf("destination" to Path.of("/different/project"))
            }
            h.drain()
            assertTrue(h.forked.isEmpty())
            if (case == 0) assertTrue(h.reports.isEmpty()) else assertEquals(1, h.reports.size)
        }
    }

    @Test fun `fork failure clears pending for retry in the already opened window`() {
        val h = Harness(forkSession = true)
        h.throwFork = true
        h.controller.request(h.selected); h.drain()
        assertTrue(h.forked.isEmpty())
        assertTrue(h.reports.single().contains("terminal failure"))
        h.throwFork = false
        h.controller.request(h.selected); h.drain()
        assertEquals(1, h.opened.size)
        assertEquals(1, h.forked.size)
        assertEquals(listOf("destination"), h.focused)
    }

    @Test fun `missing subdirectory disables fork but keeps root navigation available`() {
        val h = Harness(forkSession = true)
        val missing = h.selected.copy(recordedDirectoryAvailable = false)
        assertTrue(WorktreeActionPresentation.of(missing, emptyList(), emptyList()).enabled)
        val fork = WorktreeActionPresentation.of(missing, emptyList(), emptyList(), forkSession = true)
        assertTrue(fork.visible)
        assertFalse(fork.enabled)
        assertEquals("Open Worktree and Fork Session", fork.text)
        h.directoryProblem = "Recorded subdirectory was removed"
        h.controller.request(missing); h.drain()
        assertTrue(h.opened.isEmpty())
        assertTrue(h.forked.isEmpty())
        assertEquals(listOf(h.directoryProblem), h.reports)
    }

    @Test fun `resume after opening uses the same destination and rechecks running and hidden guards`() {
        for (case in 0..3) {
            val h = Harness(forkSession = true, launchVerb = "resume")
            h.openWindow()
            if (case == 1) h.allowed = false // Became live while project opening was in progress.
            if (case == 2) h.uiAllowed = false // Hidden or acquired an owned terminal.
            if (case == 3) { h.workers.removeFirst()(); h.uiAllowed = false }
            h.drain()
            assertEquals(if (case == 0) 1 else 0, h.forked.size)
            assertEquals(if (case == 0) 0 else 1, h.reports.size)
        }
    }

    @Test fun `hidden session does not open another project after queued validation`() {
        val h = Harness()
        h.controller.request(h.selected)
        h.workers.removeFirst()()
        h.uiAllowed = false
        h.drain()
        assertTrue(h.opened.isEmpty())
        assertEquals(1, h.reports.size)
    }

    @Test fun `different resumes sharing a checkout explicitly reject busy request and permit retry`() {
        val pending = mutableSetOf<Path>()
        val first = Harness(true, "resume", pending, "first")
        val second = Harness(true, "resume", pending, "second")
        assertTrue(first.controller.request(first.selected))
        assertFalse(second.controller.request(second.selected))
        assertTrue(second.workers.isEmpty())
        assertTrue(second.reports.single().contains("please try again"))
        first.drain()
        second.openProjects = first.openProjects
        assertTrue(second.controller.request(second.selected))
        second.drain()
        assertEquals("second", second.forked.single().second.id)
        assertTrue(second.opened.isEmpty())
    }

    @Test fun `resume rejects failed refresh before opening and after trust flow`() {
        for (afterOpen in listOf(false, true)) {
            val h = Harness(true, "resume")
            if (afterOpen) h.openWindow()
            h.refreshEffect = { error("refresh failed or timed out") }
            if (!afterOpen) h.controller.request(h.selected)
            h.drain()
            assertTrue(h.forked.isEmpty())
            assertEquals(1, h.reports.size)
            h.refreshEffect = {}
            assertTrue(h.controller.request(h.selected))
            h.drain()
            assertEquals(1, h.forked.size)
        }
    }

    @Test fun `fresh location record does not prevent opening or launching the same checkout`() {
        val h = Harness(true, "resume")
        h.refreshEffect = { h.latest = h.selected.copy(location = h.selected.location!!.copy(record = 999,
            timestamp = Instant.ofEpochSecond(100))) }
        h.controller.request(h.selected); h.drain()
        assertEquals(1, h.forked.size)
        assertTrue(h.reports.isEmpty())
    }
}
