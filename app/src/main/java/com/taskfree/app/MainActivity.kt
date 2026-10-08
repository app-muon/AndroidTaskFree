package com.taskfree.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.notifications.AlarmReceiver
import com.taskfree.app.ui.AppNav
import kotlinx.coroutines.flow.first
import com.taskfree.app.ui.onboarding.LocalTipManager
import com.taskfree.app.ui.onboarding.TipManager
import com.taskfree.app.util.db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : FragmentActivity() {


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

    override fun onStop() {
        RealDatabaseMigrator.onBackground()
        super.onStop()
    }

    companion object {
        private val reindexGate = Mutex()
        fun pendingIntent(ctx: Context, taskId: Int): PendingIntent = PendingIntent.getActivity(
            ctx, (12345 + taskId), Intent(ctx, MainActivity::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
