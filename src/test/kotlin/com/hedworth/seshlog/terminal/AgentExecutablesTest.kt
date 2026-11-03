package com.hedworth.seshlog.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class AgentExecutablesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: Path, name: String, executable: Boolean = true): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.writeString(file, "#!/bin/sh\n")
        file.toFile().setExecutable(executable)
        return file
    }

    private fun path(vararg dirs: Path) = dirs.joinToString(File.pathSeparator)

    private fun locate(executable: String, pathValue: String?, windows: Boolean = false, extensions: List<String> = emptyList()) =
        AgentExecutables.locate(executable, pathValue, windows, { extensions }, home = tmp.root.path)

    @Test fun `bare names use the first PATH directory holding an executable file`() {
        val first = tmp.root.toPath().resolve("first")
        val second = tmp.root.toPath().resolve("second")
        file(first, "claude", executable = false)
        Files.createDirectories(first.resolve("codex"))
        val claude = file(second, "claude")
        val codex = file(second, "codex")
        assertEquals(claude, locate("claude", path(first, second)))
        assertEquals(codex, locate(" codex ", path(first, second)))
        assertNull(locate("pi", path(first, second)))
    }

    @Test fun `absolute and home paths are checked directly while relative paths and blanks are not`() {
        val bin = tmp.root.toPath().resolve("bin")
        val claude = file(bin, "claude")
        file(bin, "codex", executable = false)
        assertEquals(claude, locate(claude.toString(), null))
        assertEquals(claude, locate("~/bin/claude", null))
        assertNull(locate(bin.resolve("codex").toString(), null))
        assertNull(locate("bin/claude", path(tmp.root.toPath())))
        assertNull(locate("  ", path(bin)))
    }

    @Test fun `a missing PATH finds no bare name`() {
        file(tmp.root.toPath(), "claude")
        assertNull(locate("claude", null))
        assertNull(locate("claude", ""))
    }

    @Test fun `windows also tries executable extensions`() {
        val bin = tmp.root.toPath().resolve("bin")
        val cmd = file(bin, "claude.cmd")
        assertEquals(cmd, locate("claude", path(bin), windows = true, extensions = listOf(".exe", ".cmd")))
        assertEquals(cmd, locate(bin.resolve("claude").toString(), null, windows = true, extensions = listOf(".cmd")))
        assertNull(locate("claude", path(bin)))
    }

    @Test fun `results are cached per executable until the ttl passes`() {
        var now = 0L
        var result: Path? = null
        val calls = mutableListOf<String>()
        val availability = AgentAvailability(finder = { calls += it; result }, clock = { now }, ttlMillis = 1_000)
        assertNull(availability.locate("claude"))
        result = Path.of("/bin/claude")
        now = 999
        assertNull(availability.locate("claude"))
        assertEquals(Path.of("/bin/claude"), availability.locate("/opt/claude"))
        now = 1_000
        assertEquals(Path.of("/bin/claude"), availability.locate("claude"))
        assertEquals(listOf("claude", "/opt/claude", "claude"), calls)
    }
}
