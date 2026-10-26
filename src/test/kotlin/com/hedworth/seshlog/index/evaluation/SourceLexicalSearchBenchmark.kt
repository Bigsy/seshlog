package com.hedworth.seshlog.index.evaluation

import com.google.gson.Gson
import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.math.ceil
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.sqlite.JDBC

/** Opt-in, synthetic only. No provider roots, agents, network or private histories are used. */
class SourceLexicalSearchBenchmark {
    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun report(fields: Map<String, Any?>) = println("SESHLOG_LEXICAL " + Gson().toJson(fields))

    @Test fun classifiedScaleAndReuse() {
        assumeTrue(System.getenv("SESHLOG_LEXICAL_BENCH") == "1")
        val corpus = SourceRetrievalEvaluation.corpus()
        for (run in 1..3) for (count in listOf(1000, 10000)) {
            val directory = Files.createTempDirectory("seshlog-source-bench")
            try {
                val files = corpus.sessions.associate { session ->
                    session.id to directory.resolve("${session.id}.synthetic").also { file ->
                        Files.writeString(file, corpus.entries.getValue(session.id).joinToString("\n") { "${it.kind} ${it.message.role} ${it.text}" })
                    }
                }
                val templates = (0 until count).associate { "s$it" to corpus.sessions[it % corpus.sessions.size].id }
                val sessions = (0 until count).map { i ->
                    val template = corpus.sessions[i % corpus.sessions.size]
                    template.copy(id = "s$i", transcriptPath = files.getValue(template.id))
                }
                var reads = 0
                var activeStore: LexicalSearchStore? = null
                fun index(path: Path) = ContentSearchIndex({ emptyList() }, { 1 },
                    localTitle = { corpus.titles.getValue(templates.getValue(it.id)) },
                    entryExtractor = { reads++; corpus.entries.getValue(templates.getValue(it.id)) },
                    storeFactory = { LexicalSearchStore.open(path).also { activeStore = it } })
                val db = directory.resolve("lexical.sqlite")
                val snapshot = directory.resolve("export.sqlite")
                val cold = index(db)
                val buildStart = System.nanoTime()
                cold.search("orchard retry policy", sessions)
                val buildMs = ms(buildStart)
                val buildReads = reads
                val buildTokens = activeStore!!.tokenizations
                for (sources in listOf(SearchSources.DEFAULT, SearchSources.ALL, SearchSources.METADATA_ONLY)) {
                    val queries = if (sources == SearchSources.METADATA_ONLY)
                        listOf("Harbour", "Displayed override", "path_only_marker", "migration") else
                        listOf("orchard retry policy", "kestrel", "ERR_KITE_715", "\"database locked\"")
                    queries.forEach { cold.search(it, sessions, sources) }
                    val beforeReads = reads
                    val beforeTokens = activeStore!!.tokenizations
                    val samples = mutableListOf<Double>()
                    repeat(3) { queries.forEach { query ->
                        val start = System.nanoTime()
                        cold.search(query, sessions, sources)
                        samples += ms(start)
                    } }
                    samples.sort()
                    val p95 = samples[ceil(samples.size * .95).toInt() - 1]
                    check(activeStore!!.tokenizations == beforeTokens)
                    report(mapOf("event" to "sourceScale", "run" to run, "schema" to LexicalSearchStore.VERSION,
                        "classification" to LexicalSearchStore.CLASSIFICATION_VERSION, "sessions" to count, "mode" to sources.summary,
                        "buildMs" to buildMs, "buildSourceReads" to buildReads, "buildTokenizations" to buildTokens,
                        "warmP50Ms" to samples[samples.size / 2], "warmP95Ms" to p95, "warmP95Below100Ms" to (p95 < 100),
                        "warmSourceReads" to reads - beforeReads, "warmTokenizations" to activeStore!!.tokenizations - beforeTokens,
                        "retainedSourceChars" to cold.retainedCharacters, "dbBytes" to Files.size(db),
                        "jvmHeapUsedBytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
                        "processRssSampleBytes" to rss()))
                }
                val toggleReads = reads
                val toggleTokens = activeStore!!.tokenizations
                val toggleStart = System.nanoTime()
                repeat(3) {
                    check(cold.search("ERR_KITE_715", sessions, SearchSources.DEFAULT).isEmpty())
                    check(cold.search("ERR_KITE_715", sessions, SearchSources.ALL).isNotEmpty())
                }
                val toggleMs = ms(toggleStart) / 6
                val toggleSourceReads = reads - toggleReads
                val toggleTokenizations = activeStore!!.tokenizations - toggleTokens
                check(activeStore!!.tokenizations == toggleTokens)
                val exportStart = System.nanoTime()
                cold.export(snapshot)
                val exportMs = ms(exportStart)
                val passages = activeStore!!.passageCount
                cold.close()
                reads = 0
                val reopenStart = System.nanoTime()
                index(db).use { check(it.search("Harbour", sessions).isNotEmpty()); check(activeStore!!.tokenizations == 0L) }
                val reopenMs = ms(reopenStart)
                val reopenReads = reads
                check(reopenReads == 0)
                reads = 0
                var validationMs = 0.0
                val importStart = System.nanoTime()
                index(directory.resolve("fresh.sqlite")).use {
                    check(it.importSnapshot(snapshot) == count)
                    val validationStart = System.nanoTime()
                    check(it.search("Harbour", sessions).isNotEmpty())
                    validationMs = ms(validationStart)
                    check(activeStore!!.tokenizations == 0L)
                }
                val importMs = ms(importStart) - validationMs
                check(reads == 0)
                val counts = JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { c -> c.createStatement().use { statement ->
                    statement.executeQuery("SELECT field,count(*) FROM associations GROUP BY field").use { rows ->
                        buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
                    }
                } }
                report(mapOf("event" to "sourceReuse", "run" to run, "sessions" to count,
                    "categoryRows" to counts, "sharedPassages" to passages, "toggleMeanMs" to toggleMs,
                    "toggleSourceReads" to toggleSourceReads,
                    "toggleTokenizations" to toggleTokenizations, "exportMs" to exportMs, "reopenAndQueryMs" to reopenMs,
                    "reopenSourceReads" to reopenReads, "reopenTokenizations" to 0, "importMs" to importMs,
                    "importValidationAndQueryMs" to validationMs, "importSourceReads" to reads, "importTokenizations" to 0))
            } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    @Test fun boundedLongToolHistory() {
        assumeTrue(System.getenv("SESHLOG_LEXICAL_BENCH") == "1")
        val template = SourceRetrievalEvaluation.corpus().sessions.first()
        val tool = "diagnostic needle ".repeat(220)
        val entries = (0 until 100).map { i -> ConversationEntry(ConversationMessage(Role.USER, tool, null), "tool:$i", EntryKind.TOOL_RESULT) } +
            ConversationEntry(ConversationMessage(Role.USER, "needle user request", null), "user")
        var reads = 0
        var store: LexicalSearchStore? = null
        ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { reads++; entries },
            storeFactory = { LexicalSearchStore.open().also { store = it } }).use { index ->
            val start = System.nanoTime()
            index.search("needle", listOf(template))
            val buildMs = ms(start)
            for (sources in listOf(SearchSources.DEFAULT, SearchSources.ALL)) {
                val samples = mutableListOf<Double>()
                val beforeReads = reads
                val beforeTokens = store!!.tokenizations
                repeat(12) {
                    val queryStart = System.nanoTime()
                    val hit = index.search("needle", listOf(template), sources).single()
                    check(!hit.partial)
                    if (sources == SearchSources.DEFAULT) check(hit.primary.source == SearchMatchSource.USER)
                    samples += ms(queryStart)
                }
                samples.sort()
                check(store!!.tokenizations == beforeTokens)
                report(mapOf("event" to "sourceLongTool", "mode" to sources.summary, "buildMs" to buildMs,
                    "retainedSourceChars" to index.retainedCharacters, "sharedPassages" to store!!.passageCount,
                    "warmP50Ms" to samples[samples.size / 2], "warmP95Ms" to samples[ceil(samples.size * .95).toInt() - 1],
                    "warmSourceReads" to reads - beforeReads, "warmTokenizations" to store!!.tokenizations - beforeTokens))
            }
        }
    }

    private fun rss(): Long? = try {
        val process = ProcessBuilder("/bin/ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim().toLongOrNull() }
        process.waitFor()
        value?.times(1024)
    } catch (_: Exception) { null }
}
