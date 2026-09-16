package com.hedworth.seshlog.pi

import com.hedworth.seshlog.cache.InfoStore
import com.hedworth.seshlog.model.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class PiTranscriptInfoStoreTest {
    private val info = PiTranscriptInfo(
        "synthetic", "/Users/tester/project", "Named", "First prompt",
        Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(1_700_000_100_000), 2,
        Activity.INTERRUPTED, Instant.ofEpochMilli(1_700_000_050_000),
    )

    @Test
    fun `round trips activity and drops caches from before it was recorded`() {
        val entries = mapOf(Paths.get("/pi/a.jsonl") to InfoStore.Entry(10, 20, info))
        val json = PiTranscriptInfoStore.toJson(entries)
        assertEquals(entries, PiTranscriptInfoStore.fromJson(json))

        assertTrue(PiTranscriptInfoStore.fromJson(json.replace("\"version\":2", "\"version\":1")).isEmpty())

        val withoutActivity = json.replace("\"activity\":\"INTERRUPTED\",", "").replace(",\"activityAt\":1700000050000", "")
        val read = PiTranscriptInfoStore.fromJson(withoutActivity).values.single().info
        assertEquals(Activity.UNKNOWN, read.activity)
        assertEquals(null, read.activityAt)
    }
}
