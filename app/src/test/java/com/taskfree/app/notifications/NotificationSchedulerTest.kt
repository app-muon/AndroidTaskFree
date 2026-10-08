// notifications/NotificationSchedulerTest.kt
package com.taskfree.app.notifications

import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NotificationSchedulerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    // Fixed local hours so "same day, different time" never straddles midnight
    private fun inThreeDaysAt(hour: Int): Instant =
        LocalDate.now().plusDays(3).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant()

    private val future = inThreeDaysAt(10)

    private fun latestToast(): String? {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowToast.getTextOfLatestToast()
    }

    @Test
    fun `future reminder sets an alarm for that task`() {
        NotificationScheduler.schedule(app, taskId = 5, newUtc = future)

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(future.toEpochMilli(), alarm.triggerAtMs)
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals(AlarmReceiver.ACTION, intent.action)
        assertEquals(5, intent.getIntExtra(AlarmReceiver.EXTRA_TASK_ID, -1))
        assertEquals(
            app.getString(R.string.notification_scheduled, NotificationScheduler.formatDateTime(future)),
            latestToast()
        )
    }

    @Test
    fun `past reminder fires immediately instead of setting an alarm`() {
        NotificationScheduler.schedule(app, taskId = 6, newUtc = Instant.now().minusSeconds(60))

        assertTrue(alarms.scheduledAlarms.isEmpty())
        val sent = shadowOf(app).broadcastIntents.single()
        assertEquals(AlarmReceiver.ACTION, sent.action)
        assertEquals(6, sent.getIntExtra(AlarmReceiver.EXTRA_TASK_ID, -1))
    }

    @Test
    fun `rescheduling replaces rather than duplicates the alarm`() {
        val later = inThreeDaysAt(12)
        NotificationScheduler.schedule(app, taskId = 5, newUtc = future)
        NotificationScheduler.reschedule(app, taskId = 5, oldUtc = future, newUtc = later)

        assertEquals(later.toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
        assertEquals(
            app.getString(R.string.notification_time_changed, NotificationScheduler.formatDateTime(later)),
            latestToast()
        )
    }

    @Test
    fun `moving the reminder to another day reports a date change`() {
        val nextDay = future.plus(Duration.ofDays(1))
        NotificationScheduler.reschedule(app, taskId = 5, oldUtc = future, newUtc = nextDay)

        assertEquals(
            app.getString(R.string.notification_date_changed, NotificationScheduler.formatDate(nextDay)),
            latestToast()
        )
    }

    @Test
    fun `alarms for different tasks are independent`() {
        NotificationScheduler.schedule(app, taskId = 1, newUtc = future)
        NotificationScheduler.schedule(app, taskId = 2, newUtc = future)
        NotificationScheduler.cancel(app, taskId = 1, oldUtc = null)

        val remaining = alarms.scheduledAlarms.single()
        assertEquals(2, shadowOf(remaining.operation).savedIntent.getIntExtra(AlarmReceiver.EXTRA_TASK_ID, -1))
    }

    @Test
    fun `clearing a reminder cancels the alarm and says so`() {
        NotificationScheduler.schedule(app, taskId = 5, newUtc = future)
        NotificationScheduler.reschedule(app, taskId = 5, oldUtc = future, newUtc = null)

        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(
            app.getString(R.string.notification_cancelled, NotificationScheduler.formatDateTime(future)),
            latestToast()
        )
    }

    @Test
    fun `cancelling a task without an alarm is silent`() {
        NotificationScheduler.cancel(app, taskId = 42, oldUtc = future)

        assertNull(latestToast())
    }

    @Test
    fun `in-past toast has a fixed message`() {
        NotificationScheduler.showToast(app, NotificationScheduler.ToastKind.InPast)

        assertEquals(app.getString(R.string.notification_in_past), latestToast())
    }

    @Test
    fun `unavailable database retry is silent and replaces the same pending intent`() {
        NotificationScheduler.scheduleSilently(app, 5, future)
        val original = alarms.scheduledAlarms.single().operation
        val now = Instant.now()
        repeat(3) { NotificationScheduler.retryWhenAvailable(app, 5, 0, now) }
        val retry = alarms.scheduledAlarms.single()
        assertEquals(original, retry.operation)
        assertEquals(now.plusSeconds(60).toEpochMilli(), retry.triggerAtMs)
        assertNull(latestToast())
    }
}
