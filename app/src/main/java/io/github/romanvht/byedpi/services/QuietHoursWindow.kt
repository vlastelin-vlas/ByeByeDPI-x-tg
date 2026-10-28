package io.github.romanvht.byedpi.services

import java.util.Calendar

/** Local wall-clock minutes; start is inclusive and end is exclusive. */
data class QuietHoursWindow(val start: Int, val end: Int) {
    init {
        require(start in 0..1439 && end in 0..1439)
    }

    fun contains(minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }

    fun nextBoundary(now: Calendar): Long = listOf(start, end).minOf { minute ->
        (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }
}
