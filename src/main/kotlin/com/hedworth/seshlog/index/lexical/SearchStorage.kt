package com.hedworth.seshlog.index.lexical

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Held for the entire lifetime: another IDE cannot replace/purge a live database. */
internal class SearchStoreLease private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { channel.close() } }
    companion object {
        fun acquire(path: Path): SearchStoreLease {
            val channel = FileChannel.open(path.resolveSibling(path.fileName.toString()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
            try { return SearchStoreLease(channel, channel.tryLock() ?: error("Search index is in use by another IDE. Close it and retry.")) }
            catch (t: Throwable) { channel.close();throw t }
        }
    }
}
internal fun privatePermissions(path: Path, directory: Boolean = false) {
    try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")) }
    catch (_: UnsupportedOperationException) { /* ACLs are inherited on Windows. */ }
}
fun searchHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Portable identity hashes raw local sources without parsing or tokenizing them. Never follows imported locators. */
fun sourceFingerprint(paths: List<Path>): String? {
    if (paths.isEmpty()) return null
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in paths) {
            val size=Files.size(path)
            // Shared scan budget bounds work; an unvalidated tail cannot be claimed portable.
            if (size > 16_000_000) return null
            digest.update(size.toString().toByteArray());digest.update(0.toByte())
            Files.newInputStream(path).use { input ->
                val buffer=ByteArray(32*1024); var read=0L
                while (true) { val n=input.read(buffer);if(n<0)break;read+=n;if(read>16_000_000)return null;digest.update(buffer,0,n) }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
