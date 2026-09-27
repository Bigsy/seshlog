package com.hedworth.seshlog.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class FileBackedParseCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = InfoStore<Int>(
        version = 1,
        write = { value, json -> json.addProperty("value", value) },
        read = { json -> json.get("value").asInt },
    )

    private fun attrs(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java)

    private fun cache(
        path: Path,
        fullParses: MutableList<Path>,
        previousValues: MutableList<Int?>,
        cacheFile: Path? = null,
        scheduler: CachePersistScheduler = CachePersistScheduler.default(),
    ) =
        FileBackedParseCache(
            store = store,
            cacheFile = cacheFile,
            parse = { file ->
                fullParses.add(file)
                Files.readString(file).lineSequence().count { it.isNotEmpty() }
            },
            incrementalParse = { file, previous, offset ->
                previousValues += previous
                var count = previous ?: 0
                val completed = IncrementalJsonl.read(file, offset) { line ->
                    if (line.isNotEmpty()) count++
                }
                IncrementalParseResult(count, completed)
            },
            persistScheduler = scheduler,
        )

    private class ManualScheduler : CachePersistScheduler {
        var schedules = 0
            private set

        override fun schedule(delayMillis: Long, task: () -> Unit): CacheScheduledTask {
            schedules++
            return CacheScheduledTask { }
        }
    }

    @Test
    fun `valid final record is visible and later newline does not double count it`() {
        val path = tmp.newFile("transcript.jsonl").toPath()
        Files.writeString(path, "first\nfinal")
        val fullParses = mutableListOf<Path>()
        val previousValues = mutableListOf<Int?>()
        val cache = cache(path, fullParses, previousValues)

        assertEquals(2, cache.get(path, attrs(path)))
        assertEquals(1, fullParses.size)

        Files.writeString(path, "\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(2, cache.get(path, attrs(path)))
        assertEquals(1, fullParses.size)
        assertEquals(listOf<Int?>(null, null), previousValues)
    }

    @Test
    fun `append resumes complete records but truncation and changed first line restart`() {
        val path = tmp.newFile("transcript.jsonl").toPath()
        Files.writeString(path, "one\n")
        val fullParses = mutableListOf<Path>()
        val previousValues = mutableListOf<Int?>()
        val cache = cache(path, fullParses, previousValues)

        assertEquals(1, cache.get(path, attrs(path)))
        Files.writeString(path, "two\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(2, cache.get(path, attrs(path)))
        assertEquals(listOf<Int?>(null, 1), previousValues)

        Files.writeString(path, "one\n")
        assertEquals(1, cache.get(path, attrs(path)))
        assertEquals(null, previousValues.last())

        Files.writeString(path, "six\n")
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(attrs(path).lastModifiedTime().toMillis() + 1_000))
        assertEquals(1, cache.get(path, attrs(path)))
        assertEquals(null, previousValues.last())
    }

    @Test
    fun `replacement with equal prefix and stamp is rejected when file identity changes`() {
        val path = tmp.newFile("transcript.jsonl").toPath()
        Files.writeString(path, "same\nold\n")
        val before = attrs(path).fileKey()
        assumeNotNull(before)
        val fullParses = mutableListOf<Path>()
        val previousValues = mutableListOf<Int?>()
        val cache = cache(path, fullParses, previousValues)
        assertEquals(2, cache.get(path, attrs(path)))

        val mtime = Files.getLastModifiedTime(path)
        Files.delete(path)
        Files.writeString(path, "same\nnew\n")
        Files.setLastModifiedTime(path, mtime)
        val after = attrs(path).fileKey()
        assumeNotNull(after)
        assumeTrue(before != after)

        assertEquals(2, cache.get(path, attrs(path)))
        assertNull(previousValues.last())
    }

    @Test
    fun `first line hash and file identity are metadata only`() {
        val path = Path.of("/synthetic/transcript.jsonl")
        val entry = InfoStore.Entry(12, 34, 2, 12, "hash", "file-key")
        val json = store.toJson(mapOf(path to entry))
        assertTrue(json.contains("\"offset\":12"))
        assertTrue(json.contains("\"firstLineHash\":\"hash\""))
        assertTrue(json.contains("\"fileIdentity\":\"file-key\""))
        val keys = com.google.gson.JsonParser.parseString(json).asJsonObject
            .getAsJsonArray("entries")[0].asJsonObject.keySet()
        assertEquals(setOf("path", "size", "mtime", "offset", "firstLineHash", "fileIdentity", "value"), keys)
        assertEquals(entry, store.fromJson(json).getValue(path))
    }

    @Test
    fun `oversized first line does not create an unsafe resume checkpoint`() {
        val path = tmp.newFile("large.jsonl").toPath()
        Files.writeString(path, "x".repeat(70_000) + "\n")
        val fullParses = mutableListOf<Path>()
        val previousValues = mutableListOf<Int?>()
        val cache = cache(path, fullParses, previousValues)

        assertEquals(1, cache.get(path, attrs(path)))
        Files.writeString(path, "next\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(2, cache.get(path, attrs(path)))
        assertEquals(listOf<Int?>(null, null), previousValues)
    }

    @Test
    fun `persistence is coalesced and flush writes metadata`() {
        val path = tmp.newFile("persisted.jsonl").toPath()
        Files.writeString(path, "one\n")
        val fullParses = mutableListOf<Path>()
        val previousValues = mutableListOf<Int?>()
        val scheduler = ManualScheduler()
        val cacheFile = tmp.root.toPath().resolve("cache.json")
        val cache = cache(path, fullParses, previousValues, cacheFile, scheduler)

        cache.get(path, attrs(path))
        cache.get(path, attrs(path))
        assertEquals(1, scheduler.schedules)
        assertTrue(!Files.exists(cacheFile))

        cache.persist()
        assertTrue(Files.isRegularFile(cacheFile))
        Files.writeString(path, "two\n", java.nio.file.StandardOpenOption.APPEND)
        cache.get(path, attrs(path))
        assertEquals(2, scheduler.schedules)
    }

    @Test
    fun `UTF-8 code point split across append is decoded after the newline arrives`() {
        val path = tmp.newFile("utf8.jsonl").toPath()
        val prefix = "first\n".toByteArray()
        val character = "é".toByteArray()
        Files.write(path, prefix + character.copyOfRange(0, 1))

        val firstOffset = IncrementalJsonl.read(path) { }
        assertEquals(prefix.size.toLong(), firstOffset)

        Files.write(
            path,
            character.copyOfRange(1, character.size) + "\n".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        val lines = mutableListOf<String>()
        IncrementalJsonl.read(path, firstOffset) { lines += it }
        assertEquals(listOf("é"), lines)
    }
}
