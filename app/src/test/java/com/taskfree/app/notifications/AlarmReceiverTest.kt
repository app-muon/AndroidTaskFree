package com.taskfree.app.notifications

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
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
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AlarmReceiverTest {
    @get:Rule val mainDispatcher = com.taskfree.app.testutil.MainDispatcherRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val reminder = Instant.ofEpochMilli(System.currentTimeMillis() - 30_000)
    private val task = Task(id = 1, categoryId = 1, text = "Reminder", singleCategoryPageOrder = 0, reminderTime = reminder)

    @Before fun prepare() = runBlocking {
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
        app.getSharedPreferences("runtime_state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        AppDatabaseFactory.buildInternal(app, AppDatabaseFactory.DB_NAME).useDatabase {
            it.categoryDao().insertAll(listOf(Category(id = 1, title = "Tasks", color = 0)))
            it.taskDao().insertAll(listOf(task))
        }
        Unit
    }

    @After fun clean() = runBlocking {
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
    }

    @Test fun boundedRetriesSurviveProcessResetAndPreserveStoredReminder() = runBlocking {
        Prefs.requestEncryption(app, listOf("pending"))
        var retryCount = 0
        var identity: android.app.PendingIntent? = null
        for (seconds in listOf(60L, 300L, 900L)) {
            RealDatabaseMigrator.resetProcessForTesting()
            val before = Instant.now()
            AlarmReceiver().deliver(app, 1, retryCount)
            val alarm = alarms.scheduledAlarms.single()
            val operation = requireNotNull(alarm.operation)
            assertTrue(alarm.triggerAtMs >= before.plusSeconds(seconds).toEpochMilli())
            assertTrue(alarm.triggerAtMs <= Instant.now().plusSeconds(seconds).toEpochMilli())
            identity?.let { assertEquals(it, operation) }
            identity = operation
            retryCount = shadowOf(operation).savedIntent.getIntExtra(AlarmReceiver.EXTRA_RETRY_COUNT, -1)
            assertEquals(listOf(60L, 300L, 900L).indexOf(seconds) + 1, retryCount)
            app.getSystemService(AlarmManager::class.java).cancel(operation) // Consume it without real delivery.
        }
        RealDatabaseMigrator.resetProcessForTesting()
        AlarmReceiver().deliver(app, 1, retryCount)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, notifications.size())
        val notice = notifications.allNotifications.single()
        assertEquals(app.getString(com.taskfree.app.R.string.reminder_open_app),
            notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(Prefs.reminderNoticePosted(app))
        RealDatabaseMigrator.resetProcessForTesting()
        AlarmReceiver().deliver(app, 1, retryCount)
        assertEquals(1, notifications.size())
        NotificationScheduler.scheduleSilently(app, 1, Instant.now().plusSeconds(3_600))
        val normal = alarms.scheduledAlarms.single()
        val normalOperation = requireNotNull(normal.operation)
        assertEquals(identity, normalOperation)
        assertEquals(0, shadowOf(normalOperation).savedIntent.getIntExtra(AlarmReceiver.EXTRA_RETRY_COUNT, -1))
        app.getSystemService(AlarmManager::class.java).cancel(normalOperation)
        AppDatabaseFactory.buildInternal(app, AppDatabaseFactory.DB_NAME).useDatabase {
            assertEquals(reminder, it.taskDao().taskById(1)!!.reminderTime)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowToast.getTextOfLatestToast())
        // States needing user input stop immediately, even before consuming any retries.
        RealDatabaseMigrator.resetProcessForTesting()
        Prefs.finishEncryptionAttempt(app, null)
        java.io.File(app.noBackupFilesDir, "encryption-migration.journal").writeText("invalid")
        AlarmReceiver().deliver(app, 1)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, notifications.size())
        java.io.File(app.noBackupFilesDir, "encryption-migration.journal").delete()
        RealDatabaseMigrator.retryRecovery(app)
        RealDatabaseMigrator.start(app).join()
        RealDatabaseMigrator.onForeground(app)
        assertEquals(0, notifications.size())
        assertFalse(Prefs.reminderNoticePosted(app))
        assertNull(Prefs.encryptionOutcome(app))
        assertTrue(alarms.scheduledAlarms.isEmpty()) // No replay of the expired reminder.
    }

    @Test fun `retry re-reads cancellation deletion task archive and category archive`() = runBlocking {
        RealDatabaseMigrator.start(app).join()
        val db = AppDatabaseFactory.getDatabase(app)
        val cancelled = task.copy(reminderTime = null)
        for (replacement in listOf(cancelled, task.copy(isArchived = true), null)) {
            db.taskDao().deleteAll()
            replacement?.let { db.taskDao().insertAll(listOf(it)) }
            AlarmReceiver().deliver(app, 1)
            assertEquals(0, notifications.size())
        }
        db.taskDao().insertAll(listOf(task))
        db.categoryDao().insertAll(listOf(Category(id = 1, title = "Tasks", color = 0, isDeleted = true)))
        AlarmReceiver().deliver(app, 1)
        assertEquals(0, notifications.size())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun `retry uses future stored time and not the obsolete firing time`() = runBlocking {
        RealDatabaseMigrator.start(app).join()
        val moved = Instant.now().plusSeconds(3_600)
        AppDatabaseFactory.getDatabase(app).taskDao().update(task.copy(reminderTime = moved))
        AlarmReceiver().deliver(app, 1)
        assertEquals(moved.toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
        assertEquals(0, notifications.size())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
