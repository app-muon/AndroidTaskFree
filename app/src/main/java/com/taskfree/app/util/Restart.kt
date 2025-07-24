// util/Restart.kt
package com.taskfree.app.util

import android.content.Context
import android.content.Intent
import android.os.Process

fun Context.restartApp() {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
        ?: return                                           // should never happen
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivity(launch)
    Process.killProcess(Process.myPid())                   // full cold start
}
