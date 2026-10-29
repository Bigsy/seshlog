package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

class WorktreeDeletionTest {
    private class Harness(var checkout: Checkout = Checkout(Path.of("/synthetic/topic"),
        Path.of("/synthetic/main/.git/worktrees/topic"), Path.of("/synthetic/main/.git"), Checkout.Kind.LINKED, "topic", null),
        pending: MutableSet<Path> = mutableSetOf()) {
        val session = Session(AgentKind.CODEX, "inside", "Session", checkout.root.resolve("src"), null, null,
            Instant.EPOCH, null, false, null, null, 1, true,
            location = SessionLocation(LocationSource.CODEX_CONTEXT), checkout = checkout)
        var inside = listOf(session)
        var verified: Checkout? = checkout
        var associated = emptySet<String>()
        var openProjects = emptyMap<String, Path>()
        var changeProjects = false
        var protected = emptyList<Path>()
        var alive = true
        var refreshEffect: () -> Unit = {}
        var gitEffect: () -> Unit = {}
        val gitResults = ArrayDeque<GitResult>()
        val gitCalls = mutableListOf<Pair<Path, List<String>>>()
        val refreshed = mutableListOf<Set<AgentKind>>()
        val hidden = mutableListOf<Set<String>>()
        var rescans = 0
        val reports = mutableListOf<String>()
        val done = mutableListOf<String>()
        val workers = ArrayDeque<() -> Unit>()
        val deliveries = ArrayDeque<() -> Unit>()
        val controller = WorktreeDeletion(
            refresh = { refreshed += it; refreshEffect() }, sessions = { inside }, resolve = { path, _ ->
                assertEquals(checkout.root, path); verified
            },
            projects = { OpenProjectSnapshot(openProjects.keys, openProjects) },
            projectSet = { if (changeProjects) openProjects.keys + "late" else openProjects.keys },
            associated = { associated }, protectedRoots = { protected },
            git = { directory, args ->
                gitCalls += directory to args; gitEffect(); gitResults.removeFirstOrNull() ?: GitResult(0, "")
            },
            hide = { hidden += it }, rescan = { rescans++ },
            worker = { workers += it }, ui = { deliveries += it }, active = { alive },
            report = { reports += it }, done = { done += it }, pending = pending,
        )
        fun drain() {
            while (workers.isNotEmpty() || deliveries.isNotEmpty()) {
                while (workers.isNotEmpty()) workers.removeFirst()()
                while (deliveries.isNotEmpty()) deliveries.removeFirst()()
            }
        }
        fun delete(hide: Boolean = true, branch: Boolean = false): Boolean =
            controller.request(checkout, WorktreeDeletionOptions(hide, branch)).also { drain() }
    }

