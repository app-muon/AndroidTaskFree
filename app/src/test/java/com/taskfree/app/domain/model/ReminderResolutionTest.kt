// domain/model/ReminderResolutionTest.kt
package com.taskfree.app.domain.model

import com.taskfree.app.testutil.task
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderResolutionTest {

    // resolveReminderInstant compares against the real Instant.now(), so keep dates far away
    private val futureDue = LocalDate.now().plusYears(1)
    private val pastDue = LocalDate.of(2000, 1, 1)
    private val live = task(categoryId = 1, due = futureDue)

    private fun localInstant(date: LocalDate, time: LocalTime) =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()

    @Test
    fun `future reminder is scheduled at the exact local time`() {
        assertEquals(
            ReminderResult.Scheduled(localInstant(futureDue, LocalTime.of(9, 0))),
            live.resolveReminderInstant(NotificationOption.Morning, futureDue)
        )
    }

    @Test
    fun `past reminder is reported as in the past`() {
        assertEquals(
            ReminderResult.InPast,
            live.resolveReminderInstant(NotificationOption.Noon, pastDue)
        )
    }

    @Test
    fun `done and archived tasks never get reminders`() {
        val done = live.copy(status = TaskStatus.DONE)
        val archived = live.copy(isArchived = true)
        assertEquals(ReminderResult.Blocked, done.resolveReminderInstant(NotificationOption.Morning, futureDue))
        assertEquals(ReminderResult.Blocked, archived.resolveReminderInstant(NotificationOption.Morning, futureDue))
    }

    @Test
    fun `no option, no time or no due date is blocked`() {
        assertEquals(ReminderResult.Blocked, live.resolveReminderInstant(NotificationOption.None, futureDue))
        assertEquals(ReminderResult.Blocked, live.resolveReminderInstant(NotificationOption.Other(null), futureDue))
        assertEquals(ReminderResult.Blocked, live.resolveReminderInstant(NotificationOption.Morning, null))
    }

    @Test
    fun `from maps preset times and falls back to Other`() {
        assertEquals(NotificationOption.Morning, NotificationOption.from(LocalTime.of(9, 0)))
        assertEquals(NotificationOption.Noon, NotificationOption.from(LocalTime.NOON))
        assertEquals(NotificationOption.Afternoon, NotificationOption.from(LocalTime.of(15, 0)))
        assertEquals(
            NotificationOption.Other(LocalTime.of(10, 30)),
            NotificationOption.from(LocalTime.of(10, 30))
        )
    }

    @Test
    fun `fromTask reads the local time of the stored reminder`() {
        assertEquals(NotificationOption.None, NotificationOption.fromTask(live))
        val withReminder = live.copy(reminderTime = localInstant(futureDue, LocalTime.of(15, 0)))
        assertEquals(NotificationOption.Afternoon, NotificationOption.fromTask(withReminder))
    }

    @Test
    fun `toInstant needs both a time and a date`() {
        assertNull(NotificationOption.Morning.toInstant(null))
        assertNull(NotificationOption.None.toInstant(futureDue))
        assertNull(NotificationOption.Other(null).toInstant(futureDue))
        assertEquals(
            localInstant(futureDue, LocalTime.of(7, 45)),
            NotificationOption.Other(LocalTime.of(7, 45)).toInstant(futureDue)
        )
    }
}
