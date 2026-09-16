package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Activity
import java.time.Duration
import java.time.Instant

/** Text for a running session's activity badge and tooltip. Pure, so the wording is unit-tested. */
object ActivityLabel {
    /** The badge after the title: `● working`, `● waiting 4 min`, `● interrupted`, or `● live` when nothing is known. */
    fun badge(activity: Activity, since: Instant?, now: Instant): String = when (activity) {
        Activity.WORKING -> "● working"
        Activity.WAITING -> "● waiting" + suffix(since, now)
        Activity.INTERRUPTED -> "● interrupted" + suffix(since, now)
        Activity.UNKNOWN -> "● live"
    }

    fun describe(activity: Activity): String = when (activity) {
        Activity.WORKING -> "Working"
        Activity.WAITING -> "Waiting for input"
        Activity.INTERRUPTED -> "Interrupted, waiting for input"
        Activity.UNKNOWN -> "Running"
    }

    /** `4 min`, `2 h`, `3 d`; null under a minute, without a timestamp, or when the clock ran backwards. */
    fun duration(since: Instant?, now: Instant): String? {
        if (since == null) return null
        val minutes = Duration.between(since, now).toMinutes()
        return when {
            minutes < 1 -> null
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${minutes / 60} h"
            else -> "${minutes / (60 * 24)} d"
        }
    }

    /** Whether [activity] means the agent has stopped and is on the user. */
    fun isIdle(activity: Activity): Boolean = activity == Activity.WAITING || activity == Activity.INTERRUPTED

    private fun suffix(since: Instant?, now: Instant): String = duration(since, now)?.let { " $it" } ?: ""
}
