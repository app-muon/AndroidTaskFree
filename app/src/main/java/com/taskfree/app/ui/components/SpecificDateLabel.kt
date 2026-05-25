// ui/task/components/specificDateLabel.kt
package com.taskfree.app.ui.components

import android.content.Context
import com.taskfree.app.R
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import com.taskfree.app.util.weekdayShortLabel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun specificDateLabel(
    date: LocalDate,
    context: Context,
    dp: DateProvider = AppDateProvider.current   // <-- inject, default to singleton
): String {
    val today = dp.today()                        // <-- use provider
    return when (val days = ChronoUnit.DAYS.between(today, date)) {
        0L -> context.getString(R.string.today)
        1L -> context.getString(R.string.tomorrow)
        -1L -> context.getString(R.string.yesterday)
        in 2L..6L -> weekdayShortLabel(date)
        in -6L..-2L -> context.getString(R.string.last_weekday, weekdayShortLabel(date))
        else -> date.toString() // ISO-8601, beyond the in-week window
    }
}
