package com.taskfree.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import com.taskfree.app.R
import java.time.Instant

object NotificationScheduler {
    private const val REQ = 11_337

    enum class ToastKind { None, Scheduled, Updated }

    /** schedule one-shot alarm (fires roughly at the requested minute) */
    fun schedule(
        ctx: Context,
        taskId: Int,
        whenUtc: Instant,
        toastKind: ToastKind = ToastKind.None
    ) {
        val am = ctx.getSystemService<AlarmManager>() ?: return
        // Build identifying intent once
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }

        // Does an alarm already exist?
        val existing = PendingIntent.getBroadcast(
            ctx, REQ + taskId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        // Always (re)create the PI we hand to AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQ + taskId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trigger = whenUtc.toEpochMilli()
        val nowMs = System.currentTimeMillis()
        Log.d("ReminderDebug", "trigger=$trigger (${Instant.ofEpochMilli(trigger)}), now=$nowMs (${Instant.ofEpochMilli(nowMs)}), diff=${trigger - nowMs}ms")

        if (trigger < System.currentTimeMillis()) {
            ctx.sendBroadcast(intent)       // overdue → fire now
            return
        }

        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)

        when (toastKind) {
            ToastKind.Scheduled -> toast(ctx, ctx.getString(R.string.notification_scheduled))
            ToastKind.Updated   -> toast(ctx, ctx.getString(R.string.notification_updated))
            ToastKind.None      -> Unit
        }
    }

    fun cancel(ctx: Context, taskId: Int, showToast: Boolean = false) {
        val am = ctx.getSystemService<AlarmManager>() ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val existing = PendingIntent.getBroadcast(
            ctx, REQ + taskId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            am.cancel(existing)
            if (showToast) toast(ctx, ctx.getString(R.string.notification_cancelled))
        }
    }

    /* common toast helper */
    private fun toast(ctx: Context, msg: String) =
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
}
