package com.hedworth.seshlog.worktree

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs git directly, without a shell. Output goes to a capped temporary file. Call on a worker. */
internal object GitCommand {
    private const val OUTPUT_LIMIT = 64 * 1024

    fun run(executable: String, directory: Path, args: List<String>, timeoutSeconds: Long = 120): GitResult {
        val output = Files.createTempFile("seshlog-git-", ".txt")
        try {
            val builder = ProcessBuilder(listOf(executable) + args).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            val process = builder.start()
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return GitResult(-1, "git did not finish within $timeoutSeconds seconds; check the worktree before retrying")
            }
            val text = Files.newInputStream(output).use { String(it.readNBytes(OUTPUT_LIMIT)) }
            return GitResult(process.exitValue(), text)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /** IntelliJ's shell PATH first, so a Homebrew or other user-installed git is preferred. */
    fun executable(): String = PathEnvironmentVariableUtil.findInPath("git")?.path ?: "git"
}
