package com.taskfree.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.taskfree.app.R

object NotificationUtil {
    const val CHANNEL_ID = "TASK_REMINDER_CH"
    fun ensureChannel(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService<NotificationManager>() ?: return CHANNEL_ID
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.reminder_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = ctx.getString(R.string.reminder_channel_desc)
                    }
                )
            }
        }
        return CHANNEL_ID
    }
}
