package com.hedworth.seshlog.cache

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Result of a metadata-only JSONL parse. [completedOffset] ends after a complete newline. */
data class IncrementalParseResult<T>(val info: T, val completedOffset: Long)

/** Reads complete UTF-8 JSONL records without persisting their content. */
object IncrementalJsonl {
    private const val BUFFER_SIZE = 64 * 1024
    private const val FIRST_LINE_LIMIT = 64 * 1024

    fun firstLineHash(path: Path): String? = try {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            var done = false
            while (!done && bytes.size() < FIRST_LINE_LIMIT) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read <= 0) break
                buffer.flip()
                while (buffer.hasRemaining() && bytes.size() < FIRST_LINE_LIMIT) {
                    val value = buffer.get()
                    if (value == '\n'.code.toByte()) { done = true; break }
                    bytes.write(value.toInt())
                }
            }
            // A capped prefix is not an identity for an oversized first record. Returning null
            // makes the cache reparse rather than incorrectly resuming a replaced transcript.
            if (bytes.size() == 0 || !done) null else sha256(bytes.toByteArray())
        }
    } catch (_: Exception) { null }

    /** Calls [onLine] in order and returns the byte offset after the last complete line. */
    fun read(path: Path, startOffset: Long = 0, onLine: (String) -> Unit): Long {
        return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (startOffset < 0 || startOffset > size) return startOffset.coerceAtMost(size)
            channel.position(startOffset)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val line = ByteArrayOutputStream()
            var completed = startOffset
            var absolute = startOffset
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read <= 0) break
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val value = buffer.get()
                    absolute++
                    if (value == '\n'.code.toByte()) {
                        onLine(line.toString(StandardCharsets.UTF_8.name()))
                        line.reset()
                        completed = absolute
                    } else line.write(value.toInt())
                }
            }
            completed
        }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
