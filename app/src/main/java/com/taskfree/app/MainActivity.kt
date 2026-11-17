package com.taskfree.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.backup.AutoBackup
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.notifications.AlarmReceiver
import com.taskfree.app.ui.AppNav
import com.taskfree.app.ui.onboarding.LocalTipManager
import com.taskfree.app.ui.onboarding.TipManager
import com.taskfree.app.util.db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    var notificationTaskId by mutableStateOf<Int?>(null)
        private set
    var notificationRequestId by mutableLongStateOf(0L)
        private set
    private var notificationNoticeShown = false

    fun consumeNotification(taskId: Int, requestId: Long = notificationRequestId) {
        if (notificationTaskId == taskId && notificationRequestId == requestId) notificationTaskId = null
    }

    fun showDeferredNotificationNotice(requestId: Long) {
        if (notificationRequestId != requestId || notificationNoticeShown) return
        notificationNoticeShown = true
        Toast.makeText(this, R.string.notification_finish_editing, Toast.LENGTH_SHORT).show()
    }

    // Permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Handle permission denied - maybe show a dialog explaining why you need it
            // For now, just log it
            android.util.Log.w("MainActivity", "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationTaskId = if (savedInstanceState != null) {
            savedInstanceState.getInt(PENDING_NOTIFICATION, -1).takeIf { it > 0 }
        } else notificationTask(intent)
        notificationRequestId = savedInstanceState?.getLong(NOTIFICATION_REQUEST) ?: 0L
        notificationNoticeShown = savedInstanceState?.getBoolean(NOTIFICATION_NOTICE) ?: false
        intent.removeExtra(AlarmReceiver.EXTRA_TASK_ID)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()
        RealDatabaseMigrator.start(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RealDatabaseMigrator.startup.first { it == com.taskfree.app.data.DatabaseStartup.READY }
                try {
                    reindexGate.withLock {
                        withContext(Dispatchers.IO) { TaskRepository(application.db).reindexAllTaskPageOrders() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("MainActivity", "Reindex failed", e) }
                AutoBackup.onForeground(applicationContext)
            }
        }

        setContent {
            val tipManager = remember { TipManager(applicationContext) }
            val inDarkMode = isSystemInDarkTheme()

            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
                    !inDarkMode
            }

            CompositionLocalProvider(LocalTipManager provides tipManager) {
                AppNav()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(PENDING_NOTIFICATION, notificationTaskId ?: -1)
        outState.putLong(NOTIFICATION_REQUEST, notificationRequestId)
        outState.putBoolean(NOTIFICATION_NOTICE, notificationNoticeShown)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTask(intent)?.let {
            notificationTaskId = it
            notificationRequestId++
            notificationNoticeShown = false
        }
        intent.removeExtra(AlarmReceiver.EXTRA_TASK_ID)
    }

    private fun notificationTask(intent: Intent): Int? =
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null
        else intent.getIntExtra(AlarmReceiver.EXTRA_TASK_ID, -1).takeIf { it > 0 }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RealDatabaseMigrator.onForeground(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        // Still visible here, which a save that needs the foreground service requires.
        AutoBackup.onLeaving(applicationContext)
    }

    override fun onStop() {
        RealDatabaseMigrator.onBackground()
        super.onStop()
    }

    companion object {
        private const val PENDING_NOTIFICATION = "pending_notification_task"
        private const val NOTIFICATION_REQUEST = "pending_notification_request"
        private const val NOTIFICATION_NOTICE = "pending_notification_notice"
        private val reindexGate = Mutex()
        fun pendingIntent(ctx: Context, taskId: Int): PendingIntent = PendingIntent.getActivity(
            ctx, (12345 + taskId), Intent(ctx, MainActivity::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
