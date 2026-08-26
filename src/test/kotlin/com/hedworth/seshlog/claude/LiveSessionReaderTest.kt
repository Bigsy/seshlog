package com.hedworth.seshlog.claude

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
}
