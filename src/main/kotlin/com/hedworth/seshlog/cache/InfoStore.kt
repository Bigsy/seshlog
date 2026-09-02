package com.hedworth.seshlog.cache

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * On-disk copy of a provider's parsed-transcript cache, so the first scan after IDE start costs a
 * stat per transcript instead of a full parse. Lives in IntelliJ's system directory, never in the
 * agent's own data directory.
 *
 * Format: `{"version": N, "entries": [{"path", "size", "mtime", ...fields written by [write]}]}`.
 * Any problem reading (missing file, wrong version, bad JSON) yields an empty map — the cache is
 * only ever an optimisation. Providers subclass this with their own field list.
 *
 * @param version bumped whenever the per-entry fields change; other versions are dropped, not migrated
 * @param write   adds the fields of one [T] to an entry object
 * @param read    rebuilds a [T] from an entry object; missing fields should become defaults, never throw
 */
open class InfoStore<T>(
    val version: Int,
    private val write: (T, JsonObject) -> Unit,
    private val read: (JsonObject) -> T,
) {
    data class Entry<T>(val size: Long, val mtimeMillis: Long, val info: T)

    fun load(file: Path): Map<Path, Entry<T>> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            fromJson(Files.readString(file, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            LOG.debug("Ignoring unreadable index cache $file", e)
            emptyMap()
        }
    }

    /** Writes atomically (temp file + move) so a crash mid-write never leaves a truncated cache. */
    fun save(file: Path, entries: Map<Path, Entry<T>>) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, toJson(entries), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOG.debug("Cannot write index cache $file", e)
        }
    }

    fun toJson(entries: Map<Path, Entry<T>>): String {
        val root = JsonObject()
        root.addProperty("version", version)
        val array = JsonArray()
        for ((path, e) in entries.entries.sortedBy { it.key.toString() }) {
            val o = JsonObject()
            o.addProperty("path", path.toString())
            o.addProperty("size", e.size)
            o.addProperty("mtime", e.mtimeMillis)
            write(e.info, o)
            array.add(o)
        }
        root.add("entries", array)
        return GSON.toJson(root)
    }

    fun fromJson(json: String): Map<Path, Entry<T>> {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        if (root.long("version")?.toInt() != version) return emptyMap()
        val array = root.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val result = LinkedHashMap<Path, Entry<T>>(array.size())
        for (el in array) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val path = o.string("path")?.let { runCatching { Paths.get(it) }.getOrNull() } ?: continue
            val size = o.long("size") ?: continue
            val mtime = o.long("mtime") ?: continue
            result[path] = Entry(size, mtime, read(o))
        }
        return result
    }

    companion object {
        private val LOG = logger<InfoStore<*>>()
        private val GSON = GsonBuilder().disableHtmlEscaping().create()

        fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString

        fun JsonObject.long(name: String): Long? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    }
}
