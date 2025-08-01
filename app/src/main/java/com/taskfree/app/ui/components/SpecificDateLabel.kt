// ui/task/components/specificDateLabel.kt
package com.taskfree.app.ui.components

import android.content.Context
import com.taskfree.app.R
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun specificDateLabel(
    date: LocalDate,
    context: Context,
    dp: DateProvider = AppDateProvider.current   // <-- inject, default to singleton
): String {
    val today = dp.today()                        // <-- use provider
    return when (date) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        today.plusDays(2) -> context.getString(R.string.today_offset, 2)
        else -> if (date.isAfter(today.minusDays(7)) && date.isBefore(today.plusDays(7))) {
            context.getString(
                R.string.today_offset,
                ChronoUnit.DAYS.between(today, date) // note: negative for past
            )
        } else {
            date.toString() // ISO-8601
        }
    }
}
