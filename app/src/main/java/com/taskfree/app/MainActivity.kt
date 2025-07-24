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
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.notifications.AlarmReceiver
import com.taskfree.app.ui.AppNav
import com.taskfree.app.ui.enc.MnemonicManager
import com.taskfree.app.ui.onboarding.LocalTipManager
import com.taskfree.app.ui.onboarding.TipManager
import com.taskfree.app.util.db
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val repo by lazy {
        if (Prefs.isEncrypted(this) && !MnemonicManager.hasKey(this)) {
            throw IllegalStateException("Cannot initialize repository - encryption key not available")
        }
        TaskRepository(application.db)
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()

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

    override fun onStart() {
        super.onStart()

        // Only access repo if encryption is properly set up
        if (!Prefs.isEncrypted(this) || MnemonicManager.hasKey(this)) {
            lifecycleScope.launch {
                repo.reindexAllTaskPageOrders()
            }
        }
    }
    companion object {
        fun pendingIntent(ctx: Context, taskId: Int): PendingIntent = PendingIntent.getActivity(
            ctx, (12345 + taskId), Intent(ctx, MainActivity::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}