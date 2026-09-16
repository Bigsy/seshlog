package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ActivityLabelTest {
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    @Test
    fun `badges name the state and add how long the agent has waited`() {
        assertEquals("● working", ActivityLabel.badge(Activity.WORKING, now.minusSeconds(3600), now))
        assertEquals("● waiting", ActivityLabel.badge(Activity.WAITING, now.minusSeconds(59), now))
        assertEquals("● waiting 4 min", ActivityLabel.badge(Activity.WAITING, now.minusSeconds(4 * 60 + 30), now))
        assertEquals("● interrupted 2 h", ActivityLabel.badge(Activity.INTERRUPTED, now.minusSeconds(2 * 3600 + 5), now))
        assertEquals("● waiting", ActivityLabel.badge(Activity.WAITING, null, now))
        assertEquals("● live", ActivityLabel.badge(Activity.UNKNOWN, now.minusSeconds(3600), now))
    }

    @Test
    fun `durations round down and ignore clocks that ran backwards`() {
        assertNull(ActivityLabel.duration(null, now))
        assertNull(ActivityLabel.duration(now.minusSeconds(30), now))
        assertNull(ActivityLabel.duration(now.plusSeconds(600), now))
        assertEquals("1 min", ActivityLabel.duration(now.minusSeconds(60), now))
        assertEquals("59 min", ActivityLabel.duration(now.minusSeconds(59 * 60 + 59), now))
        assertEquals("1 h", ActivityLabel.duration(now.minusSeconds(3600), now))
        assertEquals("23 h", ActivityLabel.duration(now.minusSeconds(23 * 3600 + 3599), now))
        assertEquals("3 d", ActivityLabel.duration(now.minusSeconds(3 * 86400 + 7200), now))
    }

    @Test
    fun `descriptions and idleness`() {
        assertEquals("Working", ActivityLabel.describe(Activity.WORKING))
        assertEquals("Waiting for input", ActivityLabel.describe(Activity.WAITING))
        assertEquals("Interrupted, waiting for input", ActivityLabel.describe(Activity.INTERRUPTED))
        assertEquals("Running", ActivityLabel.describe(Activity.UNKNOWN))
        assertTrue(ActivityLabel.isIdle(Activity.WAITING))
        assertTrue(ActivityLabel.isIdle(Activity.INTERRUPTED))
        assertFalse(ActivityLabel.isIdle(Activity.WORKING))
        assertFalse(ActivityLabel.isIdle(Activity.UNKNOWN))
    }
}
