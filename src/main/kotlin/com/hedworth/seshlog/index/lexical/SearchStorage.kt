package com.hedworth.seshlog.index.lexical

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Held for the entire lifetime: another IDE cannot replace/purge a live database. */
internal class SearchStoreLease
private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(path: Path): SearchStoreLease {
            val channel =
                FileChannel.open(
                    path.resolveSibling(path.fileName.toString() + ".lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            try {
                return SearchStoreLease(
                    channel,
                    channel.tryLock()
                        ?: error("Search index is in use by another IDE. Close it and retry."),
                )
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }
    }
}

internal fun privatePermissions(path: Path, directory: Boolean = false) {
    try {
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"),
        )
    } catch (_: UnsupportedOperationException) {
        /* ACLs are inherited on Windows. */
    }
}

private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }
}

fun searchHash(text: String): String =
    hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

/**
 * Portable identity hashes raw local sources without parsing or tokenizing them. Never follows
 * imported locators.
 */
fun sourceFingerprint(paths: List<Path>, cancelled: () -> Boolean = { false }): String? {
    if (paths.isEmpty() || paths.size > 2000 || cancelled()) return null
    return try {
        var sourceBytes = 0L
        val sizes =
            paths.map { path ->
                if (cancelled()) return null
                val size = Files.size(path)
                sourceBytes += size
                if (sourceBytes > 16_000_000) return null
                size
            }
        var readBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        for ((index, path) in paths.withIndex()) {
            if (index > 0) {
                digest.update(path.fileName.toString().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            val size = sizes[index]
            digest.update(size.toString().toByteArray())
            digest.update(0.toByte())
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (cancelled()) return null
                    val n = input.read(buffer)
                    if (n < 0) break
                    readBytes += n
                    if (readBytes > 16_000_000) return null
                    digest.update(buffer, 0, n)
                }
            }
        }
        hex(digest.digest())
    } catch (_: Exception) {
        null
    }
}
