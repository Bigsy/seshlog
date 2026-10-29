package com.hedworth.seshlog.worktree

import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Bounded, in-memory Git output. Used for source content; never writes it to a temporary file. */
internal object GitRead {
    fun run(root: Path, args: List<String>, limit: Int = 8 * 1024 * 1024): ByteArray {
        val process = ProcessBuilder(listOf(GitCommand.executable(), "--no-pager", "-C", root.toString()) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_OPTIONAL_LOCKS"] = "0"
            }.start()
        process.outputStream.close()
        val reader = FutureTask { process.inputStream.use { it.readNBytes(limit + 1) } }
        Thread.ofVirtual().name("seshlog-git-read").start(reader)
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Git did not finish within 20 seconds" }
            val bytes = reader.get(5, TimeUnit.SECONDS)
            check(bytes.size <= limit) { "Git output exceeds the ${limit / 1024} KiB review limit" }
            check(process.exitValue() == 0) { "Git could not read this checkout or reference; refresh and check the base ref" }
            return bytes
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.cancel(true)
        }
    }

    fun commit(root: Path, ref: String): String {
        require(ref.isNotBlank() && ref.length <= 1024 && !ref.contains('\u0000')) { "Enter a local branch, tag or commit" }
        val hash = run(root, listOf("rev-parse", "--verify", "--end-of-options", "$ref^{commit}"), 4096)
            .toString(Charsets.UTF_8).trim()
        check(hash.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "Git did not resolve a commit" }
        return hash
    }
}
