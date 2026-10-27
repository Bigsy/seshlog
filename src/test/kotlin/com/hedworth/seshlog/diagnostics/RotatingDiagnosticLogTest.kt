package com.hedworth.seshlog.diagnostics

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RotatingDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `disabled logging creates no files and can be enabled again without replay`() {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val files = RotatingDiagnosticFile(temporary.root.toPath().resolve("disabled"))
        AsyncDiagnosticLog(files::write, enabled = enabled::get).use { log ->
            log.trace.event("disabled_event")
            assertTrue(log.flush())
            assertFalse(Files.exists(files.directory))
            enabled.set(true)
            log.trace.event("enabled_event")
            assertTrue(log.flush())
            val before = Files.readString(files.current)
            enabled.set(false)
            log.trace.event("disabled_again")
            assertTrue(log.flush())
            assertEquals(before, Files.readString(files.current))
            assertTrue(before.contains("enabled_event"))
            assertFalse(before.contains("disabled_event"))
        }
    }

    @Test fun `disabling recording drops queued writes but allows an in-flight write to finish`() {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        AsyncDiagnosticLog({ bytes ->
            val line = bytes.toString(Charsets.UTF_8)
            if ("in_flight" in line) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            lines += line
        }, enabled = enabled::get).use { log ->
            try {
                log.trace.event("in_flight")
                assertTrue(started.await(5, TimeUnit.SECONDS))
                log.trace.event("queued_event")
                enabled.set(false)
            } finally { release.countDown() }
            assertTrue(log.flush())
            assertEquals(1, lines.size)
            enabled.set(true)
            log.trace.event("resumed_event")
            assertTrue(log.flush())
            assertTrue(lines.any { "resumed_event" in it })
            assertFalse(lines.any { "queued_event" in it })
        }
    }

    @Test fun `rotation bounds disk usage and preserves chronological records across restarts`() {
        val directory = temporary.root.toPath().resolve("logs")
        val file = RotatingDiagnosticFile(directory, maxBytes = 60, backups = 3)
        repeat(50) { file.write("{\"record\":$it}\n".toByteArray()) }
        RotatingDiagnosticFile(directory, maxBytes = 60, backups = 3).write("{\"record\":50}\n".toByteArray())
        val files = (3 downTo 1).map { directory.resolve("sessions.$it.jsonl") } + listOf(file.current)
        assertTrue(files.all { Files.size(it) <= 60 })
        assertEquals(4L, Files.list(directory).use { it.count() })
        val records = files.flatMap { Files.readAllLines(it) }.map { JsonParser.parseString(it).asJsonObject["record"].asInt }
        assertEquals((records.first()..50).toList(), records)
        assertThrows(IllegalArgumentException::class.java) { file.write(ByteArray(61)) }
    }

    @Test fun `concurrent producers write complete lines off the calling thread`() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        AsyncDiagnosticLog({ lines += it.toString(Charsets.UTF_8); threads += Thread.currentThread().name }, capacity = 512).use { log ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                repeat(4) { worker -> pool.submit { repeat(50) { log.trace.event("poll", "worker" to worker, "value" to it) } } }
                pool.shutdown()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(log.flush())
            } finally { pool.shutdownNow() }
        }
        val records = lines.map { JsonParser.parseString(it).asJsonObject }
        assertEquals(200, records.count { it["event"].asString == "poll" })
        assertEquals(records.size, records.map { it["seq"].asLong }.distinct().size)
        assertEquals(1, records.map { it["run"].asString }.distinct().size)
        assertEquals(setOf("Seshlog session diagnostics"), threads)
        assertTrue(lines.all { it.endsWith('\n') && it.count { c -> c == '\n' } == 1 })
    }

    @Test fun `slow disk never blocks producers and overflow is visible`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        AsyncDiagnosticLog({ bytes ->
            val line = bytes.toString(Charsets.UTF_8)
            if ("blocker" in line) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            lines += line
            if ("queued_two" in line) drained.countDown()
        }, capacity = 2).use { log ->
            try {
                log.trace.event("blocker")
                assertTrue(started.await(5, TimeUnit.SECONDS))
                log.trace.event("queued_one")
                log.trace.event("queued_two")
                // These submissions complete even though the writer cannot make progress.
                repeat(100) { log.trace.event("overflow") }
            } finally { release.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertTrue(log.flush())
        }
        val gaps = lines.map { JsonParser.parseString(it).asJsonObject }.filter { it["event"].asString == "diagnostic_gap" }
        assertEquals(100L, gaps.sumOf { it.getAsJsonObject("fields")["lostRecords"].asLong })
    }

    @Test fun `write failures do not escape and recovery reports lost records`() {
        var unavailable = true
        val lines = Collections.synchronizedList(mutableListOf<String>())
        var warnings = 0
        AsyncDiagnosticLog({ if (unavailable) throw IOException("private path") else lines += it.toString(Charsets.UTF_8) }, onFailure = { warnings++ }).use { log ->
            log.trace.event("lost")
            assertFalse(log.flush())
            unavailable = false
            log.trace.event("recovered")
            assertTrue(log.flush())
        }
        assertEquals(1, warnings)
        assertTrue(lines.any { "diagnostic_gap" in it })
        assertTrue(lines.any { "recovered" in it })
        assertFalse(lines.any { "private path" in it })
    }

    @Test fun `only explicit bounded metadata is serialized and failures omit messages`() {
        val lines = mutableListOf<String>()
        AsyncDiagnosticLog({ lines += it.toString(Charsets.UTF_8) }).use { log ->
            val secret = "secret title and command"
            log.trace.event("metadata", "session" to DiagnosticRef.of(secret), "unexpected" to object { override fun toString() = secret },
                "values" to List(500) { it })
            log.trace.failure("failure", IOException(secret, IllegalStateException(secret)))
            log.trace.record("large", (1..64).associate { "x".repeat(80) + it to "😀".repeat(240) })
            assertTrue(log.flush())
        }
        assertTrue(lines.all { it.toByteArray().size <= 16 * 1024 + 1 })
        assertFalse(lines.joinToString().contains("secret title and command"))
        assertTrue(lines.any { "truncated" in it })
        assertTrue(lines.any { "java.io.IOException" in it })
        assertEquals(DiagnosticRef.of("same"), DiagnosticRef.of("same"))
        assertEquals("ba7816bf8f01cfea414140de", DiagnosticRef.of("abc"))
        assertNotEquals(DiagnosticRef.of("same"), DiagnosticRef.of("different"))
        // Broken diagnostic consumers cannot interfere with tracking.
        DiagnosticTrace { _, _ -> throw IOException("synthetic") }.event("safe")
    }
}
