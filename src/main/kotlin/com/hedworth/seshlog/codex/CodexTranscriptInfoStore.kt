package com.hedworth.seshlog.codex

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hedworth.seshlog.codex.CodexTranscriptParser.string
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Persistent metadata-only cache for parsed Codex rollouts. */
object CodexTranscriptInfoStore {
    private val LOG = logger<CodexTranscriptInfoStore>()
    const val VERSION = 1

    data class Entry(val size: Long, val mtimeMillis: Long, val info: CodexTranscriptInfo)

    fun load(file: Path): Map<Path, Entry> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            fromJson(Files.readString(file, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            LOG.debug("Ignoring unreadable Codex index cache $file", e)
            emptyMap()
        }
    }

    fun save(file: Path, entries: Map<Path, Entry>) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, toJson(entries), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOG.debug("Cannot write Codex index cache $file", e)
        }
    }

    fun toJson(entries: Map<Path, Entry>): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val array = JsonArray()
        for ((path, entry) in entries.entries.sortedBy { it.key.toString() }) {
            val info = entry.info
            val obj = JsonObject()
            obj.addProperty("path", path.toString())
            obj.addProperty("size", entry.size)
            obj.addProperty("mtime", entry.mtimeMillis)
            obj.addProperty("sessionId", info.sessionId)
            obj.addProperty("cwd", info.cwd)
            obj.addProperty("gitBranch", info.gitBranch)
            obj.addProperty("promptTitle", info.promptTitle)
            info.startedAt?.let { obj.addProperty("startedAt", it.toEpochMilli()) }
            obj.addProperty("promptCount", info.promptCount)
            array.add(obj)
        }
        root.add("entries", array)
        return GSON.toJson(root)
    }

    fun fromJson(json: String): Map<Path, Entry> {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        if (root.long("version")?.toInt() != VERSION) return emptyMap()
        val array = root.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val result = LinkedHashMap<Path, Entry>(array.size())
        for (element in array) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val path = obj.string("path")?.let { runCatching { Paths.get(it) }.getOrNull() } ?: continue
            val size = obj.long("size") ?: continue
            val mtime = obj.long("mtime") ?: continue
            val info = CodexTranscriptInfo(
                sessionId = obj.string("sessionId"),
                cwd = obj.string("cwd"),
                gitBranch = obj.string("gitBranch"),
                promptTitle = obj.string("promptTitle"),
                startedAt = obj.long("startedAt")?.let(Instant::ofEpochMilli),
                promptCount = obj.long("promptCount")?.toInt() ?: 0,
            )
            result[path] = Entry(size, mtime, info)
        }
        return result
    }

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private val GSON = GsonBuilder().disableHtmlEscaping().create()
}
