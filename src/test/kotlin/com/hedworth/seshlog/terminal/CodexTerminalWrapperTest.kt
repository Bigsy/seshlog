package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Real shell/executable tests, with no installed agent, personal shell config or network. */
class CodexTerminalWrapperTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: Path
    private lateinit var wrapper: Path

    @Before fun setup() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        root = temporary.root.toPath()
        wrapper = CodexTerminalWrapper.install(root.resolve("IDE config with 'quotes'"))
    }

    private fun executable(directory: Path, body: String): Path {
        Files.createDirectories(directory)
        return Files.writeString(directory.resolve("codex"), "#!/bin/sh\n$body\n").also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private data class Result(val code: Int, val output: String, val errors: String)
    private fun run(path: String, vararg args: String, stdin: String = "", command: List<String>? = null): Result {
        val builder = ProcessBuilder(command ?: (listOf(wrapper.resolve("codex").toString()) + args))
            .directory(root.toFile())
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to path, "HOME" to root.toString()))
        val process = builder.start()
        try {
            process.outputStream.bufferedWriter().use { it.write(stdin) }
            assertTrue("Synthetic executable timed out", process.waitFor(10, TimeUnit.SECONDS))
            return Result(process.exitValue(), process.inputStream.reader().readText(), process.errorStream.reader().readText())
        } finally { process.destroyForcibly() }
    }

    @Test fun `manual codex and resume preserve every argument including shell metacharacters`() {
        val real = executable(root.resolve("real bin"), "printf '%s\\000' \"\$@\"").parent
        val args = arrayOf("resume", "synthetic-id", "", "space and 'quote'", "\$(touch SHOULD_NOT_EXIST)", "*", "one\ntwo")
        val result = run("$wrapper:$real:/bin", *args, command = listOf("/bin/sh", "-c", "codex \"\$@\"", "shell") + args)
        assertEquals(0, result.code)
        assertEquals(listOf("-c", "tui.terminal_title=[\"project\",\"thread-id\"]") + args,
            result.output.split('\u0000').dropLast(1))
        assertFalse(Files.exists(root.resolve("SHOULD_NOT_EXIST")))
    }

    @Test fun `stdin stdout stderr and exit status belong to the real executable`() {
        val real = executable(root.resolve("real"), "read -r line\nprintf 'out:%s' \"\$line\"\nprintf 'err' >&2\nexit 23").parent
        val result = run("$wrapper:$real:/bin", stdin = "fictional input\n")
        assertEquals(Result(23, "out:fictional input", "err"), result)
    }

    @Test fun `real executable follows current PATH order and ignores non executables`() {
        val first = executable(root.resolve("first"), "printf first").parent
        val second = executable(root.resolve("second"), "printf second").parent
        assertEquals("second", run("$wrapper:$second:$first").output)
        Files.setPosixFilePermissions(second.resolve("codex"), PosixFilePermissions.fromString("rw-------"))
        assertEquals("first", run("$wrapper:$second:$first").output)
    }

    @Test fun `duplicate and symlinked wrapper entries cannot recurse`() {
        val real = executable(root.resolve("real"), "printf '%s' \"\$PATH\"").parent
        val alias = Files.createSymbolicLink(root.resolve("shim alias"), wrapper)
        val result = run("$wrapper:$alias:$wrapper:$real:/bin")
        assertEquals(0, result.code)
        assertEquals("$real:/bin", result.output)
    }

    @Test fun `nested IDE wrappers terminate and retain explicit override precedence`() {
        val other = CodexTerminalWrapper.install(root.resolve("other IDE"))
        val real = executable(root.resolve("real"), "printf '%s\\000' \"\$@\"").parent
        val result = run("$wrapper:$other:$real:/bin", "-c", "tui.terminal_title=[]")
        assertEquals(0, result.code)
        assertEquals(listOf("-c", "tui.terminal_title=[]"), result.output.split('\u0000').dropLast(1).takeLast(2))
    }

    @Test fun `empty and relative PATH entries preserve shell lookup semantics`() {
        executable(root, "printf cwd")
        assertEquals("cwd", run("$wrapper:").output)
        executable(root.resolve("relative"), "printf relative")
        assertEquals("relative", run("$wrapper:relative:").output)
    }

    @Test fun `missing real binary fails clearly without recursion`() {
        val result = run("$wrapper:$wrapper")
        assertEquals(127, result.code)
        assertTrue(result.errors.contains("codex was not found"))
        assertEquals("", result.output)
    }

    @Test fun `existing shell functions and aliases keep their normal precedence`() {
        val real = executable(root.resolve("real"), "printf wrapped").parent
        for (shell in listOf("/bin/bash", "/bin/zsh").filter { Files.isExecutable(Path.of(it)) }) {
            val function = run("$wrapper:$real:/bin", command = listOf(shell, "-f", "-c",
                "codex() { printf custom-function; }\ncodex"))
            assertEquals("custom-function", function.output)
            val aliases = (if (shell.endsWith("bash")) "shopt -s expand_aliases\n" else "") +
                // Parse the invocation after defining the alias, as separate interactive input does.
                "alias codex='printf custom-alias'\neval codex"
            assertEquals(shell, "custom-alias", run("$wrapper:$real:/bin", command = listOf(shell, "-f", "-c", aliases)).output)
        }
    }

    @Test fun `later PATH changes and full executable paths remain user controlled`() {
        val first = executable(root.resolve("first"), "printf '%s' \"\$1\"").parent
        val other = executable(root.resolve("other"), "printf bypass").parent
        assertEquals("bypass", run("$wrapper:$first:/bin", command = listOf("/bin/sh", "-c",
            "PATH=${ShellQuote.quote(other.toString())}:\$PATH\ncodex")).output)
        assertEquals("original", run("$wrapper:$first:/bin", command = listOf(first.resolve("codex").toString(), "original")).output)
    }

    @Test fun `wrapper exec retains the process identity instead of leaving a launcher parent`() {
        val real = executable(root.resolve("real"), "printf '%s' \"\$\$\"").parent
        val process = ProcessBuilder(wrapper.resolve("codex").toString()).apply { environment()["PATH"] = "$wrapper:$real" }.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(process.pid().toString(), process.inputStream.reader().readText())
    }

    @Test fun `installer repairs corrupted content and preserves the versioned location`() {
        val script = wrapper.resolve("codex")
        val original = Files.readString(script)
        Files.writeString(script, "broken")
        assertEquals(wrapper, CodexTerminalWrapper.install(root.resolve("IDE config with 'quotes'")))
        assertEquals(original, Files.readString(script))
        assertTrue(Files.isExecutable(script))
        assertEquals(listOf("codex"), Files.list(wrapper).use { it.map { p -> p.fileName.toString() }.toList() })
    }
}
