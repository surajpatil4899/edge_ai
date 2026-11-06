package com.edgeai.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TimeResolverTest {
    // Saturday 2026-10-31 14:00
    private val now = LocalDateTime.of(2026, 10, 31, 14, 0)
    private fun at(spoken: String, day: String? = null) = TimeResolver.resolve(spoken, day, now)

    @Test
    fun explicitMeridiem() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 6, 0), at("6 am"))            // passed today -> tomorrow
        assertEquals(LocalDateTime.of(2026, 10, 31, 17, 45), at("5:45 pm"))
        assertEquals(LocalDateTime.of(2026, 11, 1, 6, 0), at("6 a.m", "tomorrow"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 30), at("8:30 tonight"))
    }

    @Test
    fun ambiguousHourPicksNextOccurrence() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 0), at("6"))             // 6:00 passed, 18:00 next
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 30), at("3:30"))
        assertEquals(LocalDateTime.of(2026, 11, 1, 7, 0), at("7", "tomorrow"))   // tomorrow: earliest, 7 AM
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), at("12"))              // noon passed -> midnight tonight
    }

    @Test
    fun weekdays() {
        assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0), at("9 am", "monday"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 16, 0), at("4 pm", "saturday"))   // today, still ahead
        assertEquals(LocalDateTime.of(2026, 11, 7, 10, 0), at("10 am", "saturday"))   // today, passed -> next week
    }

    @Test
    fun relativeTimesAreDelays() {
        // "Set alarm after 6 hours" used to ring at 6:00 AM (field logs, vivo).
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 0), at("6 hours"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 0), at("after six hours"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 14, 20), at("in 20 minutes"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 30), at("an hour and a half"))
        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 10), at("10 minutes past 6"))  // a clock time, not a delay
    }

    @Test
    fun describe() {
        assertEquals("6:00 AM tomorrow", TimeResolver.describe(LocalDateTime.of(2026, 11, 1, 6, 0), now))
        assertEquals("5:45 PM", TimeResolver.describe(LocalDateTime.of(2026, 10, 31, 17, 45), now))
        assertEquals("9:00 AM on Monday", TimeResolver.describe(LocalDateTime.of(2026, 11, 2, 9, 0), now))
    }
}
