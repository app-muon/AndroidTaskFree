package com.taskfree.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskfree.app.util.db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = ctx.db.taskDao()
            val now = Instant.now()
            dao.upcomingReminders(now).forEach { (taskId, reminderTime) ->
                NotificationScheduler.schedule(ctx, taskId, reminderTime)
            }
        }
    }
}
