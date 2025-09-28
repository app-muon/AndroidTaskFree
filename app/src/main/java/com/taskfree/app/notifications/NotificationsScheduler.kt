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
        val am = ctx.getSystemService<AlarmManager>() ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
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

        // toast based on diff
        val kind = toastKindForReminderChange(oldUtc, newUtc)
        postNotificationToast(ctx, kind, newUtc)
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
