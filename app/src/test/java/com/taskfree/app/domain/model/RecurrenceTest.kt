// domain/model/RecurrenceTest.kt
package com.taskfree.app.domain.model

import com.taskfree.app.testutil.datesAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {

    // Tuesday
    private val today = LocalDate.of(2026, 10, 6)
    private val dates = datesAt(today)

    private fun Recurrence.next(base: LocalDate, on: LocalDate = today) =
        calculateNextValidDueDate(base, datesAt(on))

    @Test
    fun `from parses every enum name`() {
        Recurrence.entries.forEach { assertEquals(it, Recurrence.from(it.name)) }
    }

    @Test
    fun `from rejects legacy and unknown names`() {
        assertThrows(IllegalArgumentException::class.java) { Recurrence.from("EVERY_DAY") }
        assertThrows(IllegalArgumentException::class.java) { Recurrence.from("daily") }
    }

    @Test
    fun `NONE never produces a next date`() {
        assertNull(Recurrence.NONE.calculateNextValidDueDate(today, dates))
    }

    @Test
    fun `DAILY from today is tomorrow`() {
        assertEquals(today.plusDays(1), Recurrence.DAILY.next(today))
    }

    @Test
    fun `DAILY catches up from an old base date to tomorrow`() {
        assertEquals(today.plusDays(1), Recurrence.DAILY.next(today.minusDays(30)))
    }

    @Test
    fun `DAILY from a future base advances one step`() {
        val future = today.plusDays(10)
        assertEquals(future.plusDays(1), Recurrence.DAILY.next(future))
    }

    @Test
    fun `WEEKLY keeps the weekday and skips past dates`() {
        // 2026-09-01 is a Tuesday; Tuesday 2026-10-06 is "today", so not valid
        val next = Recurrence.WEEKLY.next(LocalDate.of(2026, 9, 1))
        assertEquals(LocalDate.of(2026, 10, 13), next)
        assertEquals(DayOfWeek.TUESDAY, next!!.dayOfWeek)
    }

    @Test
    fun `WEEKDAYS from Friday jumps to Monday`() {
        val friday = LocalDate.of(2026, 10, 9)
        assertEquals(LocalDate.of(2026, 10, 12), Recurrence.WEEKDAYS.next(friday, on = friday))
    }

    @Test
    fun `WEEKDAYS midweek goes to the next day`() {
        assertEquals(LocalDate.of(2026, 10, 7), Recurrence.WEEKDAYS.next(today))
    }

    @Test
    fun `WEEKENDS from Saturday goes to Sunday`() {
        val saturday = LocalDate.of(2026, 10, 10)
        assertEquals(saturday.plusDays(1), Recurrence.WEEKENDS.next(saturday, on = saturday))
    }

    @Test
    fun `WEEKENDS from Sunday goes to next Saturday`() {
        val sunday = LocalDate.of(2026, 10, 11)
        assertEquals(LocalDate.of(2026, 10, 17), Recurrence.WEEKENDS.next(sunday, on = sunday))
    }

    @Test
    fun `MONTHLY clamps the 31st to the end of a shorter month`() {
        val jan31 = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), Recurrence.MONTHLY.next(jan31, on = jan31))
    }

    /**
     * Characterises current behaviour: each occurrence is computed from the previous one,
     * so after a short month the day-of-month stays clamped (31st → 28th → 28th).
     */
    @Test
    fun `MONTHLY drifts after a clamped month`() {
        val feb28 = LocalDate.of(2026, 2, 28)
        assertEquals(LocalDate.of(2026, 3, 28), Recurrence.MONTHLY.next(feb28, on = feb28))

        // Catch-up from an old base date also walks through February
        val jan31 = LocalDate.of(2026, 1, 31)
        assertEquals(
            LocalDate.of(2026, 4, 28),
            Recurrence.MONTHLY.next(jan31, on = LocalDate.of(2026, 4, 15))
        )
    }

    @Test
    fun `QUARTERLY adds three months with clamping`() {
        val nov30 = LocalDate.of(2025, 11, 30)
        assertEquals(LocalDate.of(2026, 2, 28), Recurrence.QUARTERLY.next(nov30, on = nov30))
    }

    @Test
    fun `YEARLY from a leap day lands on the 28th`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2025, 2, 28), Recurrence.YEARLY.next(leapDay, on = leapDay))
    }

    @Test
    fun `every recurring type returns a date no earlier than tomorrow`() {
        val oldBase = LocalDate.of(2020, 1, 1)
        Recurrence.entries.filter { it != Recurrence.NONE }.forEach { rec ->
            val next = rec.next(oldBase)!!
            assertFalse("$rec gave $next", next.isBefore(today.plusDays(1)))
        }
    }

    /**
     * Catch-up stops after 3000 steps, so a DAILY task whose base date is ~8.2+ years old
     * gets no next date (and no next instance is spawned).
     */
    @Test
    fun `DAILY gives up when the base date is more than 3000 steps behind`() {
        assertNull(Recurrence.DAILY.next(today.minusDays(3001)))
        assertEquals(today.plusDays(1), Recurrence.DAILY.next(today.minusDays(2998)))
    }
}
