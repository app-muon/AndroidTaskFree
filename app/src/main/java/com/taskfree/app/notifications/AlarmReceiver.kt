package com.taskfree.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.domain.model.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
        if (taskId == -1) return                         // safety-net

        /* ─── 1 ▸ fetch task + category in one query (suspending, so wrap) ─── */
        val dao = AppDatabaseFactory.getDatabase(context).taskDao()

        val row = runBlocking(Dispatchers.IO) { dao.taskWithCatById(taskId) } ?: return

        /* ─── 2 ▸ craft title + body ─── */
        val title = "Reminder: ${row.text}"

        val body = buildList {
            if (row.recurrence != Recurrence.NONE) add("Repeats: ${row.recurrence}")
            add("Category: ${row.catTitle}")
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
    }
}
