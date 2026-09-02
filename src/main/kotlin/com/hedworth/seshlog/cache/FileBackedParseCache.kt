package com.hedworth.seshlog.cache

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** `(size, mtime)` of a file: cheap to compute and enough to tell whether an append-only transcript changed. */
data class FileStamp(val size: Long, val mtimeMillis: Long) {
    companion object {
        fun of(attrs: BasicFileAttributes) = FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis())

        /** Null when the file cannot be stat'ed (vanished, permissions). */
        fun of(path: Path?): FileStamp? {
            if (path == null) return null
            return try {
                of(Files.readAttributes(path, BasicFileAttributes::class.java))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Per-file parse cache shared by the file-backed providers: a transcript is parsed once and its
 * [T] reused for as long as its size and mtime are unchanged. Optionally mirrored to disk through
 * an [InfoStore], so the first scan after an IDE restart costs a stat per file instead of a parse.
 *
 * @param store     how to (de)serialise entries; only used when [cacheFile] is set
 * @param cacheFile where to persist between IDE runs (null: in-memory only)
 * @param parse     parses one transcript; may throw, in which case the file is skipped for this scan
 */
class FileBackedParseCache<T>(
    private val store: InfoStore<T>,
    private val cacheFile: Path?,
    private val parse: (Path) -> T,
) {
    private val LOG = logger<FileBackedParseCache<*>>()

    private val cache = ConcurrentHashMap<Path, InfoStore.Entry<T>>()

    /** Set when [cache] differs from what is on disk in [cacheFile]. */
    private val dirty = AtomicBoolean(false)

    init {
        if (cacheFile != null) {
            val loaded = store.load(cacheFile)
            cache.putAll(loaded)
            LOG.debug("Loaded ${loaded.size} cached entries from $cacheFile")
        }
    }

    /** Forget files that no longer exist, so the persisted cache does not grow forever. */
    fun retainOnly(live: Set<Path>) {
        if (cache.keys.retainAll(live)) dirty.set(true)
    }

    /** Cached [T] for [path] if its stamp matches [attrs], else a fresh parse; null when parsing fails. */
    fun get(path: Path, attrs: BasicFileAttributes): T? {
        val stamp = FileStamp.of(attrs)
        cache[path]?.let { if (it.size == stamp.size && it.mtimeMillis == stamp.mtimeMillis) return it.info }
        val info = try {
            parse(path)
        } catch (e: Exception) {
            LOG.debug("Failed to parse $path", e)
            return null
        }
        cache[path] = InfoStore.Entry(stamp.size, stamp.mtimeMillis, info)
        dirty.set(true)
        return info
    }

    /** Write the cache to [cacheFile] if anything changed since the last persist. */
    fun persist() {
        if (cacheFile == null || !dirty.compareAndSet(true, false)) return
        store.save(cacheFile, HashMap(cache))
    }
}
