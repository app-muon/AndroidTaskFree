package com.taskfree.app.notifications

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.ReceiverDatabase
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.util.format
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
        if (taskId == -1) return                         // safety-net

        /* ─── 1 ▸ fetch task + category in one query (suspending, so wrap) ─── */
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { deliver(context.applicationContext, taskId, intent.getIntExtra(EXTRA_RETRY_COUNT, 0)) }
            catch (e: Exception) {
                android.util.Log.e("AlarmReceiver", "Reminder delivery failed", e)
            } finally { pending.finish() }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    internal suspend fun deliver(context: Context, taskId: Int, retryCount: Int = 0) {
        val row = try {
            when (val access = RealDatabaseMigrator.startupDatabase(context)) {
                is ReceiverDatabase.Ready -> access.database.taskDao().taskWithCatById(taskId) ?: return
                ReceiverDatabase.RetryLater -> {
                    NotificationScheduler.retryWhenAvailable(context, taskId, retryCount)
                    return
                }
                ReceiverDatabase.NeedsUserAction -> {
                    ReminderAccessNotice.post(context)
                    return
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmReceiver", "Reminder deferred until database is available", e)
            NotificationScheduler.retryWhenAvailable(context, taskId, retryCount)
            return
        }
        val reminder = row.reminderTime ?: return
        if (reminder > Instant.now()) {
            NotificationScheduler.scheduleSilently(context, taskId, reminder)
            return
        }

        /* ─── 2 ▸ craft title + body ─── */
        val title = context.getString(R.string.notification_reminder, row.text)

        val body = buildList {
            if (row.recurrence != Recurrence.NONE) {
                val rule = RepeatRule.fromStoredOrNull(row.recurrence, row.recurrenceInterval)
                if (rule == null) android.util.Log.e("AlarmReceiver", "Invalid repeat rule for task $taskId")
                add(context.getString(R.string.notification_repeat, rule.format(context)))
            }
            add(context.getString(R.string.notification_category, row.catTitle))
        }.joinToString("\n")

        /* ─── 3 ▸ notification ─── */
        val chanId = NotificationUtil.ensureChannel(context)
        val contentPi = MainActivity.pendingIntent(context, taskId)

        val notif = NotificationCompat.Builder(context, chanId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor((row.catColor and 0xFFFFFFFF).toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(taskId, notif)
    }


    companion object {
        const val ACTION = "com.taskfree.app.REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_RETRY_COUNT = "retry_count"
    }
}
