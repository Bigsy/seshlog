package com.hedworth.seshlog.index

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class GitRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `sibling worktree and main checkout share common directory but unrelated repo does not`() {
        val root = tmp.root.toPath()
        val main = Files.createDirectories(root.resolve("main/.git/worktrees/topic"))
        Files.copy(javaClass.getResourceAsStream("/fixtures/worktree-commondir.txt")!!, main.resolve("commondir"))
        val worktree = Files.createDirectories(root.resolve("topic"))
        Files.copy(javaClass.getResourceAsStream("/fixtures/worktree-gitdir.txt")!!, worktree.resolve(".git"))
        val other = Files.createDirectories(root.resolve("other/.git")).parent
        val checkout = root.resolve("main")
        val nested = worktree.resolve("removed/sub")
        val snapshot = ResolvedPaths.resolve(listOf(checkout, nested, other))
        assertTrue(snapshot.belongsToRepository(nested, listOf(checkout)))
        assertFalse(snapshot.belongsToRepository(other, listOf(checkout)))
        assertEquals(GitRepository.commonDir(checkout), GitRepository.commonDir(nested))
        Files.writeString(worktree.resolve(".git"), "malformed")
        assertNull(GitRepository.commonDir(worktree))
    }
}
