package io.github.romanvht.byedpi.services

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuietHoursWindowTest {
    @Test fun overnightWindowIncludesStartAndExcludesEnd() {
        val window = QuietHoursWindow(23 * 60, 8 * 60)
        assertFalse(window.contains(22 * 60 + 59))
        assertTrue(window.contains(23 * 60))
        assertTrue(window.contains(0))
        assertTrue(window.contains(7 * 60 + 59))
        assertFalse(window.contains(8 * 60))
        assertFalse(window.contains(12 * 60))
    }

    @Test fun daytimeWindowAndEqualTimesDoNotPauseAllDay() {
        val window = QuietHoursWindow(12 * 60, 14 * 60)
        assertFalse(window.contains(11 * 60))
        assertTrue(window.contains(12 * 60))
        assertTrue(window.contains(13 * 60))
        assertFalse(window.contains(14 * 60))
        assertFalse(QuietHoursWindow(0, 0).contains(0))
    }

    @Test fun nextBoundaryIsAlwaysInTheFutureAndCrossesMidnight() {
        val window = QuietHoursWindow(0, 8 * 60)
        assertEquals(at(28, 8, 0).timeInMillis, window.nextBoundary(at(28, 0, 0)))
        assertEquals(at(29, 0, 0).timeInMillis, window.nextBoundary(at(28, 8, 0)))
        assertEquals(at(29, 0, 0).timeInMillis, window.nextBoundary(at(28, 23, 59)))
    }

    @Test fun boundaryUsesLocalCalendarAcrossDaylightSavingChange() {
        val now = at(24, 23, 0).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }
        now.set(2026, Calendar.OCTOBER, 24, 23, 0, 0)
        val expected = now.clone() as Calendar
        expected.set(2026, Calendar.OCTOBER, 25, 8, 0, 0)
        assertEquals(expected.timeInMillis, QuietHoursWindow(23 * 60, 8 * 60).nextBoundary(now))
    }

    @Test fun invalidMinutesAreRejected() {
        for ((start, end) in listOf(-1 to 480, 1440 to 480, 0 to 1440)) {
            try {
                QuietHoursWindow(start, end)
                fail("Invalid minute accepted: $start, $end")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun at(day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Yekaterinburg")).apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
        }
}