    @Test fun `removes the verified worktree through its repository and hides its sessions`() {
        val pending = mutableSetOf<Path>()
        val h = Harness(pending = pending)
        assertTrue(h.delete())
        assertEquals(listOf(setOf(AgentKind.CODEX), setOf(AgentKind.CODEX)), h.refreshed)
        assertEquals(listOf(h.checkout.commonDir to listOf("--git-dir=/synthetic/main/.git", "worktree", "remove", "/synthetic/topic")), h.gitCalls)
        assertEquals(listOf(setOf("inside")), h.hidden)
        assertEquals(1, h.rescans)
        assertEquals(listOf("Deleted worktree topic"), h.done)
        assertTrue(h.reports.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun `keeping sessions visible and deleting a merged branch are both optional`() {
        val h = Harness()
        assertTrue(h.delete(hide = false, branch = true))
        assertTrue(h.hidden.isEmpty())
        assertEquals(listOf("--git-dir=/synthetic/main/.git", "branch", "-d", "topic"), h.gitCalls.last().second)
        assertEquals(listOf("Deleted worktree topic; deleted branch topic"), h.done)

        val unmerged = Harness()
        unmerged.gitResults += GitResult(0, "")
        unmerged.gitResults += GitResult(1, "error: the branch 'topic' is not fully merged\nhint: run 'git branch -D topic'")
        unmerged.delete(branch = true)
        assertEquals(listOf("Deleted worktree topic; kept branch topic: the branch 'topic' is not fully merged"), unmerged.done)
        assertEquals(1, unmerged.hidden.size) // The worktree itself is gone, so hiding still applies.
    }

    @Test fun `git refusals are reported and leave sessions visible`() {
        val h = Harness()
        h.gitResults += GitResult(128, "fatal: '/synthetic/topic' contains modified or untracked files, use --force to delete it\n")
        h.delete(branch = true)
        assertEquals(listOf("Could not delete worktree: '/synthetic/topic' contains modified or untracked files, use --force to delete it"), h.reports)
        assertEquals(1, h.gitCalls.size) // No branch deletion after a refused removal.
        assertTrue(h.hidden.isEmpty() && h.done.isEmpty())
        assertEquals(0, h.rescans)
        assertFalse(h.gitCalls.single().second.any { it.startsWith("--force") || it == "-f" })
    }

    @Test fun `running sessions terminals windows and agent data block deletion before git runs`() {
        val cases = listOf<Pair<String, Harness.() -> Unit>>(
            "running" to { inside = listOf(session.copy(isLive = true)) },
            "terminal tab" to { associated = setOf("inside") },
            "IntelliJ window" to { openProjects = mapOf("window" to checkout.root.resolve("src")) },
            "agent's data folder" to { protected = listOf(Path.of("/synthetic")) },
            "Open projects changed" to { changeProjects = true },
        )
        for ((expected, setup) in cases) {
            val h = Harness().apply(setup)
            h.delete()
            assertTrue(expected, h.gitCalls.isEmpty())
            assertTrue(expected, h.reports.single().contains(expected))
        }
        val parent = Harness().apply { openProjects = mapOf("main" to Path.of("/synthetic")) }
        parent.delete() // A window on an enclosing folder is unaffected by removing the worktree.
        assertEquals(1, parent.gitCalls.size)
    }

    @Test fun `replaced checkouts changed branches and new agents abort after the refresh`() {
        val replaced = Harness().apply { verified = checkout.copy(directoryKey = "other") }
        replaced.delete()
        assertTrue(replaced.reports.single().contains("missing, unreadable or replaced"))
        val branch = Harness().apply { verified = checkout.copy(branch = "other") }
        branch.delete(branch = true)
        assertTrue(branch.reports.single().contains("branch changed"))
        assertTrue(Harness().apply { verified = checkout.copy(branch = "other") }.delete(branch = false))
        val arrived = Harness()
        arrived.refreshEffect = { arrived.inside = arrived.inside + arrived.session.copy(id = "claude", kind = AgentKind.CLAUDE_CODE) }
        arrived.delete()
        assertTrue(arrived.reports.single().contains("Sessions in this worktree changed"))
        assertTrue(listOf(replaced, branch, arrived).all { it.gitCalls.isEmpty() })
    }

    @Test fun `containing agent storage or using protected git metadata blocks deletion`() {
        for (protected in listOf(Path.of("/synthetic/topic/agent-data"),
            Path.of("/synthetic/main/.git"), Path.of("/synthetic/main/.git/worktrees/topic"))) {
            val h = Harness().apply { this.protected = listOf(protected) }
            h.delete()
            assertTrue(protected.toString(), h.gitCalls.isEmpty())
            assertTrue(h.reports.single().contains("agent"))
        }
    }

    @Test fun `liveness changes before UI delivery block deletion`() {
        val h = Harness()
        h.controller.request(h.checkout, WorktreeDeletionOptions(true, false))
        h.workers.removeFirst()()
        h.inside = listOf(h.session.copy(isLive = true))
        h.drain()
        assertTrue(h.gitCalls.isEmpty())
        assertTrue(h.reports.single().contains("running"))
    }

    @Test fun `queued removal revalidates windows terminals storage checkout branch and liveness`() {
        val changes = listOf<Harness.() -> Unit>(
            { openProjects = mapOf("late" to checkout.root) },
            { associated = setOf(session.id) },
            { protected = listOf(checkout.root.resolve("agent-data")) },
            { verified = checkout.copy(directoryKey = "replacement") },
            { verified = checkout.copy(branch = "replacement") },
            { inside = listOf(session.copy(isLive = true)) },
            { refreshEffect = { error("Scan failed") } },
        )
        for (change in changes) {
            val h = Harness()
            h.controller.request(h.checkout, WorktreeDeletionOptions(true, true))
            h.workers.removeFirst()()
            h.deliveries.removeFirst()()
            h.change()
            h.drain()
            assertTrue(h.gitCalls.toString(), h.gitCalls.isEmpty())
            assertEquals(1, h.reports.size)
        }
    }

    @Test fun `deletion coalesces with opening and still hides after the window closes`() {
        val pending = mutableSetOf(Path.of("/synthetic/topic"))
        val busy = Harness(pending = pending)
        assertFalse(busy.delete())
        assertTrue(busy.reports.single().contains("already using this worktree"))
        assertTrue(busy.workers.isEmpty())

        val closing = Harness()
        closing.gitEffect = { closing.alive = false }
        closing.delete()
        assertEquals(listOf(setOf("inside")), closing.hidden)
        assertTrue(closing.done.isEmpty() && closing.reports.isEmpty())
    }

    @Test fun `only linked worktrees are offered and sessions match by checkout then path`() {
        val h = Harness()
        val main = h.checkout.copy(root = Path.of("/synthetic/main"), gitDir = Path.of("/synthetic/main/.git"), kind = Checkout.Kind.MAIN)
        assertFalse(WorktreeDeletionPresentation.of(main, emptyList(), emptySet(), emptyList(), emptyList()).visible)
        assertFalse(WorktreeDeletionPresentation.of(null, emptyList(), emptySet(), emptyList(), emptyList()).visible)
        assertTrue(WorktreeDeletionPresentation.of(h.checkout, h.inside, emptySet(), emptyList(), emptyList()).enabled)
        val gone = WorktreeDeletionPresentation.of(h.checkout.unavailable("Checkout missing"), h.inside, emptySet(), emptyList(), emptyList())
        assertTrue(gone.visible && !gone.enabled)

        val unresolved = h.session.copy(id = "unresolved", cwd = Path.of("/synthetic/topic/deleted"), checkout = null, location = null)
        val sibling = h.session.copy(id = "sibling", cwd = Path.of("/synthetic/topic-2"), checkout = null, location = null)
        val inMain = h.session.copy(id = "main", cwd = Path.of("/synthetic/main"), checkout = main)
        val paths = ResolvedPaths.resolve(listOf(unresolved.cwd, sibling.cwd), resolver = { it },
            repositoryResolver = { null }, checkoutResolver = { _, _ -> null })
        assertEquals(listOf("inside", "unresolved"),
            worktreeSessions(h.checkout, listOf(h.session, unresolved, sibling, inMain), paths).map { it.id })
        assertNull(h.checkout.copy(branch = "--force").deletableBranch)
    }

    @Test fun `real git removes a clean worktree and refuses untracked files`() {
        assumeTrue("git is not installed", runCatching { git(Path.of("."), "--version") }.isSuccess)
        val base = SessionFilter.canonical(Files.createTempDirectory("seshlog-worktree-delete"))
        try {
            val main = base.resolve("main")
            Files.createDirectories(main)
            git(main, "init", "-q")
            git(main, "-c", "user.email=test@example.invalid", "-c", "user.name=Test", "-c", "commit.gpgsign=false",
                "commit", "-q", "--allow-empty", "-m", "init")
            git(main, "worktree", "add", "-q", "-b", "topic", base.resolve("topic").toString())
            git(main, "worktree", "add", "-q", "-b", "dirty", base.resolve("dirty").toString())
            Files.writeString(base.resolve("dirty/untracked.txt"), "work in progress")

            fun deleteReal(root: Path, branch: Boolean): Pair<List<String>, List<String>> {
                val checkout = GitRepository.checkout(root)!!
                val reports = mutableListOf<String>()
                val done = mutableListOf<String>()
                WorktreeDeletion<String>(refresh = {}, sessions = { emptyList() }, resolve = { path, previous -> GitRepository.checkout(path, previous) },
                    projects = { OpenProjectSnapshot(emptySet(), emptyMap()) }, projectSet = { emptySet() }, associated = { emptySet() },
                    protectedRoots = { emptyList() }, git = { directory, args -> GitCommand.run("git", directory, args) },
                    hide = {}, rescan = {}, worker = { it() }, ui = { it() }, active = { true },
                    report = { reports += it }, done = { done += it }).request(checkout, WorktreeDeletionOptions(true, branch))
                return reports to done
            }

            val (cleanReports, cleanDone) = deleteReal(base.resolve("topic"), branch = true)
            assertEquals(emptyList<String>(), cleanReports)
            assertEquals(listOf("Deleted worktree topic; deleted branch topic"), cleanDone)
            assertFalse(Files.exists(base.resolve("topic")))

            val (dirtyReports, dirtyDone) = deleteReal(base.resolve("dirty"), branch = false)
            assertTrue(dirtyReports.single(), dirtyReports.single().contains("modified or untracked"))
            assertTrue(dirtyDone.isEmpty())
            assertTrue(Files.exists(base.resolve("dirty/untracked.txt")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun git(directory: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    }
}
