// ui/SmokeTest.kt
package com.taskfree.app.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.Prefs
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.MigrationHooks
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.ui.enc.fetchWords
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.Lifecycle
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.ui.components.TOOLS_MENU_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.taskfree.app.ui.task.components.DATE_FILTER_TAG
import com.taskfree.app.ui.task.components.SEARCH_TOGGLE_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val reset = ResetAppStateRule()
    private val compose = createEmptyComposeRule()

    private val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RuleChain.emptyRuleChain()
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(reset).around(notifications).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /* ---------- helpers ---------- */

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForGone(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    private fun taskRow(text: String) = hasContentDescription(text, substring = true)

    /** The app force-shows a "create your first …" tip on empty lists; it covers the screen. */
    private fun dismissForcedTip() {
        val gotIt = hasText(str(R.string.got_it_confirmation))
        waitFor(gotIt)
        compose.onNode(gotIt).performClick()
        waitForGone(gotIt)
    }

    /**
     * The task screen debounces its "add your first task" tip with delay(300); under the
     * Compose test clock that delay can elapse before Room has loaded, so the tip may
     * appear over a non-empty list. Dismiss it if it shows up.
     */
    private fun dismissTipIfShown(timeoutMs: Long = 2_000) {
        val gotIt = hasText(str(R.string.got_it_confirmation))
        if (runCatching { waitFor(gotIt, timeoutMs) }.isSuccess) {
            compose.onNode(gotIt).performClick()
            waitForGone(gotIt)
        }
    }

    private fun seedCategory(title: String = "Home"): Int = reset.seed {
        categoryDao().insertAll(listOf(Category(title = title, color = 0xFF3F51B5))).single().toInt()
    }

    private fun seedTask(categoryId: Int, text: String, order: Int = 0): Int = reset.seed {
        taskDao().insert(
            Task(
                categoryId = categoryId,
                text = text,
                due = LocalDate.now(),
                singleCategoryPageOrder = order,
                allCategoryPageOrder = order
            )
        ).toInt()
    }

    /* ---------- tests ---------- */

    @Test
    fun firstLaunch_addCategoryFromEmptyState() {
        launch()
        waitFor(hasText(str(R.string.no_categories_yet)))
        dismissForcedTip()

        compose.onNodeWithText(str(R.string.add_category_button_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Groceries")
        compose.onNodeWithText(str(R.string.add_category_yes_dialog_button)).performClick()

        waitFor(hasText("Groceries"))
        assertEquals(
            listOf("Groceries"),
            runBlocking { reset.db().categoryDao().getAllNow().map { it.title } }
        )
    }

    @Test
    fun addTask_appearsInTodayList() {
        seedCategory()
        launch()
        dismissForcedTip()   // no tasks yet

        compose.onNodeWithText(str(R.string.add_task_button_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Buy milk")
        compose.onNodeWithText(str(R.string.save_yes_dialog_button)).performClick()

        waitFor(taskRow("Buy milk"))
        val saved = runBlocking { reset.db().taskDao().getAllNow() }.single()
        assertEquals("Buy milk", saved.text)
        assertEquals(LocalDate.now(), saved.due)
    }

    @Test
    fun markTaskDone_fromOptionsPanel() {
        val cat = seedCategory()
        val id = seedTask(cat, "Water plants")
        launch()
        waitFor(taskRow("Water plants"))
        dismissTipIfShown()

        compose.onNode(taskRow("Water plants")).performClick()
        val setDone = str(R.string.set_as_for_task_status, str(R.string.done_status))
        waitFor(isDialog())
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(setDone))
        compose.onNodeWithText(setDone).performClick()

        compose.waitUntil(10_000) {
            runBlocking { reset.db().taskDao().taskById(id)?.status } == TaskStatus.DONE
        }
        assertEquals(LocalDate.now(), runBlocking { reset.db().taskDao().taskById(id)!!.completedDate })
    }

    @Test
    fun creationDate_isShownInTaskDetails() {
        val cat = seedCategory()
        val id = seedTask(cat, "Water plants")
        val originalCreatedAt = Instant.parse("2025-07-15T12:00:00Z")
        reset.seed {
            taskDao().update(taskDao().taskById(id)!!.copy(
                originalCreatedAt = originalCreatedAt,
                occurrenceCreatedAt = Instant.parse("2026-10-06T08:00:00Z"),
                status = TaskStatus.DONE,
                completedDate = LocalDate.now()
            ))
        }
        launch()
        waitFor(taskRow("Water plants"))
        dismissTipIfShown()

        compose.onNode(taskRow("Water plants")).performClick()
        val label = str(R.string.created_date_label).uppercase() + ":"
        waitFor(hasText(label))
        // The formatter has independent fixed-output unit tests; this checks field selection and layout.
        compose.onNode(hasText("2025", substring = true)).performScrollTo().assertIsDisplayed()
        val createdBounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val completedBounds = compose.onNodeWithText(str(R.string.completed_date_label).uppercase() + ":")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(createdBounds.top, completedBounds.top, 1f)
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                File(ctx.externalCacheDir, "task-creation-dates.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test
    fun legacyTask_showsCreationDateNotRecorded() {
        seedTask(seedCategory(), "Legacy task")
        launch()
        waitFor(taskRow("Legacy task"))
        dismissTipIfShown()

        compose.onNode(taskRow("Legacy task")).performClick()
        val label = str(R.string.created_date_label).uppercase() + ":"
        waitFor(hasText(label))
        compose.onNodeWithText(str(R.string.creation_date_not_recorded)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun search_filtersTheList() {
        val cat = seedCategory()
        seedTask(cat, "Buy milk", order = 0)
        seedTask(cat, "Email Bob", order = 1)
        launch()
        waitFor(taskRow("Buy milk"))
        waitFor(taskRow("Email Bob"))
        dismissTipIfShown()

        compose.onNodeWithTag(SEARCH_TOGGLE_TAG).performClick()
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("MILK")

        waitForGone(taskRow("Email Bob"))
        waitFor(taskRow("Buy milk"))
    }

    private fun openToolsAction(@StringRes action: Int) {
        compose.onNodeWithTag(TOOLS_MENU_TAG).performClick()
        waitFor(isDialog())
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(str(action)))
        compose.onNodeWithText(str(action)).performClick()
    }

    @Test
    fun toolsBackupFile_showsOffThenOpensPanelWithSetupRestoreAndExport() {
        val cat = seedCategory()
        seedTask(cat, "Keep visible")
        launch()
        waitFor(taskRow("Keep visible"))
        dismissTipIfShown()

        compose.onNodeWithTag(TOOLS_MENU_TAG).performClick()
        waitFor(isDialog())
        val dialogList = hasScrollToNodeAction() and hasAnyAncestor(isDialog())
        compose.onNode(dialogList).performScrollToNode(hasText(str(R.string.backup_file_title)))
        compose.onNodeWithText(str(R.string.backup_off)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_file_title)).performClick()

        waitFor(hasText(str(R.string.backup_file_intro)))
        for (action in listOf(R.string.backup_set_up, R.string.restore_from_file, R.string.backup_export_unencrypted)) {
            compose.onNode(dialogList).performScrollToNode(hasText(str(action)))
            compose.onNodeWithText(str(action)).assertIsDisplayed()
        }
        compose.onNodeWithText(str(R.string.backup_export_unencrypted)).performClick()
        waitFor(hasText(str(R.string.backup_export_warning)))
        compose.onNodeWithText(str(R.string.cancel_no_dialog_button)).performClick()
        waitForGone(hasText(str(R.string.backup_export_warning)))
    }

    @Test
    fun toolsArchiving_cancelThenConfirmRefreshesWithoutReopeningMenu() {
        val cat = seedCategory()
        seedTask(cat, "Keep visible")
        val repeat = seedTask(cat, "Old repeat", 1)
        val once = seedTask(cat, "Old one-off", 2)
        reset.seed {
            taskDao().update(taskDao().taskById(repeat)!!.copy(
                status = TaskStatus.DONE, completedDate = LocalDate.now().minusDays(1),
                recurrence = Recurrence.DAILY, baseDate = LocalDate.now()))
            taskDao().update(taskDao().taskById(once)!!.copy(
                status = TaskStatus.DONE, completedDate = LocalDate.now().minusDays(1)))
        }
        launch()
        waitFor(taskRow("Keep visible"))
        dismissTipIfShown()
        compose.onNodeWithTag(DATE_FILTER_TAG).performClick()
        compose.onNodeWithText(str(R.string.all_dates)).performClick()
        waitFor(taskRow("Old repeat"))

        openToolsAction(R.string.archive_old_completed_repeats)
        waitFor(hasText(str(R.string.confirm_archive_repeats_msg)))
        waitFor(hasText(str(R.string.archive_repeats_total)))
        captureArchiveScreenshot("archive-repeats-confirmation")
        compose.onNodeWithText(str(R.string.cancel_no_dialog_button)).performClick()
        assertFalse(runBlocking { reset.db().taskDao().taskById(repeat)!!.isArchived })
        waitFor(taskRow("Old repeat"))

        openToolsAction(R.string.archive_old_completed_repeats)
        compose.onNodeWithText(str(R.string.archive_task_yes_dialog_button)).performClick()
        waitForGone(taskRow("Old repeat"))
        waitFor(taskRow("Old one-off"))
        openToolsAction(R.string.archive_old_completed)
        compose.onNodeWithText(str(R.string.archive_task_yes_dialog_button)).performClick()
        waitForGone(taskRow("Old one-off"))
        waitFor(taskRow("Keep visible"))
        assertTrue(runBlocking { reset.db().taskDao().taskById(repeat)!!.isArchived })
        assertTrue(runBlocking { reset.db().taskDao().taskById(once)!!.isArchived })
    }

    @Test
    fun archiveRepeats_longListScrollsBetweenFixedTitleAndButtons() {
        val cat = seedCategory()
        seedTask(cat, "Keep visible")
        val repeats = (1..30).map { seedTask(cat, "Repeat %02d".format(it), it) }
        reset.seed {
            repeats.forEach { id ->
                taskDao().update(taskDao().taskById(id)!!.copy(
                    status = TaskStatus.DONE, completedDate = LocalDate.now().minusDays(1),
                    recurrence = Recurrence.DAILY, baseDate = LocalDate.now()))
            }
        }
        launch()
        waitFor(taskRow("Keep visible"))
        dismissTipIfShown()

        openToolsAction(R.string.archive_old_completed_repeats)
        val lastRow = hasText("Repeat 30") and hasAnyAncestor(isDialog())
        val title = hasText(str(R.string.confirm_archive_repeats_title)) and hasAnyAncestor(isDialog())
        val archive = hasText(str(R.string.archive_task_yes_dialog_button)) and hasAnyAncestor(isDialog())
        waitFor(lastRow)
        compose.onNode(lastRow).assertIsNotDisplayed()
        compose.onNode(archive).assertIsDisplayed()
        captureArchiveScreenshot("archive-repeats-long-list")

        compose.onNode(hasText(str(R.string.archive_repeats_total)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(lastRow).assertIsDisplayed()
        compose.onNode(title).assertIsDisplayed()
        compose.onNode(archive).assertIsDisplayed()
        captureArchiveScreenshot("archive-repeats-long-list-scrolled")

        compose.onNode(archive).performClick()
        compose.waitUntil(10_000) {
            runBlocking { repeats.all { reset.db().taskDao().taskById(it)!!.isArchived } }
        }
    }

    @Test
    fun archiveSeries_confirmationAndRefreshIncludeOnlyLinkedLaterOccurrences() {
        val cat = seedCategory()
        val earlier = seedTask(cat, "Earlier")
        val selected = seedTask(cat, "Selected occurrence", 1)
        val middle = seedTask(cat, "Archived intermediate", 2)
        val later = seedTask(cat, "Edited later occurrence", 3)
        seedTask(cat, "Unrelated", 4)
        reset.seed {
            taskDao().update(taskDao().taskById(selected)!!.copy(sourceTaskId = earlier,
                recurrence = Recurrence.DAILY, baseDate = LocalDate.now()))
            taskDao().update(taskDao().taskById(middle)!!.copy(sourceTaskId = selected, isArchived = true))
            taskDao().update(taskDao().taskById(later)!!.copy(sourceTaskId = middle))
        }
        launch()
        waitFor(taskRow("Selected occurrence"))
        dismissTipIfShown()
        compose.onNode(taskRow("Selected occurrence")).performClick()
        waitFor(isDialog())
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(str(R.string.archive_series_action)))
        compose.onNodeWithText(str(R.string.archive_series_action)).performClick()
        waitFor(hasText(str(R.string.are_you_sure_you_want_to_archive_series)))
        captureArchiveScreenshot("archive-series-confirmation")
        compose.onNodeWithText(str(R.string.archive_task_yes_dialog_button)).performClick()
        waitForGone(taskRow("Selected occurrence"))
        waitForGone(taskRow("Edited later occurrence"))
        waitFor(taskRow("Earlier"))
        waitFor(taskRow("Unrelated"))
    }

    @Test
    fun legacyConflictRequiresExplicitSelectionAndLabelsUnreadableCopyUnavailable() {
        seedTask(seedCategory(), "Preserved task")
        val legacy = ctx.getDatabasePath("checklists_backup.db")
        legacy.writeText("conflicting backup")
        launch()
        waitFor(hasText(str(R.string.encryption_conflict_message)))
        compose.onNodeWithText(str(R.string.encryption_copy_unavailable), substring = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.encryption_use_selected)).assertIsNotEnabled()
        waitForGone(taskRow("Preserved task"))
        captureArchiveScreenshot("encryption-legacy-selection")
        assertEquals("conflicting backup", legacy.readText())
        compose.onNodeWithText(str(R.string.encryption_main_copy), substring = true).performClick()
        compose.onNodeWithText(str(R.string.encryption_use_selected)).performClick()
        waitFor(taskRow("Preserved task"))
        assertFalse(legacy.exists())
    }

    @Test
    fun encryptionAndReminderRegressionWalkthrough() {
        seedTask(seedCategory(), "Preserved during rotation")
        Prefs.requestEncryption(ctx, fetchWords().take(8))
        val checkpoint = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checkpoints = AtomicInteger()
        val restarts = AtomicInteger()
        RealDatabaseMigrator.restart = { restarts.incrementAndGet() }
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun checkpoint(db: AppDatabase) {
                if (checkpoints.incrementAndGet() == 1) {
                    checkpoint.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                super.checkpoint(db)
            }
        }
        try {
            launch()
            assertTrue(checkpoint.await(10, TimeUnit.SECONDS))
            scenario!!.recreate()
            waitFor(hasText(str(R.string.encrypting_header)))
            assertTrue(runCatching { AppDatabaseFactory.getDatabase(ctx) }.isFailure)
            scenario!!.moveToState(Lifecycle.State.CREATED)
            release.countDown()
            runBlocking { RealDatabaseMigrator.start(ctx).join() }
            assertEquals(0, restarts.get())
            assertEquals(3, checkpoints.get())
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            waitFor(hasText(str(R.string.encryption_restart_message)))
            compose.waitUntil { restarts.get() == 1 }
            captureArchiveScreenshot("encryption-restart-en")
            assertTrue(runCatching { AppDatabaseFactory.getDatabase(ctx) }.isFailure)
        } finally { release.countDown() }

        // Simulate the requested process restart without killing the instrumentation runner.
        scenario!!.close()
        reset.newProcess()
        RealDatabaseMigrator.hooks = MigrationHooks()
        launch()
        waitFor(taskRow("Preserved during rotation"))
        dismissTipIfShown()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var taskId = -1
        scenario!!.onActivity { taskId = it.taskId }
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        compose.waitUntil(10_000) { scenario!!.state == Lifecycle.State.CREATED }
        ctx.getSystemService(android.app.ActivityManager::class.java).appTasks
            .first { it.taskInfo.id == taskId }.moveToFront()
        compose.waitUntil(10_000) { scenario!!.state == Lifecycle.State.RESUMED }
        waitFor(taskRow("Preserved during rotation"))

        scenario!!.close()
        reset.newProcess()
        val journal = File(ctx.noBackupFilesDir, "encryption-migration.journal")
        journal.writeText("invalid")
        // Access restrictions must take precedence over an older cleanup warning.
        Prefs.finishEncryptionAttempt(ctx, Prefs.EncryptionOutcome.CLEANUP_WARNING)
        launch()
        waitFor(hasText(str(R.string.encryption_recovery_blocked)))
        runBlocking { com.taskfree.app.notifications.AlarmReceiver().deliver(ctx, 1) }
        assertTrue(Prefs.reminderNoticePosted(ctx))
        val notice = ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .single { it.tag == "reminder-access" }.notification
        assertEquals(str(R.string.reminder_open_app), notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).use { it.readBytes() }
        shell("cmd statusbar expand-notifications")
        Thread.sleep(700) // System UI animations run outside the Compose test clock.
        captureArchiveScreenshot("reminder-access-en")
        shell("cmd statusbar collapse")
        Thread.sleep(700)
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitForGone(hasText(str(R.string.encryption_recovery_blocked)))
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor(hasText(str(R.string.encryption_recovery_blocked)))
        compose.onNodeWithText(str(R.string.retry_recovery)).performClick()
        waitFor(hasText(str(R.string.encryption_recovery_blocked)))
        assertEquals("invalid", journal.readText())
        check(journal.delete()) // Repair the intentionally malformed test journal.
        compose.onNodeWithText(str(R.string.retry_recovery)).performClick()
        waitFor(taskRow("Preserved during rotation"))
        compose.waitUntil { !Prefs.reminderNoticePosted(ctx) }

        // Back/outside dismissal must never run Skip, including after a failed reset.
        scenario!!.close()
        reset.newProcess()
        Prefs.clearEncryptionSecrets(ctx)
        launch()
        waitFor(hasText(str(R.string.restore_found_body)))
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        automation.executeShellCommand("input tap 100 200").close()
        waitFor(hasText(str(R.string.restore_found_body)))
        assertTrue(ctx.getDatabasePath("checklists.db").exists())
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun delete(file: File) { throw java.io.IOException("reset unavailable") }
        }
        compose.onNodeWithText(str(R.string.skip)).performClick()
        waitFor(hasText(str(R.string.encryption_reset_failed), substring = true))
        captureArchiveScreenshot("encryption-reset-failed-en")
        RealDatabaseMigrator.hooks = MigrationHooks()
        compose.onNodeWithText(str(R.string.skip)).performClick()
        compose.waitUntil { RealDatabaseMigrator.startup.value == com.taskfree.app.data.DatabaseStartup.READY }
        assertFalse(Prefs.isEncrypted(ctx))
    }

    @Test
    fun rollbackFeedbackSurvivesBackgroundRecoveryAndDismissalDoesNotRestart() {
        seedTask(seedCategory(), "Recovered task")
        Prefs.requestEncryption(ctx, fetchWords().take(8))
        Prefs.startEncryptionAttempt(ctx) { _, editor -> editor.commit() }
        // Receiver startup waits for the activity before recovering a pending request.
        runBlocking { RealDatabaseMigrator.startupDatabase(ctx) }
        val restarts = AtomicInteger()
        RealDatabaseMigrator.restart = { restarts.incrementAndGet() }
        launch()
        waitFor(hasText(str(R.string.encryption_rolled_back)))
        captureArchiveScreenshot("encryption-rolled-back")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitForGone(hasText(str(R.string.encryption_rolled_back)))
        assertEquals(0, restarts.get())
        assertEquals(Prefs.EncryptionOutcome.ROLLED_BACK, Prefs.encryptionOutcome(ctx))
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor(hasText(str(R.string.encryption_rolled_back)))
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input tap 100 200").close()
        waitForGone(hasText(str(R.string.encryption_rolled_back)))
        assertEquals(0, restarts.get())
        assertEquals(Prefs.EncryptionOutcome.ROLLED_BACK, Prefs.encryptionOutcome(ctx))
        compose.onNodeWithText(str(R.string.encryption_failed_title)).performClick()
        waitFor(hasText(str(R.string.encryption_rolled_back)))
        compose.onNodeWithText(str(R.string.retry_encryption)).performClick()
        assertEquals(1, restarts.get())
        assertTrue(Prefs.encryptionPending(ctx))
        assertEquals(null, Prefs.encryptionOutcome(ctx))
    }

    @Test
    fun blockedRecoveryOffersRetryAndPreservesJournal() {
        seedTask(seedCategory(), "Preserved task")
        val journal = File(ctx.noBackupFilesDir, "encryption-migration.journal")
        journal.writeText("invalid")
        launch()
        waitFor(hasText(str(R.string.encryption_recovery_blocked)))
        captureArchiveScreenshot("encryption-recovery-blocked")
        compose.onNodeWithText(str(R.string.retry_recovery)).performClick()
        waitFor(hasText(str(R.string.encryption_recovery_blocked)))
        assertEquals("invalid", journal.readText())
        waitForGone(taskRow("Preserved task"))
    }

    @Test
    fun cleanupWarningAllowsTasksAndOffersRetry() {
        seedTask(seedCategory(), "Encrypted task")
        val failing = object : MigrationHooks() {
            override fun delete(file: File) {
                if (file.name == "encryption-rollback.db") throw java.io.IOException("cleanup")
                super.delete(file)
            }
        }
        runBlocking { RealDatabaseMigrator.migrateToEncrypted(ctx, fetchWords().take(8), failing) }
        reset.newProcess()
        RealDatabaseMigrator.hooks = failing
        launch()
        waitFor(hasText(str(R.string.encryption_cleanup_warning)))
        // Startup keeps restoring reminders after READY; a retry tapped before it ends is ignored.
        runBlocking { RealDatabaseMigrator.start(ctx).join() }
        captureArchiveScreenshot("encryption-cleanup-warning")
        compose.onNodeWithText(str(R.string.retry_cleanup)).assertIsDisplayed()
        RealDatabaseMigrator.hooks = MigrationHooks()
        compose.onNodeWithText(str(R.string.retry_cleanup)).performClick()
        waitForGone(hasText(str(R.string.encryption_cleanup_warning)))
        waitFor(taskRow("Encrypted task"))
        assertFalse(File(ctx.noBackupFilesDir, "encryption-rollback.db").exists())
    }

    private fun captureArchiveScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400) // Let the platform dialog/window animation finish before capture.
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(ctx.externalCacheDir, "$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screenshot.recycle()
        }
    }
}
