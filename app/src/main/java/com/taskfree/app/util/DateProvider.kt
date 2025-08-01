// DateProvider.kt
package com.taskfree.app.util

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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


// Common formatters you might need
object DateFormatters {
    val ISO_LOCAL_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    val SHORT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
}