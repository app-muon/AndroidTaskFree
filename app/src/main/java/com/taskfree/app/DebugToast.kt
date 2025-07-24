package com.taskfree.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Non-composable: can be called from any thread */
fun debugToast(context: Context, msg: String) {
    if (!BuildConfig.DEBUG) return

    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    } else {                         // post to main looper
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

