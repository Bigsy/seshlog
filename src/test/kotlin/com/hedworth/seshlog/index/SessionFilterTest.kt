package com.hedworth.seshlog.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class SessionFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `cwd inside or equal to a root matches, siblings do not`() {
        val root = Paths.get("/Users/tester/workspace/acme")
        assertTrue(SessionFilter.isUnderAny(Paths.get("/Users/tester/workspace/acme"), listOf(root)))
        assertTrue(SessionFilter.isUnderAny(Paths.get("/Users/tester/workspace/acme/api"), listOf(root)))
        assertFalse(SessionFilter.isUnderAny(Paths.get("/Users/tester/workspace/acme-other"), listOf(root)))
        assertFalse(SessionFilter.isUnderAny(Paths.get("/Users/tester/workspace"), listOf(root)))
        assertFalse(SessionFilter.isUnderAny(Paths.get("/x"), emptyList()))
    }

    @Test
    fun `symlinked paths are normalised with toRealPath`() {
        val real = tmp.newFolder("real").toPath()
        val link = tmp.root.toPath().resolve("link")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: Exception) {
            assumeTrue("symlinks unsupported here", false)
        }
        assertTrue(SessionFilter.isUnderAny(link.resolve("sub"), listOf(real)))
        assertTrue(SessionFilter.isUnderAny(real, listOf(link)))
    }
}
