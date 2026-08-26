package com.hedworth.seshlog.claude

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Manual benchmark against the real ~/.claude; only runs when SESHLOG_BENCH=1. */
class RealDataScanBenchmark {
    @Test
    fun coldAndWarmScan() {
        assumeTrue(System.getenv("SESHLOG_BENCH") == "1")
        val root = Paths.get(System.getProperty("user.home"), ".claude")
        assumeTrue(Files.isDirectory(root.resolve("projects")))
        val provider = ClaudeCodeSessionProvider({ root }, { "claude" })
        val t0 = System.nanoTime()
        val cold = provider.scan(emptyMap())
        val t1 = System.nanoTime()
        val warm = provider.scan(cold.associateBy { it.id })
        val t2 = System.nanoTime()
        val totalBytes = cold.sumOf { Files.size(it.transcriptPath) }
        println("SESHLOG_BENCH cold=${(t1 - t0) / 1_000_000}ms warm=${(t2 - t1) / 1_000_000}ms sessions=${cold.size} bytes=${totalBytes / 1_000_000}MB live=${cold.count { it.isLive }} titled=${cold.count { it.hasExplicitTitle }} untitledNoPrompt=${cold.count { !it.hasExplicitTitle && it.promptCount == 0 }}")
        cold.sortedByDescending { it.lastActivityAt }.take(8).forEach { println("SESHLOG_BENCH  ${if (it.isLive) "●" else " "} ${it.title} | ${it.cwd.fileName} | ${it.displayBranch ?: "-"}") }

        // Content search: cold = build the in-memory index, warm = pure string scan.
        val rt = Runtime.getRuntime()
        System.gc()
        val memBefore = rt.totalMemory() - rt.freeMemory()
        val index = com.hedworth.seshlog.index.ContentSearchIndex(TranscriptTextExtractor::extract)
        val s0 = System.nanoTime()
        val coldHits = index.search("rollback", cold)
        val s1 = System.nanoTime()
        val warmHits = index.search("terminal tab", cold)
        val s2 = System.nanoTime()
        System.gc()
        val memAfter = rt.totalMemory() - rt.freeMemory()
        println("SESHLOG_BENCH search cold=${(s1 - s0) / 1_000_000}ms (${coldHits.size} hits) warm=${(s2 - s1) / 1_000_000}ms (${warmHits.size} hits) indexed=${index.size} approxIndexMB=${(memAfter - memBefore) / 1_000_000}")
        val biggest = cold.maxByOrNull { Files.size(it.transcriptPath) }!!
        val p0 = System.nanoTime()
        val tail = TranscriptTailReader.lastMessages(biggest.transcriptPath, 2)
        println("SESHLOG_BENCH tail(2) of ${Files.size(biggest.transcriptPath) / 1_000_000}MB transcript=${(System.nanoTime() - p0) / 1_000_000}ms roles=${tail.map { it.role }}")
        warmHits.take(5).forEach { println("SESHLOG_BENCH  ${it.score} ${it.session.title} :: ${it.snippet}") }
    }
}
