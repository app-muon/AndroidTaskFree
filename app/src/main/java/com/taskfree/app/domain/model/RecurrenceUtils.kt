package com.taskfree.app.domain.model

import java.time.LocalDate

private fun RepeatRule.nextDate(base: LocalDate): LocalDate? = when (recurrence) {
    Recurrence.NONE -> null
    Recurrence.DAILY -> base.plusDays(interval.toLong())
    Recurrence.WEEKLY -> base.plusWeeks(interval.toLong())
    Recurrence.MONTHLY -> base.plusMonths(interval.toLong())
    Recurrence.WEEKDAYS -> generateSequence(base.plusDays(1)) { it.plusDays(1) }
        .first { it.dayOfWeek.value in 1..5 }
    Recurrence.WEEKENDS -> generateSequence(base.plusDays(1)) { it.plusDays(1) }
        .first { it.dayOfWeek.value in 6..7 }
    Recurrence.QUARTERLY -> base.plusMonths(3)
    Recurrence.YEARLY -> base.plusYears(1)
}

/** Walk each step to retain month-end clamping and the existing 3,000-step cutoff. */
fun RepeatRule.calculateNextValidDueDate(baseDate: LocalDate, today: LocalDate): LocalDate? {
    var next = baseDate
    repeat(3000) {
        next = nextDate(next) ?: return null
        if (next.isAfter(today)) return next
    }
    return null
}
