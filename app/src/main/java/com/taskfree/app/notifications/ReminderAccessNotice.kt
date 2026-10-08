package com.taskfree.app.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taskfree.app.MainActivity
import com.taskfree.app.Prefs
import com.taskfree.app.R

/** No task content is available or retained while database access is blocked. */
internal object ReminderAccessNotice {
    internal const val TAG = "reminder-access"
    internal const val ID = 1

    @Synchronized
    @SuppressLint("MissingPermission")
    fun post(context: Context) {
        try {
            if (Prefs.reminderNoticePosted(context)) return
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.notify(TAG, ID, NotificationCompat.Builder(context, NotificationUtil.ensureChannel(context))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.reminder_open_app))
                .setContentIntent(MainActivity.pendingIntent(context, -1))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build())
            Prefs.setReminderNoticePosted(context, true)
        } catch (e: Exception) {
            Log.e("ReminderAccessNotice", "Could not post reminder access notice", e)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        // Attempt both operations even if one fails; a later foreground can retry.
        // Cancelling needs no disk access; the flag is written only when it is set.
        try { NotificationManagerCompat.from(context).cancel(TAG, ID) }
        catch (e: Exception) { Log.e("ReminderAccessNotice", "Could not clear reminder notice", e) }
        try { if (Prefs.reminderNoticePosted(context)) Prefs.setReminderNoticePosted(context, false) }
        catch (e: Exception) { Log.e("ReminderAccessNotice", "Could not clear reminder notice flag", e) }
    }
}
