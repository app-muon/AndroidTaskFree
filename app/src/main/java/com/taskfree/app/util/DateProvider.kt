// DateProvider.kt
package com.taskfree.app.util

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Simple date/time provider that always uses the phone's system timezone.
 * Uses Clock for clean testability.
 */
class DateProvider(private val clock: Clock = Clock.systemDefaultZone()) {

    fun today(): LocalDate = LocalDate.now(clock)

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    // Convenience methods
    fun todayPlusDays(days: Long): LocalDate = today().plusDays(days)
    fun todayMinusDays(days: Long): LocalDate = today().minusDays(days)
    fun isToday(date: LocalDate?): Boolean = date == today()
    fun isFuture(date: LocalDate?): Boolean = date?.isAfter(today()) ?: false
    fun isPast(date: LocalDate?): Boolean = date?.isBefore(today()) ?: false
}

/**
 * Global singleton - always uses system timezone in production
 */
object AppDateProvider {
    @Volatile private var _instance: DateProvider = DateProvider()

    val current: DateProvider get() = _instance
}


/**
 * Short, capitalized weekday code for a date, e.g. "Mon".
 * Locale-aware; the single source of truth for weekday labels in the UI.
 */
fun weekdayShortLabel(date: LocalDate): String =
    date.dayOfWeek
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
        .take(3)
        .replaceFirstChar { it.uppercaseChar() }