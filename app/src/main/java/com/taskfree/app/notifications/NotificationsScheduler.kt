package com.taskfree.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.taskfree.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object NotificationScheduler {
    private const val REQ = 11_337
    private const val RESTORE_REQ = 11_336
    /** Delays before each of the three follow-up attempts when the database is unavailable. */
    internal val RETRY_DELAYS_SECONDS = listOf(60L, 300L, 900L)

    enum class ToastKind {
        Scheduled,
        Cancelled,
        DateChanged,
        TimeChanged,
        InPast
    }

    // --- formatters ---
    private fun dateFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.getDefault())
            .withZone(ZoneId.systemDefault())

    private fun dateTimeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())

    fun formatDate(instant: Instant): String = dateFormatter().format(instant)
    fun formatDateTime(instant: Instant): String = dateTimeFormatter().format(instant)

    /** schedule one-shot alarm (fires roughly at the requested minute) */
    fun schedule(ctx: Context, taskId: Int, newUtc: Instant, oldUtc: Instant? = null) {
        scheduleSilently(ctx, taskId, newUtc)
        if (newUtc.toEpochMilli() >= System.currentTimeMillis()) {
            postNotificationToast(ctx, toastKindForReminderChange(oldUtc, newUtc), newUtc)
        }
    }

    internal fun retryWhenAvailable(ctx: Context, taskId: Int, retryCount: Int,
        now: Instant = Instant.now()) {
        val delaySeconds = RETRY_DELAYS_SECONDS.getOrNull(retryCount)
        if (delaySeconds == null) {
            ReminderAccessNotice.post(ctx)
            return
        }
        try {
            scheduleAlarm(ctx, taskId, now.plusSeconds(delaySeconds), retryCount + 1)
        } catch (e: Exception) {
            android.util.Log.e("NotificationScheduler", "Reminder retry could not be scheduled", e)
            ReminderAccessNotice.post(ctx)
        }
    }

    /** Same bounded policy as reminder delivery, for restoring all reminders after boot. */
    internal fun retryReminderRestore(ctx: Context, retryCount: Int, now: Instant = Instant.now()) {
        val delaySeconds = RETRY_DELAYS_SECONDS.getOrNull(retryCount)
        if (delaySeconds == null) {
            ReminderAccessNotice.post(ctx)
            return
        }
        try {
            val am = checkNotNull(ctx.getSystemService<AlarmManager>()) { "AlarmManager unavailable" }
            val intent = Intent(ctx, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_RESTORE_REMINDERS
                putExtra(BootReceiver.EXTRA_RETRY_COUNT, retryCount + 1)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, RESTORE_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now.plusSeconds(delaySeconds).toEpochMilli(), pi)
        } catch (e: Exception) {
            android.util.Log.e("NotificationScheduler", "Reminder restore retry could not be scheduled", e)
            ReminderAccessNotice.post(ctx)
        }
    }

    internal fun scheduleSilently(ctx: Context, taskId: Int, newUtc: Instant) =
        scheduleAlarm(ctx, taskId, newUtc, retryCount = 0)

    private fun scheduleAlarm(ctx: Context, taskId: Int, newUtc: Instant, retryCount: Int) {
        val am = ctx.getSystemService<AlarmManager>() ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_RETRY_COUNT, retryCount)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ + taskId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trigger = newUtc.toEpochMilli()
        if (trigger < System.currentTimeMillis()) {
            ctx.sendBroadcast(intent)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)

    }


    fun reschedule(ctx: Context, taskId: Int, oldUtc: Instant?, newUtc: Instant?) {
        if (oldUtc != null && newUtc == null) {
            postNotificationToast(ctx, ToastKind.Cancelled, oldUtc)
            cancel(ctx, taskId, oldUtc)  // let cancel handle AM
            return
        }
        if (newUtc != null) {
            schedule(ctx, taskId, newUtc, oldUtc)
        }
    }


    /** Cancel scheduled alarm and toast */
    fun cancel(ctx: Context, taskId: Int, oldUtc: Instant?) {
        val am = ctx.getSystemService<AlarmManager>() ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val existing = PendingIntent.getBroadcast(
            ctx, REQ + taskId, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            am.cancel(existing)
            if (oldUtc != null) {
                postNotificationToast(ctx, ToastKind.Cancelled, oldUtc)
            }
        }
    }


    // decide toast type
    private fun toastKindForReminderChange(oldUtc: Instant?, newUtc: Instant): ToastKind {
        if (oldUtc == null) return ToastKind.Scheduled
        val zone = ZoneId.systemDefault()
        val oldZ = oldUtc.atZone(zone)
        val newZ = newUtc.atZone(zone)
        return when {
            oldZ.toLocalDate() != newZ.toLocalDate() -> ToastKind.DateChanged
            oldZ.toLocalTime() != newZ.toLocalTime() -> ToastKind.TimeChanged
            else -> ToastKind.Scheduled
        }
    }

    fun showToast(ctx: Context, kind: ToastKind, instant: Instant? = null) {
        postNotificationToast(ctx, kind, instant)
    }
    /* common toast helper */
    private fun postNotificationToast(ctx: Context, kind: ToastKind, instant: Instant?) {
        val msg = when (kind) {
            ToastKind.Scheduled   -> ctx.getString(R.string.notification_scheduled, formatDateTime(instant!!))
            ToastKind.Cancelled   -> ctx.getString(R.string.notification_cancelled, formatDateTime(instant!!))
            ToastKind.DateChanged -> ctx.getString(R.string.notification_date_changed, formatDate(instant!!))
            ToastKind.TimeChanged -> ctx.getString(R.string.notification_time_changed, formatDateTime(instant!!))
            ToastKind.InPast      -> ctx.getString(R.string.notification_in_past)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }}
