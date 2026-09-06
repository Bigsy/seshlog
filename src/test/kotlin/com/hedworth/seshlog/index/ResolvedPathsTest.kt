package com.hedworth.seshlog.index

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class ResolvedPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `large history resolves distinct paths once and filtering never calls resolver`() {
        val root = Paths.get("/synthetic")
        val paths = (0 until 50_000).map { root.resolve("worktree-${it % 100}/sub") }
        var calls = 0
        val snapshot = ResolvedPaths.resolve(paths + root, resolver = { calls++; it }, repositoryResolver = { null })
        assertEquals(101, calls)
        repeat(3) { assertTrue(paths.all { snapshot.isUnderAny(it, listOf(root)) }) }
        assertEquals(101, calls)
    }

    @Test fun `refresh handles symlink changes and missing descendants`() {
        val a = tmp.newFolder("a").toPath()
        val b = tmp.newFolder("b").toPath()
        val link = tmp.root.toPath().resolve("link")
        Files.createSymbolicLink(link, a)
        val missing = link.resolve("removed/sub")
        val first = ResolvedPaths.resolve(listOf(missing, a, b))
        assertTrue(first.isUnderAny(missing, listOf(a)))
        Files.delete(link)
        Files.createSymbolicLink(link, b)
        val next = ResolvedPaths.resolve(listOf(missing, a, b))
        assertTrue(next.isUnderAny(missing, listOf(b)))
        assertFalse(next.isUnderAny(missing, listOf(a)))
    }
}
