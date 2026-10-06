// domain/model/ValidationTest.kt
package com.taskfree.app.domain.model

import com.taskfree.app.testutil.AppDateProviderRule
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ValidationTest {

    // Tuesday 2026-10-06
    @get:Rule
    val dateRule = AppDateProviderRule(LocalDate.of(2026, 10, 6))

    private val saturday = DueChoice.Other(LocalDate.of(2026, 10, 10))
    private val sunday = DueChoice.Other(LocalDate.of(2026, 10, 11))
    private val monday = DueChoice.Other(LocalDate.of(2026, 10, 12))
    private val wednesday = DueChoice.Other(LocalDate.of(2026, 10, 7))

    @Test
    fun `no due date is fine only without recurrence`() {
        assertEquals(
            RecurrenceValidationResult.Ok,
            validateRecurrenceDate(Recurrence.NONE, DueChoice.None)
        )
        Recurrence.entries.filter { it != Recurrence.NONE }.forEach {
            assertEquals(
                "$it",
                RecurrenceValidationResult.MissingDueDate,
                validateRecurrenceDate(it, DueChoice.None)
            )
        }
    }

    @Test
    fun `date-agnostic recurrences accept any date`() {
        listOf(
            Recurrence.NONE, Recurrence.DAILY, Recurrence.WEEKLY,
            Recurrence.MONTHLY, Recurrence.QUARTERLY, Recurrence.YEARLY
        ).forEach {
            assertEquals("$it", RecurrenceValidationResult.Ok, validateRecurrenceDate(it, saturday))
        }
    }

    @Test
    fun `WEEKDAYS rejects a weekend date with a formatted message`() {
        assertEquals(
            RecurrenceValidationResult.NotWeekday("Sat, Oct 10"),
            validateRecurrenceDate(Recurrence.WEEKDAYS, saturday)
        )
        assertEquals(
            RecurrenceValidationResult.Ok,
            validateRecurrenceDate(Recurrence.WEEKDAYS, monday)
        )
    }

    @Test
    fun `WEEKENDS rejects a weekday date`() {
        assertEquals(
            RecurrenceValidationResult.NotWeekend("Wed, Oct 7"),
            validateRecurrenceDate(Recurrence.WEEKENDS, wednesday)
        )
        assertEquals(
            RecurrenceValidationResult.Ok,
            validateRecurrenceDate(Recurrence.WEEKENDS, sunday)
        )
    }

    @Test
    fun `weekday-specific recurrence with an unpicked date needs a due date`() {
        assertEquals(
            RecurrenceValidationResult.MissingDueDate,
            validateRecurrenceDate(Recurrence.WEEKDAYS, DueChoice.Other(null))
        )
    }

    @Test
    fun `quick choices resolve against the pinned date`() {
        // Today is Tuesday, so WEEKENDS should reject it and WEEKDAYS accept it
        assertEquals(
            RecurrenceValidationResult.Ok,
            validateRecurrenceDate(Recurrence.WEEKDAYS, DueChoice.Today)
        )
        assertEquals(
            RecurrenceValidationResult.NotWeekend("Tue, Oct 6"),
            validateRecurrenceDate(Recurrence.WEEKENDS, DueChoice.Today)
        )
    }

    @Test
    fun `notification needs a due date`() {
        assertEquals(
            NotificationValidationResult.MissingDueDate,
            validateNotification(DueChoice.None, NotificationOption.Morning)
        )
        assertEquals(
            NotificationValidationResult.Ok,
            validateNotification(DueChoice.None, NotificationOption.None)
        )
        assertEquals(
            NotificationValidationResult.Ok,
            validateNotification(DueChoice.Tomorrow, NotificationOption.Other(null))
        )
    }
}
