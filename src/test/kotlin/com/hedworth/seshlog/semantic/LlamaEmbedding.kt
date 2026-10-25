package com.hedworth.seshlog.semantic

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local CLI over anonymous pipes: neither prompts nor vectors are written to disk or argv. */
internal class LlamaEmbedding(private val executable: String, private val model: Path) {
    data class Result(val vectors: List<FloatArray>, val elapsedMs: Long, val peakRssBytes: Long?)
    init { require(Files.isRegularFile(model)) { "Set SESHLOG_EMBED_MODEL to a local GGUF file" } }

    fun encode(prompts: List<String>): Result {
        if (prompts.isEmpty()) return Result(emptyList(), 0, null)
        require(prompts.size <= 4096) { "Split large experiments into bounded batches" }
        require(prompts.all { it.toByteArray(Charsets.UTF_8).size <= 480 }) { "Input exceeds the prototype token budget" }
        val separator = "<seshlog-${UUID.randomUUID()}>"
        val command = listOf(
            executable, "--model", model.toAbsolutePath().toString(), "--offline", "--device", "none",
            "--threads", "4", "--ctx-size", "512", "--batch-size", "512", "--ubatch-size", "512",
            "--pooling", "mean", "--embd-normalize", "2", "--embd-output-format", "array",
            "--embd-separator", separator, "--no-escape", "--log-verbosity", "0", "--file", "/dev/stdin",
        )
        val measureRss = System.getProperty("os.name").startsWith("Mac")
        val builder = ProcessBuilder(if (measureRss) listOf("/usr/bin/time", "-l") + command else command)
        // Do not inherit llama logging/download overrides from a developer's shell.
        builder.environment().keys.removeIf { it.startsWith("LLAMA_") || it == "HF_TOKEN" }
        val start = System.nanoTime()
        val process = builder.start()
        val workers = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val writer = workers.submit {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompts.joinToString(separator)) }
            }
            val reader = workers.submit<String> {
                process.inputStream.use { stream ->
                    val limit = prompts.size * 24_000 + 4096
                    val bytes = stream.readNBytes(limit + 1)
                    check(bytes.size <= limit) { "Embedding output exceeded its bound" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            val errors = workers.submit<String> {
                // Drain stderr without persisting it or including it in any exception.
                process.errorStream.bufferedReader().use { input ->
                    var peak = ""
                    input.forEachLine { line ->
                        if (line.trim().matches(Regex("[0-9]+\\s+maximum resident set size"))) peak = line.trim().substringBefore(' ')
                    }
                    peak
                }
            }
            check(process.waitFor(5, TimeUnit.MINUTES)) { "Local embedding process timed out" }
            check(process.exitValue() == 0) { "Local embedding process failed (exit ${process.exitValue()}); stderr suppressed to protect session content" }
            writer.get(5, TimeUnit.SECONDS)
            val output = reader.get(5, TimeUnit.SECONDS)
            // Parsing exceptions can quote input. Report a fixed failure rather than native output.
            val vectors = try {
                JsonParser.parseString(output).asJsonArray.map { row ->
                    row.asJsonArray.map { it.asFloat }.toFloatArray()
                }
            } catch (_: Exception) { error("Invalid embedding response; raw output suppressed") }
            check(vectors.size == prompts.size && vectors.all { it.size == 768 }) { "Unexpected embedding shape" }
            return Result(vectors.map { SemanticPrototype.normalize(it) },
                (System.nanoTime() - start) / 1_000_000, errors.get(5, TimeUnit.SECONDS).toLongOrNull())
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
            workers.shutdownNow()
        }
    }
}
