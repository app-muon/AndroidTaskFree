// testutil/Clocks.kt
package com.taskfree.app.testutil

import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A Clock that tests can move forwards (or anywhere) at will. */
class MutableClock(
    @Volatile private var now: Instant,
    private val zone: ZoneId = ZoneId.systemDefault()
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now

    fun setDate(date: LocalDate, time: LocalTime = LocalTime.NOON) {
        now = date.atTime(time).atZone(zone).toInstant()
    }

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

fun clockAt(date: LocalDate, time: LocalTime = LocalTime.NOON): MutableClock =
    MutableClock(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant())

fun datesAt(date: LocalDate): DateProvider = DateProvider(clockAt(date))

/** Pins [AppDateProvider] to [today] for the duration of each test. */
class AppDateProviderRule(private val today: LocalDate) : TestWatcher() {
    override fun starting(description: Description) {
        AppDateProvider.setForTesting(datesAt(today))
    }

    override fun finished(description: Description) {
        AppDateProvider.reset()
    }
}
