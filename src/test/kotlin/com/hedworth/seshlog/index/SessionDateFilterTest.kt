package com.hedworth.seshlog.index

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SessionDateFilterTest {
    private val zone = ZoneId.of("Europe/London")
    private val clock = Clock.fixed(Instant.parse("2026-03-29T12:00:00Z"), zone)

    @Test fun inclusiveCalendarDatesAcrossSpringAndAutumnDst() {
        val spring = SessionDateFilter(DatePeriod.TODAY).bounds(clock)
        assertEquals(Duration.ofHours(23), Duration.between(spring.start, spring.endExclusive))
        assertTrue(spring.contains(spring.start!!))
        assertTrue(spring.contains(spring.endExclusive!!.minusNanos(1)))
        assertFalse(spring.contains(spring.start.minusNanos(1)))
        assertFalse(spring.contains(spring.endExclusive))
        val autumn = SessionDateFilter(DatePeriod.CUSTOM, LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25")).bounds(clock)
        assertEquals(Duration.ofHours(25), Duration.between(autumn.start, autumn.endExclusive))
    }

    @Test fun relativeRangesIncludeTodayAndUseInjectedZone() {
        assertEquals(Instant.parse("2026-03-23T00:00:00Z"), SessionDateFilter(DatePeriod.WEEK).bounds(clock).start)
        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), SessionDateFilter(DatePeriod.MONTH).bounds(clock).start)
        val midnight = Clock.fixed(Instant.parse("2026-03-29T23:30:00Z"), ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-03-29T23:00:00Z"), SessionDateFilter(DatePeriod.TODAY).bounds(midnight, zone).start)
        assertTrue(SessionDateFilter().bounds(clock).contains(Instant.MIN))
        assertTrue(SessionDateFilter().bounds(clock).contains(Instant.MAX))
    }

    @Test fun invalidCustomRangesAreRejected() {
        for ((start, end) in listOf(null to null, LocalDate.parse("2026-04-02") to LocalDate.parse("2026-04-01"),
                LocalDate.now() to LocalDate.MAX)) {
            assertTrue(runCatching { SessionDateFilter(DatePeriod.CUSTOM, start, end) }.isFailure)
        }
    }
}
