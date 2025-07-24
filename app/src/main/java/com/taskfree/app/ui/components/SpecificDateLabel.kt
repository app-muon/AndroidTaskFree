// ui/task/components/specificDateLabel.kt
package com.taskfree.app.ui.components

import com.taskfree.app.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun specificDateLabel(date: LocalDate, context: android.content.Context): String {
    val today = LocalDate.now()
    return when (date) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        today.plusDays(2) -> context.getString(R.string.today_offset, 2)
        else -> if (date.isAfter(today.minusDays(7)) && date.isBefore(today.plusDays(7))) {
            context.getString(
                R.string.today_offset,          // e.g. "T+%d"
                ChronoUnit.DAYS.between(today, date)
            )
        } else {
            date.toString()                    // ISO-8601
        }
    }
}