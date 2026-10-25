package com.hedworth.seshlog.index.evaluation

import com.google.gson.Gson
import com.hedworth.seshlog.index.ContentSearchIndex
import com.hedworth.seshlog.index.lexical.LexicalSearchStore
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.math.ceil
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in synthetic end-to-end costs, including source reconciliation and original-source snippets.
 */
class LexicalSearchBenchmark {
    @Test
    fun durableScale() {
        assumeTrue(System.getenv("SESHLOG_LEXICAL_BENCH") == "1")
        val c = RetrievalEvaluation.corpus()
        for (run in 1..3) for (count in listOf(1000, 10000)) {
            val directory = Files.createTempDirectory("seshlog-lexical-bench")
            try {
                val sessions =
                    (0 until count).map { c.sessions[it % c.sessions.size].copy(id = "s$it") }
                val entries =
                    sessions
                        .withIndex()
                        .associate {
                            it.value.id to
                                c.entries.getValue(c.sessions[it.index % c.sessions.size].id)
                        }
                        .toMutableMap()
                val stamps = HashMap<String, Int>()
                val db = directory.resolve("index.sqlite")
                val snapshot = directory.resolve("export.sqlite")
                var reads = 0
                fun index(path: java.nio.file.Path) =
                    ContentSearchIndex(
                        { emptyList() },
                        { stamps[it.id] ?: 1 },
                        entryExtractor = {
                            reads++
                            entries.getValue(it.id)
                        },
                        storeFactory = { LexicalSearchStore.open(path) },
                    )
                val start = System.nanoTime()
                val cold = index(db)
                cold.search("durable checkpoints unexpectedly", sessions)
                val buildMs = ms(start)
                val queries =
                    listOf(
                        "durable checkpoints unexpectedly",
                        "authentication recovery",
                        "ERR_SERIALIZATION_409",
                        "\"generation counter\"",
                    )
                queries.forEach { cold.search(it, sessions) }
                val samples = ArrayList<Double>()
                repeat(3) {
                    queries.forEach { q ->
                        val t = System.nanoTime()
                        cold.search(q, sessions)
                        samples += ms(t)
                    }
                }
                val before = reads
                val incrementalStart = System.nanoTime()
                cold.search(
                    "recovery",
                    sessions.mapIndexed { i, s ->
                        if (i == 0) s.copy(title = "Renamed source") else s
                    },
                )
                val incrementalMs = ms(incrementalStart)
                val incrementalReads = reads - before
                val renamed =
                    sessions.mapIndexed { i, s ->
                        if (i == 0) s.copy(title = "Renamed source") else s
                    }
                check(incrementalReads == 0)
                entries[sessions.first().id] =
                    entries.getValue(sessions.first().id) +
                        com.hedworth.seshlog.model.ConversationEntry(
                            com.hedworth.seshlog.model.ConversationMessage(
                                com.hedworth.seshlog.model.Role.ASSISTANT,
                                "new append evidence",
                                null,
                            ),
                            "append",
                        )
                stamps[sessions.first().id] = 2
                val appendBefore = reads
                val appendStart = System.nanoTime()
                cold.search("new append evidence", renamed)
                val appendMs = ms(appendStart)
                val appendReads = reads - appendBefore
                check(appendReads == 1)
                val exportStart = System.nanoTime()
                cold.export(snapshot)
                val exportMs = ms(exportStart)
                val retainedChars = cold.retainedCharacters
                cold.close()
                reads = 0
                val reopenStart = System.nanoTime()
                index(db).use { it.search("Renamed source", renamed) }
                val reopenMs = ms(reopenStart)
                val reopenReads = reads
                check(reopenReads == 0)
                reads = 0
                val importStart = System.nanoTime()
                index(directory.resolve("fresh.sqlite")).use {
                    check(it.importSnapshot(snapshot) == count)
                }
                val importMs = ms(importStart)
                val sorted = samples.sorted()
                val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                println(
                    "SESHLOG_LEXICAL " +
                        Gson()
                            .toJson(
                                mapOf(
                                    "event" to "scale",
                                    "run" to run,
                                    "schema" to LexicalSearchStore.VERSION,
                                    "sessions" to count,
                                    "buildMs" to buildMs,
                                    "warmP50Ms" to sorted[sorted.size / 2],
                                    "warmP95Ms" to
                                        sorted[
                                            (ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)],
                                    "reopenAndReconcileAndQueryMs" to reopenMs,
                                    "reopenSourceReads" to reopenReads,
                                    "metadataUpdateMs" to incrementalMs,
                                    "metadataUpdateReads" to incrementalReads,
                                    "appendMs" to appendMs,
                                    "appendSourceReads" to appendReads,
                                    "exportMs" to exportMs,
                                    "importMs" to importMs,
                                    "dbBytes" to Files.size(db),
                                    "retainedSourceChars" to retainedChars,
                                    "processRssSampleBytes" to rss(),
                                    "summedHeapPoolPeakBytes" to
                                        ManagementFactory.getMemoryPoolMXBeans()
                                            .filter {
                                                it.type == java.lang.management.MemoryType.HEAP
                                            }
                                            .sumOf { it.peakUsage.used },
                                    "jvmHeapUsedBytes" to memory.used,
                                    "jvmHeapCommittedBytes" to memory.committed,
                                    "sqlitePageCacheBytes" to 8 * 1024 * 1024,
                                )
                            )
                )
            } finally {
                Files.walk(directory).use {
                    it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun rss(): Long? =
        try {
            if (!Files.isExecutable(java.nio.file.Path.of("/bin/ps"))) null
            else {
                val process =
                    ProcessBuilder(
                            "/bin/ps",
                            "-o",
                            "rss=",
                            "-p",
                            ProcessHandle.current().pid().toString(),
                        )
                        .start()
                val value =
                    process.inputStream.bufferedReader().use { it.readText().trim().toLongOrNull() }
                process.waitFor()
                value?.times(1024)
            }
        } catch (_: Exception) {
            null
        }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0
}
