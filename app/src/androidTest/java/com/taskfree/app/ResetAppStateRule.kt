// ResetAppStateRule.kt
package com.taskfree.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.preferences.TipPreferences
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.onboarding.TipId
import com.taskfree.app.ui.task.TaskStatusFilter
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Gives every instrumented test a fresh, unencrypted app: empty database, no encryption
 * flags, default status filter, and all onboarding tips marked as seen so overlays only
 * appear where the app forces them (empty category / task lists).
 *
 * Note: this wipes the app's data on the device under test.
 */
class ResetAppStateRule : ExternalResource() {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    override fun before() {
        AppDatabaseFactory.clearInstance()
        ctx.deleteDatabase(DB_NAME)
        ctx.deleteDatabase(AppDatabaseFactory.TEMP_DB_NAME)
        Prefs.clearEncryption(ctx)
        DatabaseKeyManager.clearCachedKey()
        ctx.getSharedPreferences("task_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TaskStatusFilter.resetForTesting()
        runBlocking {
            val tips = TipPreferences(ctx)
            tips.clearAll()
            TipId.entries.forEach { tips.markSeen(it) }
        }
    }

    override fun after() {
        AppDatabaseFactory.clearInstance()
        ctx.deleteDatabase(DB_NAME)
        ctx.deleteDatabase(AppDatabaseFactory.TEMP_DB_NAME)
        Prefs.clearEncryption(ctx)
        DatabaseKeyManager.clearCachedKey()
    }

    /** Writes rows into the app's real database before the activity is launched. */
    fun <T> seed(block: suspend AppDatabase.() -> T): T =
        runBlocking { AppDatabaseFactory.getDatabase(ctx).block() }

    fun db(): AppDatabase = AppDatabaseFactory.getDatabase(ctx)

    private companion object {
        const val DB_NAME = "checklists.db"
    }
}
