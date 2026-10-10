package com.taskfree.app.data.backup

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.taskfree.app.R

/**
 * Keeps the process running while a backup save finishes after the user leaves the app,
 * which Android otherwise freezes within seconds. Android 12+ delays the notification by
 * about 10 s, so it normally never appears.
 */
class BackupSaveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stop = Runnable { stopSelf() }
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first: stopping before startForeground crashes the app.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        )
        lastStartId = startId
        running = this
        if (holds == 0) stopSelf(startId)
        else {
            // Below API 34 the system sets no time limit, so a stuck write must not keep this running.
            handler.removeCallbacks(stop)
            handler.postDelayed(stop, MAX_RUN_MS)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = stopSelf()

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        handler.removeCallbacks(stop)
        if (running === this) running = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.backup_channel_name))
                .build()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.backup_saving_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "BackupSaveService"
        private const val CHANNEL_ID = "BACKUP_CH"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_RUN_MS = 2 * 60_000L

        // Both touched only on the main thread.
        private var holds = 0
        private var running: BackupSaveService? = null

        /** Must be called while the app is visible; returns false if the service could not start. */
        @MainThread
        fun hold(context: Context): Boolean {
            holds++
            return try {
                ContextCompat.startForegroundService(context, Intent(context, BackupSaveService::class.java))
                true
            } catch (e: Exception) {
                holds--
                Log.w(TAG, "Could not start the backup service; saving without it", e)
                false
            }
        }

        /** Pairs with a successful [hold]; may be called from any thread. */
        fun release(context: Context) = ContextCompat.getMainExecutor(context).execute {
            holds--
            if (holds == 0) running?.let { it.stopSelf(it.lastStartId) }
        }
    }
}
