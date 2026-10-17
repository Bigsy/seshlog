package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.codex.CodexTranscriptParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Manual check, after a Codex upgrade, that Codex still leaves the evidence [CodexDaemon] matches on;
 * only runs when SESHLOG_CODEX_CHECK=1 (`make codex-check`). Open a plain `codex` tab and send it a
 * prompt first. Fails when the evidence can no longer be read, warns when it reads but no longer has
 * the startup shape (matching then fails safe: tabs stay unattached). Prints counts only.
 */
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
        assumeTrue("No Codex TUI is running; open a plain codex tab and send it a prompt", launches.isNotEmpty())
        val pid = daemon ?: throw AssertionError("Codex TUIs run but app-server-daemon/daemon.pid names no live server: " +
            "Codex moved or changed its daemon marker, or these TUIs run without the daemon")

        val evidence = ProcessTranscripts.writableFiles(launches.flatMapTo(HashSet()) { it.pids } + pid)
        assertFalse("The daemon's open files could not be read", pid in evidence.unknown)
        val held = evidence.files[pid].orEmpty().filter { it.fileName.toString().let { name -> name.startsWith("rollout-") && name.endsWith(".jsonl") } }
        val headers = held.map { CodexTranscriptParser.rolloutHeader(it) }
        assertTrue("${headers.count { it == null }} of ${held.size} held session headers are unreadable", headers.all { it != null })
        val terminal = headers.filterNotNull().filter { it.terminal }
        assertTrue("The daemon holds no terminal session open. Send the plain codex tab a prompt; if it already has one, " +
            "Codex no longer keeps session files open and no tab can be matched", terminal.isNotEmpty())
        for ((field, missing) in listOf<Pair<String, (CodexTranscriptParser.RolloutHeader) -> Boolean>>(
            "id" to { it.id == null }, "cwd" to { it.cwd == null },
            "payload.timestamp" to { it.createdAt == null }, "record timestamp" to { it.writtenAt == null },
        )) assertTrue("${terminal.count(missing)} of ${terminal.size} held terminal headers lack $field", terminal.none(missing))
        val startup = terminal.count { CodexDaemon.startupShaped(it.createdAt, it.writtenAt) }

        val started = System.nanoTime()
        val matched = CodexDaemon.match(launches, pid, evidence)
        val millis = (System.nanoTime() - started) / 1_000_000
        report("heldTerminal=${terminal.size} startupShaped=$startup heldOther=${held.size - terminal.size} " +
            "matchedLaunches=${launches.count { launch -> launch.pids.any(matched::containsKey) }} matchMs=$millis")
        if (startup == 0 && launches.any { !it.resumed && it.forkOf == null })
            report("WARN no held thread was created before its first write, so startup matching is off. Codex may now " +
                "create threads at the first prompt; plain tabs sharing a directory will stay unattached.")
    }

    private fun report(line: String) = println("SESHLOG_CODEX_CHECK $line")
}
