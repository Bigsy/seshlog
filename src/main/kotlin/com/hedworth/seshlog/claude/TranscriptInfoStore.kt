package com.hedworth.seshlog.claude

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.claude.TranscriptParser.string
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * On-disk copy of the provider's parsed-transcript cache, so the first scan after IDE start
 * costs a stat per transcript instead of a full parse. Lives in IntelliJ's system directory,
 * never in `~/.claude`.
 *
 * Format: `{"version": 1, "entries": [{"path", "size", "mtime", ...TranscriptInfo fields}]}`.
 * Any problem reading (missing file, wrong version, bad JSON) yields an empty map — the cache
 * is only ever an optimisation.
 */
object TranscriptInfoStore {
    private val LOG = logger<TranscriptInfoStore>()
    const val VERSION = 2

    data class Entry(val size: Long, val mtimeMillis: Long, val info: TranscriptInfo)

    fun load(file: Path): Map<Path, Entry> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            fromJson(Files.readString(file, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            LOG.debug("Ignoring unreadable index cache $file", e)
            emptyMap()
        }
    }

    /** Writes atomically (temp file + move) so a crash mid-write never leaves a truncated cache. */
    fun save(file: Path, entries: Map<Path, Entry>) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, toJson(entries), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOG.debug("Cannot write index cache $file", e)
        }
    }

    fun toJson(entries: Map<Path, Entry>): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val array = JsonArray()
        for ((path, e) in entries.entries.sortedBy { it.key.toString() }) {
            val o = JsonObject()
            o.addProperty("path", path.toString())
            o.addProperty("size", e.size)
            o.addProperty("mtime", e.mtimeMillis)
            val i = e.info
            o.addProperty("sessionId", i.sessionId)
            o.addProperty("cwd", i.cwd)
            o.addProperty("gitBranch", i.gitBranch)
            o.addProperty("claudeVersion", i.version)
            o.addProperty("promptTitle", i.promptTitle)
            o.addProperty("aiTitle", i.aiTitle)
            o.addProperty("customTitle", i.customTitle)
            i.startedAt?.let { o.addProperty("startedAt", it.toEpochMilli()) }
            o.addProperty("promptCount", i.promptCount)
            array.add(o)
        }
        root.add("entries", array)
        return GSON.toJson(root)
    }

    fun fromJson(json: String): Map<Path, Entry> {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        if (root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt != VERSION) return emptyMap()
        val array = root.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val result = LinkedHashMap<Path, Entry>(array.size())
        for (el in array) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val path = o.string("path")?.let { runCatching { Paths.get(it) }.getOrNull() } ?: continue
            val size = o.long("size") ?: continue
            val mtime = o.long("mtime") ?: continue
            val info = TranscriptInfo(
                sessionId = o.string("sessionId"),
                cwd = o.string("cwd"),
                gitBranch = o.string("gitBranch"),
                version = o.string("claudeVersion"),
                promptTitle = o.string("promptTitle"),
                aiTitle = o.string("aiTitle"),
                customTitle = o.string("customTitle"),
                startedAt = o.long("startedAt")?.let { Instant.ofEpochMilli(it) },
                promptCount = o.long("promptCount")?.toInt() ?: 0,
            )
            result[path] = Entry(size, mtime, info)
        }
        return result
    }

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private val GSON = GsonBuilder().disableHtmlEscaping().create()
}
