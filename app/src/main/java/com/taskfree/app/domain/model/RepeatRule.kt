package com.taskfree.app.domain.model

import java.time.LocalDate

/** A complete, validated rule. Partial numeric input belongs to the editor draft. */
data class RepeatRule(val recurrence: Recurrence, val interval: Int = 1) {
    init {
        validate(recurrence, interval)
    }

    companion object {
        fun validate(recurrence: Recurrence, interval: Int) {
            require(interval in 1..99) { "Repeat interval must be between 1 and 99" }
            require(recurrence.supportsInterval || interval == 1) { "This frequency requires interval one" }
        }

        fun fromStoredOrNull(recurrence: Recurrence, interval: Int): RepeatRule? =
            try {
                RepeatRule(recurrence, interval)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

/** The same effective base is used by save and preview, including legacy schedules. */
fun RepeatRule.baseDateAfterEdit(
    due: LocalDate?,
    previousRecurrence: Recurrence,
    previousDue: LocalDate?,
    previousBaseDate: LocalDate?
): LocalDate? = when {
    recurrence == Recurrence.NONE -> null
    previousRecurrence == Recurrence.NONE || due != previousDue -> due
    else -> previousBaseDate ?: due
}

val Recurrence.supportsInterval: Boolean
    get() = this == Recurrence.DAILY || this == Recurrence.WEEKLY || this == Recurrence.MONTHLY
