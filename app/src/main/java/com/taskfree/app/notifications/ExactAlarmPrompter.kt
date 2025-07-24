package com.taskfree.app.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.getSystemService

object ExactAlarmPrompter {
    private var shown = false
    fun prompt(ctx: Context) {
        if (shown) return
        shown = true
        val am = ctx.getSystemService<AlarmManager>() ?: return
        if (am.canScheduleExactAlarms()) return
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
