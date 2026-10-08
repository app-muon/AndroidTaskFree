// ResetAppStateRule.kt
package com.taskfree.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.MigrationHooks
import com.taskfree.app.data.useDatabase
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.preferences.TipPreferences
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
    private val previousHooks = RealDatabaseMigrator.hooks
    private val previousRestart = RealDatabaseMigrator.restart

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    override fun before() {
        newProcess()
        RealDatabaseMigrator.hooks = MigrationHooks()
        RealDatabaseMigrator.resetEncryption(ctx)
        RealDatabaseMigrator.restart = { error("Tests must inject restart requests, never kill the runner") }
        ctx.getSharedPreferences("runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("task_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TaskStatusFilter.resetForTesting()
        runBlocking {
            val tips = TipPreferences(ctx)
            tips.clearAll()
            TipId.entries.forEach { tips.markSeen(it) }
        }
    }

    override fun after() {
        try {
            newProcess()
            RealDatabaseMigrator.hooks = MigrationHooks()
            RealDatabaseMigrator.resetEncryption(ctx)
            ctx.getSharedPreferences("runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            RealDatabaseMigrator.hooks = previousHooks
            RealDatabaseMigrator.restart = previousRestart
        }
    }

    /** Writes rows into the app's real database before the activity is launched. */
    fun <T> seed(block: suspend AppDatabase.() -> T): T =
        runBlocking {
            val ready = runCatching { AppDatabaseFactory.getDatabase(ctx) }.getOrNull()
            if (ready != null) ready.block()
            else AppDatabaseFactory.buildInternal(ctx, DB_NAME,
                if (Prefs.isEncrypted(ctx)) Prefs.loadDerivedKey(ctx) else null).useDatabase { it.block() }
        }

    fun db(): AppDatabase {
        runBlocking { RealDatabaseMigrator.start(ctx).join() }
        return AppDatabaseFactory.getDatabase(ctx)
    }

    fun newProcess() = runBlocking { RealDatabaseMigrator.resetProcessForTesting() }

    private companion object {
        const val DB_NAME = "checklists.db"
    }
}
