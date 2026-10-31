package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class WorktreeCreationTest {
    private fun session(root: Path) = Session(AgentKind.CODEX, "synthetic", "Synthetic", root, null, null,
        Instant.EPOCH, null, false, null, null, 1, true)

    @Test fun `creation preserves dirty source maps subdirectory and does not run checkout hooks`() = repository { root, parent ->
        Files.writeString(root.resolve("src/file"), "dirty source")
        Files.writeString(root.resolve("untracked"), "keep")
        val hook = root.resolve(".git/hooks/post-checkout")
        Files.writeString(hook, "#!/bin/sh\ntouch '${parent.resolve("hook-ran")}'\n")
        hook.toFile().setExecutable(true)
        val source = GitRepository.checkout(root)!!
        val target = parent.resolve("new worktree")
        val created = WorktreeCreator.create(session(root.resolve("src")), source,
            WorktreeCreationOptions("HEAD", "experiment/topic", target), emptyList())
        assertEquals(target.resolve("src"), created.directory)
        assertEquals("base", Files.readString(created.directory.resolve("file")))
        assertEquals("dirty source", Files.readString(root.resolve("src/file")))
        assertFalse(Files.exists(target.resolve("untracked")))
        assertFalse(Files.exists(parent.resolve("hook-ran")))
        assertEquals("experiment/topic", created.checkout.branch)
        assertEquals(source.commonDir, created.checkout.commonDir)
    }

    @Test fun `creation refuses existing nested protected destinations invalid refs and missing subdirectories`() = repository { root, parent ->
        val source = GitRepository.checkout(root)!!
        val options = WorktreeCreationOptions("HEAD", "new-topic", parent.resolve("topic"))
        val sourceSession = session(root)
        assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(sourceSession, source, options.copy(destination = root), emptyList()) }
        assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(sourceSession, source, options.copy(destination = root.resolve("nested")), emptyList()) }
        for (protected in listOf(parent, options.destination.resolve("agent-data"), source.commonDir)) {
            assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(sourceSession, source, options, listOf(protected)) }
        }
        assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(sourceSession, source, options.copy(base = "missing"), emptyList()) }
        assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(sourceSession, source, options.copy(branch = "bad..branch"), emptyList()) }
        Files.createDirectory(root.resolve("uncommitted-dir"))
        assertThrows(IllegalStateException::class.java) { WorktreeCreator.create(session(root.resolve("uncommitted-dir")), source, options, emptyList()) }
        assertFalse(Files.exists(options.destination))
    }

    @Test fun `creation cancellation stale targets and launch failures preserve partial success`() {
        val root = Path.of("/synthetic/new")
        val created = CreatedWorktree(Checkout(root, Path.of("/synthetic/main/.git/worktrees/new"),
            Path.of("/synthetic/main/.git"), Checkout.Kind.LINKED, "new", null), root)
        for (mode in listOf("cancel", "invalid", "closed", "launch-error", "ok")) {
            val workers = ArrayDeque<() -> Unit>()
            val deliveries = ArrayDeque<() -> Unit>()
            val reports = mutableListOf<String>()
            var finished = 0
            var launched = 0
            WorktreeCreation(create = { created }, validate = { if (mode == "invalid") error("replaced") },
                open = { if (mode == "cancel") null else "project" }, destinationValid = { _, _ -> mode != "closed" },
                launch = { _, _ -> if (mode == "launch-error") error("terminal failed") else launched++ },
                worker = { workers += it }, ui = { deliveries += it }, active = { true },
                report = { reports += it }, finished = { finished++ }).start()
            while (workers.isNotEmpty() || deliveries.isNotEmpty()) {
                while (workers.isNotEmpty()) workers.removeFirst()()
                while (deliveries.isNotEmpty()) deliveries.removeFirst()()
            }
            assertEquals(1, finished)
            assertEquals(if (mode == "ok") 1 else 0, launched)
            if (mode != "ok") assertTrue(reports.single(), reports.single().contains("Worktree kept at"))
        }
    }

    @Test fun `Codex fork command explicitly pins destination and Claude keeps native fork semantics`() {
        val root = Path.of("/synthetic/source")
        val target = Path.of("/synthetic/new folder")
        val selected = session(root)
        val codex = com.hedworth.seshlog.codex.CodexSessionProvider({ root }, { "codex" })
        val claude = com.hedworth.seshlog.claude.ClaudeCodeSessionProvider({ root }, { "claude" })
        assertEquals("codex fork synthetic --cd '/synthetic/new folder' ${com.hedworth.seshlog.terminal.CodexTitle.OVERRIDE}",
            codex.forkInDirectoryCommand(selected, target))
        assertEquals("claude --resume synthetic --fork-session", claude.forkInDirectoryCommand(selected.copy(kind = AgentKind.CLAUDE_CODE), target))
    }

    private fun repository(block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("seshlog-create-test").toRealPath()
        val root = Files.createDirectory(parent.resolve("main"))
        try {
            GitRead.run(root, listOf("init", "-q"))
            Files.createDirectory(root.resolve("src")); Files.writeString(root.resolve("src/file"), "base")
            GitRead.run(root, listOf("add", "."))
            GitRead.run(root, listOf("-c", "user.name=Synthetic", "-c", "user.email=test@example.invalid", "-c",
                "commit.gpgsign=false", "commit", "-qm", "synthetic"))
            block(root, parent)
        } finally { parent.toFile().deleteRecursively() }
    }
}
