package com.taskfree.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.ReminderRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_RESTORE_REMINDERS) return
        val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                restore(ctx.applicationContext, retryCount)
            } finally { pending.finish() }
        }
    }

    /** Alarms do not survive a reboot; finish only once they are scheduled again or a follow-up is set. */
    internal suspend fun restore(context: Context, retryCount: Int) {
        val result = try {
            RealDatabaseMigrator.awaitReminderRestore(context)
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Reminder restore unavailable", e)
            ReminderRestore.RETRY_LATER
        }
        when (result) {
            ReminderRestore.RESTORED -> Unit
            ReminderRestore.RETRY_LATER -> NotificationScheduler.retryReminderRestore(context, retryCount)
            ReminderRestore.NEEDS_USER_ACTION -> ReminderAccessNotice.post(context)
        }
    }

    companion object {
        const val ACTION_RESTORE_REMINDERS = "com.taskfree.app.RESTORE_REMINDERS"
        const val EXTRA_RETRY_COUNT = "retry_count"
    }
}
