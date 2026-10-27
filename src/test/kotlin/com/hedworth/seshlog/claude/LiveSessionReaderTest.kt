package com.hedworth.seshlog.claude

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class LiveSessionReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `denied first or identity read is incomplete while other live markers remain available`() {
        val dir = tmp.newFolder("marker-reads").toPath()
        val marker = dir.resolve("failed.json")
        val peer = dir.resolve("peer.json")
        val pid = ProcessHandle.current().pid()
        Files.writeString(marker, """{"pid":$pid,"sessionId":"failed"}""")
        Files.writeString(peer, """{"pid":$pid,"sessionId":"peer"}""")
        for (failAt in 1..2) {
            var reads = 0
            val scan = LiveSessionReader.readWithDiagnostics(dir, readText = { path ->
                if (path == marker && ++reads == failAt) throw java.nio.file.AccessDeniedException(path.toString())
                Files.readString(path)
            })
            assertTrue("Read $failAt must prevent a healthy scan", scan.problem != null)
            assertEquals(setOf("peer"), scan.sessions.keys)
        }
        val recovered = LiveSessionReader.readWithDiagnostics(dir)
        assertEquals(null, recovered.problem)
        assertEquals(setOf("failed", "peer"), recovered.sessions.keys)
    }

    @Test
    fun `removed markers remain normal but broken marker symlinks are incomplete`() {
        val dir = tmp.newFolder("marker-removal").toPath()
        val marker = dir.resolve("removed.json")
        val gone = LiveSessionReader.readWithDiagnostics(dir, listFiles = { listOf(marker) })
        assertEquals(null, gone.problem)
        assertTrue(gone.sessions.isEmpty())
        Files.createSymbolicLink(marker, dir.resolve("missing-target"))
        assertTrue(LiveSessionReader.readWithDiagnostics(dir).problem != null)
    }

    @Test
    fun `failed enumeration differs from an absent or successfully empty marker directory`() {
        val dir = tmp.newFolder("unreadable").toPath()
        for (failure in listOf(java.nio.file.AccessDeniedException(dir.toString()),
            java.io.UncheckedIOException(java.io.IOException("directory iteration failed")))) {
            val scan = LiveSessionReader.readWithDiagnostics(dir, listFiles = { throw failure })
            assertTrue(scan.sessions.isEmpty())
            assertTrue(scan.problem != null)
        }
        assertEquals(null, LiveSessionReader.readWithDiagnostics(dir).problem)
        assertEquals(null, LiveSessionReader.readWithDiagnostics(dir.resolve("absent")).problem)
    }

    @Test
    fun `a dangling live marker directory symlink is incomplete evidence`() {
        val dir = tmp.newFolder("broken-link").toPath()
        val link = Files.createSymbolicLink(dir.resolve("sessions"), dir.resolve("missing-target"))
        assertTrue(LiveSessionReader.readWithDiagnostics(link).problem != null)
    }

    @Test
    fun `stale pid files are not live`() {
        val dir = tmp.newFolder("sessions").toPath()
        val fixture = Paths.get(javaClass.getResource("/fixtures/live_session.json")!!.toURI())
        Files.copy(fixture, dir.resolve("14606.json"))
        Files.writeString(dir.resolve("14606.abcdef.key"), "secret")
        Files.writeString(dir.resolve("999.json"), "not json")

        val alive = LiveSessionReader.read(dir) { pid -> pid == 14606L }
        assertEquals(setOf("00c731e1-eed1-495b-92e4-346560c3e251"), alive.keys)
        assertEquals(14606L, alive.values.single().pid)

        val dead = LiveSessionReader.read(dir) { false }
        assertTrue(dead.isEmpty())
    }

    @Test
    fun `a reused PID does not make an old marker live`() {
        val dir = tmp.newFolder("reused").toPath()
        val pid = ProcessHandle.current().pid()
        val file = dir.resolve("$pid.json")
        Files.writeString(file, """{"pid":$pid,"sessionId":"old-session"}""")
        assertEquals(setOf("old-session"), LiveSessionReader.read(dir).keys)
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(0))
        assertTrue(LiveSessionReader.read(dir).isEmpty())
    }

    @Test
    fun `status and its timestamp are read, falling back to updatedAt`() {
        val dir = tmp.newFolder("status").toPath()
        val fixture = Paths.get(javaClass.getResource("/fixtures/live_session.json")!!.toURI())
        val live = LiveSessionReader.parse(fixture)!!
        assertEquals("idle", live.status)
        assertEquals(Instant.ofEpochMilli(1787672885210), live.statusUpdatedAt)

        val file = dir.resolve("1.json")
        Files.writeString(file, """{"pid":1,"sessionId":"s","status":"busy","updatedAt":42}""")
        val fallback = LiveSessionReader.parse(file)!!
        assertEquals("busy", fallback.status)
        assertEquals(Instant.ofEpochMilli(42), fallback.statusUpdatedAt)

        Files.writeString(file, """{"pid":1,"sessionId":"s"}""")
        val bare = LiveSessionReader.parse(file)!!
        assertEquals(null, bare.status)
        assertEquals(null, bare.statusUpdatedAt)
    }
}
