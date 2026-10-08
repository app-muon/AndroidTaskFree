package com.taskfree.app.notifications

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.useDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    @get:Rule val mainDispatcher = com.taskfree.app.testutil.MainDispatcherRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val future = Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000)
    private val journal get() = File(app.noBackupFilesDir, "encryption-migration.journal")

    @Before fun prepare() = runBlocking {
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
        app.getSharedPreferences("runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        AppDatabaseFactory.buildInternal(app, AppDatabaseFactory.DB_NAME).useDatabase {
            it.categoryDao().insertAll(listOf(Category(id = 1, title = "Tasks", color = 0)))
            it.taskDao().insertAll(listOf(
                Task(id = 1, categoryId = 1, text = "Reminder", singleCategoryPageOrder = 0, reminderTime = future)
            ))
        }
        Unit
    }

    @After fun clean() = runBlocking {
        journal.delete()
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
    }

    @Test fun `boot finishes only after future reminders are scheduled again`() = runBlocking {
        BootReceiver().restore(app, 0)

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(future.toEpochMilli(), alarm.triggerAtMs)
        assertEquals(AlarmReceiver.ACTION, shadowOf(alarm.operation).savedIntent.action)
        assertEquals(0, notifications.size())
    }

    @Test fun `unavailable startup sets bounded follow-ups and then posts one notice`() = runBlocking {
        Prefs.requestEncryption(app, listOf("pending")) // Migration mode waits for the activity.
        var retryCount = 0
        for (seconds in NotificationScheduler.RETRY_DELAYS_SECONDS) {
            RealDatabaseMigrator.resetProcessForTesting()
            val before = Instant.now()
            BootReceiver().restore(app, retryCount)
            val alarm = alarms.scheduledAlarms.single()
            val operation = requireNotNull(alarm.operation)
            assertTrue(alarm.triggerAtMs >= before.plusSeconds(seconds).toEpochMilli())
            assertTrue(alarm.triggerAtMs <= Instant.now().plusSeconds(seconds).toEpochMilli())
            val intent = shadowOf(operation).savedIntent
            assertEquals(BootReceiver.ACTION_RESTORE_REMINDERS, intent.action)
            retryCount = intent.getIntExtra(BootReceiver.EXTRA_RETRY_COUNT, -1)
            assertEquals(NotificationScheduler.RETRY_DELAYS_SECONDS.indexOf(seconds) + 1, retryCount)
            app.getSystemService(AlarmManager::class.java).cancel(operation) // Consume it without delivery.
        }
        RealDatabaseMigrator.resetProcessForTesting()
        BootReceiver().restore(app, retryCount)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, notifications.size())
        assertTrue(Prefs.reminderNoticePosted(app))
    }

    @Test fun `states needing the user post the notice without follow-ups`() = runBlocking {
        journal.writeText("invalid")
        BootReceiver().restore(app, 0)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, notifications.size())
    }

    @Test fun `clearing without a posted notice does not write settings`() {
        val runtime = app.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)
        ReminderAccessNotice.clear(app)
        assertFalse(runtime.contains("reminderNotice"))
    }
}
