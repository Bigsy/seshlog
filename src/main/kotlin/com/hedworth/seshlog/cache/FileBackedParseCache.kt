package com.hedworth.seshlog.cache

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Cheap file identity metadata used to distinguish unchanged, appended, and replaced transcripts. */
data class FileStamp(val size: Long, val mtimeMillis: Long, val fileIdentity: String? = null) {
    companion object {
        fun of(attrs: BasicFileAttributes) = FileStamp(
            attrs.size(),
            attrs.lastModifiedTime().toMillis(),
            attrs.fileKey()?.toString(),
        )

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
    private val incrementalParse: ((Path, T?, Long) -> IncrementalParseResult<T>)? = null,
    private val onReadFailure: (Path, Exception) -> Unit = { _, _ -> },
    private val persistScheduler: CachePersistScheduler = CachePersistScheduler.default(),
    private val persistDelayMillis: Long = 1_000,
) {
    private val LOG = logger<FileBackedParseCache<*>>()

    private val cache = ConcurrentHashMap<Path, InfoStore.Entry<T>>()

    /** Set when [cache] differs from what is on disk in [cacheFile]. */
    private val dirty = AtomicBoolean(false)
    private val persistLock = Any()
    private var scheduledPersist: CacheScheduledTask? = null

    init {
        if (cacheFile != null) {
            val loaded = store.load(cacheFile)
            cache.putAll(loaded)
            LOG.debug("Loaded ${loaded.size} cached entries from $cacheFile")
        }
    }

    /** Forget files that no longer exist, so the persisted cache does not grow forever. */
    fun retainOnly(live: Set<Path>) {
        if (cache.keys.retainAll(live)) markDirty()
    }

    /** Cached [T] for [path] if its stamp matches [attrs], else a fresh parse; null when parsing fails. */
    fun get(path: Path, attrs: BasicFileAttributes): T? {
        val stamp = FileStamp.of(attrs)
        val previous = cache[path]
        previous?.let { if (it.matches(stamp)) return it.info }
        val firstLineHash = incrementalParse?.let { IncrementalJsonl.firstLineHash(path) }
        val parsed = try {
            if (incrementalParse == null) {
                Parsed(parse(path), stamp.size)
            } else {
                val offset = previous?.parsedOffset
                val canResume = previous != null && offset != null && previous.size <= stamp.size &&
                    offset <= stamp.size && previous.fileIdentity == stamp.fileIdentity &&
                    firstLineHash != null && previous.firstLineHash == firstLineHash
                val incremental = incrementalParse(path, if (canResume) previous.info else null, if (canResume) offset!! else 0L)
                if (incremental.completedOffset < stamp.size) {
                    // A final unterminated line is still a valid record for the normal parser.
                    // Keep its metadata visible, but do not checkpoint it: when the writer later
                    // appends a newline, the full parse must see that record exactly once.
                    Parsed(parse(path), null)
                } else {
                    Parsed(incremental.info, incremental.completedOffset)
                }
            }
        } catch (e: Exception) {
            onReadFailure(path, e)
            LOG.debug("Failed to parse $path", e)
            return null
        }
        cache[path] = InfoStore.Entry(stamp.size, stamp.mtimeMillis, parsed.info, parsed.offset, firstLineHash, stamp.fileIdentity)
        markDirty()
        return parsed.info
    }

    /** Write the cache to [cacheFile] if anything changed since the last persist. */
    fun persist() {
        synchronized(persistLock) {
            val scheduled = scheduledPersist
            scheduledPersist = null
            scheduled?.cancel()
            if (cacheFile == null || !dirty.compareAndSet(true, false)) return
            // Keep the atomic temp-file write under the same lock as scheduling. A timer callback
            // and SessionIndex.dispose() can otherwise race and overwrite each other's temp file.
            store.save(cacheFile, HashMap(cache))
        }
    }

    private fun markDirty() {
        dirty.set(true)
        if (cacheFile == null) return
        synchronized(persistLock) {
            if (scheduledPersist == null) {
                scheduledPersist = persistScheduler.schedule(persistDelayMillis) { persist() }
            }
        }
    }

    private data class Parsed<T>(val info: T, val offset: Long?)

    private fun InfoStore.Entry<T>.matches(stamp: FileStamp): Boolean =
        size == stamp.size && mtimeMillis == stamp.mtimeMillis && fileIdentity == stamp.fileIdentity
}
