package com.taskfree.app.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RepeatRuleTest {
    private val jan31 = LocalDate.of(2026, 1, 31)

    @Test fun intervalsAndFixedPresetsAreValidated() {
        for (rec in Recurrence.entries) {
            assertEquals(1, RepeatRule(rec).interval)
            for (bad in listOf(-1, 0, 100, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { RepeatRule(rec, bad) }
            }
            if (!rec.supportsInterval) assertThrows(IllegalArgumentException::class.java) { RepeatRule(rec, 2) }
        }
    }

    @Test fun twoAndNinetyNineAdvanceOneFullStepEvenWhenCompletedEarly() {
        for (n in listOf(2, 99)) {
            for ((rec, expected) in listOf(
                Recurrence.DAILY to jan31.plusDays(n.toLong()),
                Recurrence.WEEKLY to jan31.plusWeeks(n.toLong()),
                Recurrence.MONTHLY to jan31.plusMonths(n.toLong())
            )) {
                assertEquals(expected, RepeatRule(rec, n).calculateNextValidDueDate(jan31, jan31.minusDays(10)))
                assertEquals(expected, RepeatRule(rec, n).calculateNextValidDueDate(jan31, jan31))
            }
        }
    }

    @Test fun missedDaysAndWeeksSkipToTheNextScheduledDate() {
        assertEquals(jan31.plusDays(6), RepeatRule(Recurrence.DAILY, 2)
            .calculateNextValidDueDate(jan31, jan31.plusDays(4)))
        assertEquals(jan31.plusWeeks(6), RepeatRule(Recurrence.WEEKLY, 2)
            .calculateNextValidDueDate(jan31, jan31.plusWeeks(4).plusDays(1)))
    }

    @Test fun monthlyStepsClampOnceAndCatchUpRetainsDrift() {
        assertEquals(LocalDate.of(2026, 3, 31), RepeatRule(Recurrence.MONTHLY, 2)
            .calculateNextValidDueDate(jan31, jan31))
        assertEquals(LocalDate.of(2026, 11, 30), RepeatRule(Recurrence.MONTHLY, 2)
            .calculateNextValidDueDate(jan31, LocalDate.of(2026, 10, 1)))
        assertEquals(LocalDate.of(2026, 3, 28), RepeatRule(Recurrence.MONTHLY)
            .calculateNextValidDueDate(jan31, LocalDate.of(2026, 3, 1)))
        assertEquals(LocalDate.of(2024, 3, 29), RepeatRule(Recurrence.MONTHLY)
            .calculateNextValidDueDate(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)))
    }

    @Test fun cutoffCountsStepsNotDays() {
        val rule = RepeatRule(Recurrence.DAILY, 99)
        assertEquals(jan31.plusDays(99), rule.calculateNextValidDueDate(jan31.minusDays(2999L * 99), jan31))
        assertNull(rule.calculateNextValidDueDate(jan31.minusDays(3000L * 99), jan31))
    }
}
