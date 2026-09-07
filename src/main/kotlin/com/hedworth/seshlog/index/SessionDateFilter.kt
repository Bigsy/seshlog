package com.hedworth.seshlog.index

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DatePeriod(val label: String) {
    ALL("All time"), TODAY("Today"), WEEK("Last 7 days"), MONTH("Last 30 days"), CUSTOM("Custom range")
}

/** Calendar bounds, recalculated from the injected clock when the panel filters its candidates. */
data class SessionDateFilter(
    val period: DatePeriod = DatePeriod.ALL,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    init {
        require(period != DatePeriod.CUSTOM || (start != null && end != null && start <= end && end < LocalDate.MAX)) {
            "Enter a start date on or before the end date."
        }
    }

    val label: String get() = if (period == DatePeriod.CUSTOM) "$start – $end" else period.label
    fun bounds(clock: Clock = Clock.systemDefaultZone(), zone: ZoneId = clock.zone): DateBounds {
        val today = LocalDate.now(clock.withZone(zone))
        val first = when (period) {
            DatePeriod.ALL -> return DateBounds(null, null)
            DatePeriod.TODAY -> today
            DatePeriod.WEEK -> today.minusDays(6)
            DatePeriod.MONTH -> today.minusDays(29)
            DatePeriod.CUSTOM -> start!!
        }
        val last = if (period == DatePeriod.CUSTOM) end!! else today
        return DateBounds(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
    }
}

data class DateBounds(val start: Instant?, val endExclusive: Instant?) {
    fun contains(at: Instant): Boolean =
        (start == null || at >= start) && (endExclusive == null || at < endExclusive)
}
