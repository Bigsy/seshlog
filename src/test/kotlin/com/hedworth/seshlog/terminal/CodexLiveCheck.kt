package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.codex.CodexTranscriptParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Path

/** Opt-in aggregate diagnostics of existing processes. Never starts an agent or certifies a title. */
class CodexLiveCheck {
    @Test
    fun daemonEvidence() {
        assumeTrue(System.getenv("SESHLOG_CODEX_CHECK") == "1")
        val home = System.getenv("CODEX_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".codex")
        val table = ProcessTableSnapshot.capture()
        assertTrue("The process table could not be read", table.complete)
        val daemon = CodexDaemon.pid(home, table)
        val launches = CodexDaemon.instances(table, "codex", daemon ?: -1)
        report("launches=${launches.size} plain=${launches.count { !it.resumed && it.forkOf == null }} " +
            "resumed=${launches.count { it.resumed }} forks=${launches.count { it.forkOf != null }} daemon=${if (daemon == null) 0 else 1}")
        assumeTrue("No Codex TUI is running; nothing to inspect", launches.isNotEmpty())
        val evidence = ProcessTranscripts.writableFiles(launches.flatMapTo(HashSet()) { it.pids } + listOfNotNull(daemon))
        val own = launches.flatMap { launch -> launch.pids.flatMap { evidence.files[it].orEmpty() } }.distinct()
        val held = daemon?.let { evidence.files[it] }.orEmpty()
        fun terminalCount(paths: Collection<Path>) = paths.count {
            it.fileName.toString().let { name -> name.startsWith("rollout-") && name.endsWith(".jsonl") } &&
                CodexTranscriptParser.rolloutHeader(it)?.terminal == true
        }
        report("directTerminalRollouts=${terminalCount(own)} daemonTerminalRollouts=${terminalCount(held)} " +
            "unavailableProcesses=${evidence.unknown.size} terminalIdentityVerified=0 " +
            "(terminal titles must be captured and validated by the IDE)")
    }

    private fun report(line: String) = println("SESHLOG_CODEX_CHECK $line")
}
