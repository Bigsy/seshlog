package com.hedworth.seshlog.claude

import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.index.ContentSearchIndex
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.pi.PiSessionProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Manual benchmark against local agent data; only runs when SESHLOG_BENCH=1. */
class RealDataScanBenchmark {
    @Test
    fun coldAndWarmScan() {
        assumeTrue(System.getenv("SESHLOG_BENCH") == "1")
        val home = Paths.get(System.getProperty("user.home"))

        benchmark("claude", home.resolve(".claude"), ClaudeCodeSessionProvider({ home.resolve(".claude") }, { "claude" }))
        benchmark("codex", home.resolve(".codex"), CodexSessionProvider({ home.resolve(".codex") }, { "codex" }))
        benchmark("pi", home.resolve(".pi/agent/sessions"), PiSessionProvider({ home.resolve(".pi/agent/sessions") }, { "pi" }))
    }

    private fun benchmark(label: String, root: Path, provider: SessionProvider) {
        if (!Files.isDirectory(root)) {
            println("SESHLOG_BENCH provider=$label available=0")
            return
        }
        val scanStart = System.nanoTime()
        val cold = provider.scan(emptyMap())
        val scanMiddle = System.nanoTime()
        val warm = provider.scan(cold.associateBy { it.id })
        val scanEnd = System.nanoTime()
        val totalBytes = cold.sumOf { session ->
            session.transcriptPath?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
        }
        println(
            "SESHLOG_BENCH provider=$label scanColdMs=${millis(scanMiddle - scanStart)} " +
                "scanWarmMs=${millis(scanEnd - scanMiddle)} sessions=${cold.size} " +
                "bytes=${totalBytes / 1_000_000} live=${cold.count { it.isLive }} " +
                "titled=${cold.count { it.hasExplicitTitle }} warmSessions=${warm.size}",
        )

        // Use the same bounded, structured entry extractor as the application search service.
        val index = ContentSearchIndex(
            extractor = provider::conversationText,
            entryExtractor = provider::conversationEntries,
            contentStamp = provider::contentStamp,
        )
        val searchStart = System.nanoTime()
        val coldHits = index.search("rollback", cold)
        val searchMiddle = System.nanoTime()
        val warmHits = index.search("terminal tab", cold)
        val searchEnd = System.nanoTime()
        println(
            "SESHLOG_BENCH provider=$label searchColdMs=${millis(searchMiddle - searchStart)} " +
                "searchWarmMs=${millis(searchEnd - searchMiddle)} coldHits=${coldHits.size} " +
                "warmHits=${warmHits.size} indexed=${index.size}",
        )
    }

    private fun millis(nanos: Long): Long = nanos / 1_000_000
}
