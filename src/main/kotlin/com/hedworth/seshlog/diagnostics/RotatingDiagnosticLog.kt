package com.hedworth.seshlog.diagnostics

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One writer owns these files. Each record is complete UTF-8 JSON followed by a newline. */
internal class RotatingDiagnosticFile(
    val directory: Path,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val backups: Int = 3,
) {
    val current: Path = directory.resolve("sessions.jsonl")
    init { require(maxBytes > 0 && backups >= 1) }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maxBytes) { "Diagnostic record exceeds file limit" }
        Files.createDirectories(directory)
        if (Files.exists(current) && Files.size(current) + bytes.size > maxBytes) {
            Files.deleteIfExists(directory.resolve("sessions.$backups.jsonl"))
            for (i in backups - 1 downTo 1) {
                val from = directory.resolve("sessions.$i.jsonl")
                if (Files.exists(from)) Files.move(from, directory.resolve("sessions.${i + 1}.jsonl"), REPLACE_EXISTING)
            }
            Files.move(current, directory.resolve("sessions.1.jsonl"), REPLACE_EXISTING)
        }
        Files.write(current, bytes, CREATE, APPEND)
    }
}

/** Bounded, non-blocking submission from EDT; all file I/O runs on one daemon worker. */
internal class AsyncDiagnosticLog(
    private val write: (ByteArray) -> Unit,
    capacity: Int = 256,
    private val onFailure: (Throwable) -> Unit = {},
    private val environment: Map<String, Any?> = emptyMap(),
) : AutoCloseable {
    private val gson = Gson()
    private val run = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val lost = AtomicLong()
    @Volatile private var failed = false
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, "Seshlog session diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    val trace = DiagnosticTrace { event, fields ->
        val bytes = encode(event, fields)
        try {
            executor.execute {
                var dropped = lost.getAndSet(0)
                try {
                    if (dropped > 0) {
                        write(encode("diagnostic_gap", mapOf("lostRecords" to dropped)))
                        dropped = 0
                    }
                    write(bytes)
                    failed = false
                } catch (error: Exception) {
                    lost.addAndGet(dropped + 1)
                    if (!failed) runCatching { onFailure(error) }
                    failed = true
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { lost.incrementAndGet() }
    }

    private fun encode(event: String, fields: Map<String, Any?>): ByteArray {
        val envelope = linkedMapOf<String, Any?>("time" to Instant.now().toString(), "run" to run,
            "seq" to sequence.incrementAndGet(), "event" to event.take(120), "fields" to safe(fields))
        if (environment.isNotEmpty()) envelope["env"] = safe(environment)
        var text = gson.toJson(envelope)
        if (text.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            envelope["fields"] = mapOf("truncated" to true)
            text = gson.toJson(envelope)
        }
        return (text + "\n").toByteArray(Charsets.UTF_8)
    }

    // Do not let an accidental Session/Throwable/ProcessRecord.toString serialize content.
    private fun safe(value: Any?, depth: Int = 0): Any? = when {
        depth > 4 -> "<truncated>"
        value == null || value is Boolean || value is Number -> value
        value is String -> if (value.length > 240) value.take(220) + "<truncated>" else value
        value is Enum<*> -> value.name
        value is Map<*, *> -> value.entries.take(64).associate { (k, v) -> (k as? String).orEmpty().take(80) to safe(v, depth + 1) } +
            if (value.size > 64) mapOf("_truncatedEntries" to value.size - 64) else emptyMap()
        value is Collection<*> -> value.take(100).map { safe(it, depth + 1) } + if (value.size > 100) listOf("<truncated:${value.size}>") else emptyList()
        else -> "<unsupported>"
    }

    /** Worker/test use only. A failed or saturated writer returns false, never blocks indefinitely. */
    fun flush(timeoutMillis: Long = 5_000): Boolean {
        val done = CountDownLatch(1)
        return try {
            // Include any queue-overflow report before the barrier.
            trace.event("flush")
            executor.execute { done.countDown() }
            done.await(timeoutMillis, TimeUnit.MILLISECONDS) && !failed && lost.get() == 0L
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    override fun close() { executor.shutdown() }
}
