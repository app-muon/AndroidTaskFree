// util/TimeExtTest.kt
package com.taskfree.app.util

import com.taskfree.app.testutil.datesAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimeExtTest {

    private val london = ZoneId.of("Europe/London")

    private fun at(dateTime: LocalDateTime, zone: ZoneId) = dateTime.atZone(zone).toInstant()

    @Test
    fun `keeps local time when crossing from summer to winter time`() {
        val reminder = at(LocalDateTime.of(2026, 10, 6, 9, 15), london)   // BST, 08:15Z
        val moved = reminder.sameLocalTimeOn(LocalDate.of(2026, 12, 1), london)

        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 15), moved.atZone(london).toLocalDateTime())
        assertEquals(at(LocalDateTime.of(2026, 12, 1, 9, 15), london), moved)
    }

    @Test
    fun `local time inside the spring-forward gap shifts forward an hour`() {
        // Clocks go forward 01:00 → 02:00 on Sunday 2026-03-29 in London
        val reminder = at(LocalDateTime.of(2026, 3, 28, 1, 30), london)
        val moved = reminder.sameLocalTimeOn(LocalDate.of(2026, 3, 29), london)

        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), moved.atZone(london).toLocalDateTime())
    }

    @Test
    fun `honours an explicit zone`() {
        val newYork = ZoneId.of("America/New_York")
        val reminder = at(LocalDateTime.of(2026, 6, 1, 9, 0), newYork)
        val moved = reminder.sameLocalTimeOn(LocalDate.of(2026, 6, 2), newYork)

        assertEquals(LocalDateTime.of(2026, 6, 2, 9, 0), moved.atZone(newYork).toLocalDateTime())
    }

    @Test
    fun `DateProvider classifies dates relative to its clock`() {
        val today = LocalDate.of(2026, 10, 6)
        val dp = datesAt(today)

        assertEquals(today, dp.today())
        assertEquals(today.plusDays(3), dp.todayPlusDays(3))
        assertEquals(today.minusDays(3), dp.todayMinusDays(3))
        assertTrue(dp.isToday(today))
        assertTrue(dp.isPast(today.minusDays(1)))
        assertTrue(dp.isFuture(today.plusDays(1)))
        assertFalse(dp.isPast(today))
        assertFalse(dp.isFuture(today))
        assertFalse(dp.isToday(null))
        assertFalse(dp.isPast(null))
        assertFalse(dp.isFuture(null))
    }
}
