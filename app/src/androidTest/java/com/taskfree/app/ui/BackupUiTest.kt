package com.taskfree.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.backup.*
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.admin.BackupFileDialog
import com.taskfree.app.ui.admin.BackupMigrationBanner
import com.taskfree.app.ui.admin.ToolsViewModel
import com.taskfree.app.ui.components.formatDateTime
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackupUiTest {
    private val reset = ResetAppStateRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val locale get() = app.resources.configuration.locales[0]
    private val ctx get() = app
    private val folder = Uri.parse("content://test/tree/backups")
    private val time = Instant.parse("2026-10-10T12:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var finish: CountDownLatch? = null
    private lateinit var saver: BackupSaver
    private var disk: BackupSettings? = null
    private var commitWorks = true
    private var writable = true
    private var writeFailure: Exception? = null
    private var writeHook: (() -> Unit)? = null
    private val documents = mutableMapOf<Uri, ByteArray>()
    private val file = object : BackupFile {
        override fun retain(uri: Uri) {}
        override fun release(uri: Uri) {}
        override fun canWrite(uri: Uri) = writable
        override fun create(folder: Uri, name: String): BackupSlot =
            BackupSlot(Uri.parse("$folder/${documents.size}/$name"), name).also { documents[it.uri] = byteArrayOf() }
        override fun delete(uri: Uri) { documents.remove(uri) }
        override fun write(uri: Uri, bytes: ByteArray) {
            writeHook?.invoke()
            writeFailure?.let { throw it }
            documents[uri] = bytes
        }
        override fun verify(uri: Uri, size: Long, digest: String) {
            val bytes = documents.getValue(uri)
            if (bytes.size.toLong() != size || bytes.backupDigest() != digest) throw BackupVerificationMismatchException()
        }
    }

    @Before fun prepare() {
        val db = reset.db()
        Prefs.savePhrase(app, listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house"))
        saver = BackupSaver(app, file, { db }, { time }, 1_000, object : BackupSettingsStore {
            override fun load() = disk
            override fun commit(settings: BackupSettings?): Boolean {
                if (commitWorks) disk = settings
                return commitWorks
            }
        })
        AutoBackup.configureForTesting(saver)
    }

    @After fun finishWork() = runBlocking {
        finish?.countDown()
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        scope.cancel()
    }

    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private fun show(registry: ActivityResultRegistry? = null) {
        val db = reset.db()
        val vm = ToolsViewModel(TaskRepository(db), db)
        val owner = registry?.let { object : ActivityResultRegistryOwner {
            override val activityResultRegistry = it
        } }
        compose.setContent {
            var visible by rememberSaveable { mutableStateOf(true) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides (owner ?: checkNotNull(LocalActivityResultRegistryOwner.current))) {
                MaterialTheme {
                    TextButton(onClick = { visible = true }) { Text("Open backup panel") }
                    BackupFileDialog(vm, show = visible, onShow = { visible = true },
                        onDismiss = { if (registry != null) visible = false })
                }
            }
        }
    }

    private class FolderPicker : ActivityResultRegistry() {
        private var request = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                    options: ActivityOptionsCompat?) { request = requestCode }
        fun finish(uri: Uri?) { dispatchResult(request, uri) }
    }

    @Test fun pickerReturnWhileBusy_reportsRejectionBeforeCloudConfirmation() {
        val picker = FolderPicker()
        show(picker)
        row(R.string.backup_set_up).performScrollTo().performClick()
        compose.runOnIdle { picker.finish(null) }
        org.junit.Assert.assertTrue(documents.isEmpty())
        compose.onNodeWithText("Open backup panel").performClick()
        row(R.string.backup_set_up).performScrollTo().performClick()
        blockWrites()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val event = instrumentation.uiAutomation.executeAndWaitForEvent({
            instrumentation.runOnMainSync { picker.finish(Uri.parse("content://cloud/tree/chosen")) }
        }, {
            it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                it.text.any { text -> text.toString() == str(R.string.backup_setup_busy) }
        }, 5_000)
        event.recycle()
        compose.onNodeWithText(str(R.string.backup_cloud_title)).assertDoesNotExist()
        org.junit.Assert.assertNull(disk)
        finish!!.countDown()
        compose.waitUntil(10_000) { !saver.status.value.saving }
    }

    @Test fun localPicker_startedSetupReopensPanelAndShowsDelayedFailure() = setupFromPicker(cloud = false)

    @Test fun cloudPicker_startedSetupReopensPanelAndShowsDelayedFailure() = setupFromPicker(cloud = true)

    private fun setupFromPicker(cloud: Boolean) {
        val picker = FolderPicker()
        show(picker)
        row(R.string.backup_set_up).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.backup_file_title)).assertDoesNotExist()
        val entered = CountDownLatch(1)
        finish = CountDownLatch(1)
        writeHook = { entered.countDown(); check(finish!!.await(30, TimeUnit.SECONDS)) }
        writeFailure = java.io.IOException("delayed provider failure")
        val uri = Uri.parse(if (cloud) "content://cloud/tree/chosen"
            else "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FBackups")
        compose.runOnIdle { picker.finish(uri) }
        if (cloud) {
            compose.onNodeWithText(str(R.string.backup_cloud_title)).assertIsDisplayed()
            compose.onNodeWithText(str(R.string.backup_use_anyway)).performClick()
        }
        check(entered.await(10, TimeUnit.SECONDS))
        compose.onNodeWithText(str(R.string.backup_file_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_saving)).assertIsDisplayed()
        disabled(R.string.backup_set_up)
        finish!!.countDown()
        compose.waitUntil(10_000) { saver.status.value.operationError != null && !saver.status.value.saving }
        compose.onNodeWithText(str(R.string.backup_setup_failed)).assertIsDisplayed()
        enabled(R.string.backup_set_up)
        capture(if (cloud) "backup-cloud-setup-failed" else "backup-local-setup-failed")
        // Successful setup also stays visible, including the current filenames.
        writeHook = null
        writeFailure = null
        row(R.string.backup_set_up).performScrollTo().performClick()
        compose.runOnIdle { picker.finish(uri) }
        if (cloud) compose.onNodeWithText(str(R.string.backup_use_anyway)).performClick()
        compose.waitUntil(10_000) { saver.status.value.settings != null && !saver.status.value.saving }
        assertSaved()
        disk!!.slots.forEach { compose.onNodeWithText(it.fileName).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(str(R.string.backup_last_saved_file, disk!!.fileName)).performScrollTo().assertIsDisplayed()
        capture("backup-current-pair")
    }

    @Test fun retryBanner_opensRecoveryPanelAndClearsAfterRecoveryOrDisable() {
        runBlocking { saver.enable(folder); writeFailure = java.io.IOException(); saver.save(true) }
        val db = reset.db()
        val vm = ToolsViewModel(TaskRepository(db), db)
        compose.setContent {
            var visible by rememberSaveable { mutableStateOf(false) }
            MaterialTheme {
                BackupMigrationBanner(onOpenBackup = { visible = true })
                BackupFileDialog(vm, visible, onShow = { visible = true }, onDismiss = { visible = false })
            }
        }
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_file_title)).performClick()
        singleFolderAction(R.string.backup_choose_folder)
        compose.onNodeWithText(str(R.string.backup_retry_recovery)).performScrollTo().assertIsDisplayed()
        enabled(R.string.backup_now)
        capture("backup-retry-recovery")
        runBlocking { writeFailure = null; saver.enable(folder) }
        compose.onNodeWithText(str(R.string.backup_retry_recovery)).assertDoesNotExist()
        // Dismiss the panel to inspect the underlying banner.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertDoesNotExist()
        runBlocking { writeFailure = java.io.IOException(); saver.save(true) }
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertIsDisplayed()
        runBlocking { saver.disable() }
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertDoesNotExist()
    }

    @Test fun recoveryBanner_prioritizesRestartThenMigrationAndIgnoresHistoricalFolderError() {
        legacy(BackupError.Kind.FAILED)
        compose.setContent { MaterialTheme { BackupMigrationBanner(onOpenBackup = {}) } }
        compose.onNodeWithText(str(R.string.backup_migration_notice)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertDoesNotExist()
        runBlocking { saver.usePhrase(); saver.enable(folder); writeFailure = java.io.IOException(); saver.enable(folder) }
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_migration_notice)).assertDoesNotExist()
        runBlocking { saver.save(true) }
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertIsDisplayed()
        commitWorks = false
        runBlocking { saver.disable() }
        compose.onNodeWithText(str(R.string.backup_error_settings)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_retry_notice)).assertDoesNotExist()
    }

    private fun row(id: Int) = compose.onNode(hasClickAction() and hasAnyDescendant(hasText(str(id))))
    private fun enabled(id: Int) { row(id).performScrollTo().assertIsEnabled() }
    private fun disabled(id: Int) { row(id).performScrollTo().assertIsNotEnabled() }
    private fun savedDate() = str(R.string.backup_saved_at, formatDateTime(time, locale, ZoneId.systemDefault()))
    private fun assertSaved() { compose.onNodeWithText(savedDate()).performScrollTo().assertIsDisplayed() }

    private fun blockWrites() {
        val entered = CountDownLatch(1)
        finish = CountDownLatch(1)
        writeHook = { entered.countDown(); check(finish!!.await(30, TimeUnit.SECONDS)) }
        scope.launch { if (disk == null) saver.enable(folder) else saver.save(true) }
        check(entered.await(10, TimeUnit.SECONDS))
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.onNode(hasText(str(R.string.backup_file_title)) and hasAnyAncestor(isDialog())).performScrollTo()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(app.externalCacheDir, "$name-${locale.language}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun firstSetup_showsProgressAndDisablesSetup() {
        blockWrites()
        show()
        compose.onNodeWithText(str(R.string.backup_saving)).assertIsDisplayed()
        disabled(R.string.backup_set_up)
        enabled(R.string.view_recovery_phrase)
        enabled(R.string.restore_from_file)
        enabled(R.string.backup_export_unencrypted)
        capture("backup-first-setup-busy")
        finish!!.countDown()
        compose.waitUntil(10_000) { !saver.status.value.saving }
        assertSaved()
    }

    @Test fun busySave_keepsDateAndDisablesMutations() {
        runBlocking { saver.enable(folder) }
        blockWrites()
        show()
        assertSaved()
        compose.onNodeWithText(str(R.string.backup_saving)).assertIsDisplayed()
        for (id in listOf(R.string.backup_now, R.string.backup_change_file, R.string.backup_turn_off)) disabled(id)
        for (id in listOf(R.string.view_recovery_phrase, R.string.restore_from_file, R.string.backup_export_unencrypted)) enabled(id)
        capture("backup-save-busy")
    }

    @Test fun restartRequired_keepsFolderNoticeAndDisablesMutations() {
        runBlocking {
            saver.enable(folder)
            writable = false
            saver.enable(Uri.parse("content://other/folder"))
            writable = true
            commitWorks = false
            saver.save(true)
        }
        show()
        assertSaved()
        compose.onNodeWithText(str(R.string.backup_error_settings)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_folder_change_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_file_not_usable)).performScrollTo().assertIsDisplayed()
        for (id in listOf(R.string.backup_now, R.string.backup_change_file, R.string.backup_turn_off)) disabled(id)
        for (id in listOf(R.string.view_recovery_phrase, R.string.restore_from_file, R.string.backup_export_unencrypted)) enabled(id)
        capture("backup-restart-required")
    }

    @Test fun setupAndCurrentAccessErrors_haveDifferentMessages() {
        runBlocking { writable = false; saver.enable(folder) }
        show()
        compose.onNodeWithText(str(R.string.backup_setup_failed)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_file_not_usable)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_error_no_access)).assertDoesNotExist()
        capture("backup-setup-access-error")
        runBlocking { writable = true; saver.enable(folder); writable = false; saver.save(true) }
        compose.onNodeWithText(str(R.string.backup_error_no_access)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_file_not_usable)).assertDoesNotExist()
        singleFolderAction(R.string.backup_choose_again)
        capture("backup-current-access-error")
    }

    private fun singleFolderAction(expected: Int) {
        for (id in listOf(R.string.backup_choose_folder, R.string.backup_choose_again, R.string.backup_change_file)) {
            val rows = compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText(str(id))))
            rows.assertCountEquals(if (id == expected) 1 else 0)
        }
        enabled(expected)
    }

    private fun legacy(error: BackupError.Kind? = null) {
        disk = BackupSettings(folder, "old phrase", legacyFileName = "Old.tfbackup", savedAt = time,
            error = error?.let { BackupError(it, time) })
        prepare()
    }

    @Test fun legacyFolderPhraseError_offersReviewAndOneFolderAction() {
        legacy()
        runBlocking { saver.enable(folder) } // Operation error, without a current-backup error.
        show()
        singleFolderAction(R.string.backup_choose_folder)
        enabled(R.string.backup_review_phrase)
        disabled(R.string.backup_now)
        assertSaved()
        capture("backup-legacy-phrase-review")
        row(R.string.backup_review_phrase).performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText(str(R.string.backup_continue)).performClick()
        compose.waitUntil(10_000) { disk!!.phraseHash != "old phrase" }
        org.junit.Assert.assertTrue(disk!!.needsMigration)
        org.junit.Assert.assertTrue(disk!!.pending)
        org.junit.Assert.assertNull(disk!!.error)
        compose.onNodeWithText(str(R.string.backup_folder_change_failed)).performScrollTo().assertIsDisplayed()
        singleFolderAction(R.string.backup_choose_folder)
    }

    @Test fun damagedBackup_offersOneFolderActionAndPhraseReviewRemainsSeparate() {
        runBlocking { saver.enable(folder) }
        documents[disk!!.active!!.uri] = byteArrayOf(1)
        runBlocking { saver.save(true) }
        Prefs.savePhrase(app, Prefs.loadPhrase(app)!!.reversed())
        runBlocking { saver.enable(folder) }
        show()
        singleFolderAction(R.string.backup_choose_folder)
        enabled(R.string.backup_review_phrase)
        capture("backup-damaged-phrase-review")
    }

    @Test fun migrationBanner_disappearsOnlyAfterSuccessfulSetup() {
        legacy(BackupError.Kind.PHRASE_CHANGED)
        compose.setContent { MaterialTheme { BackupMigrationBanner(onOpenBackup = {}) } }
        compose.onNodeWithText(str(R.string.backup_migration_notice)).assertIsDisplayed()
        runBlocking { saver.enable(folder) }
        compose.onNodeWithText(str(R.string.backup_migration_notice)).assertIsDisplayed()
        runBlocking { saver.usePhrase(); saver.enable(folder) }
        compose.onNodeWithText(str(R.string.backup_migration_notice)).assertDoesNotExist()
    }

    @Test fun sizeError_reassuresOnlyWhenSuccessfulBackupExists() {
        writeFailure = BackupManager.BackupValidationException(R.string.err_backup_too_large)
        runBlocking { saver.enable(folder) }
        show()
        compose.onNodeWithText(str(R.string.err_backup_too_large)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_error_too_large), substring = true).assertDoesNotExist()
        capture("backup-setup-too-large")
        runBlocking { writeFailure = null; saver.enable(folder); writeFailure = BackupManager.BackupValidationException(R.string.err_backup_too_large); saver.save(true) }
        compose.onNodeWithText(str(R.string.err_backup_too_large) + " " + str(R.string.backup_error_too_large))
            .performScrollTo().assertIsDisplayed()
        capture("backup-saved-too-large")
    }

    @Test fun phraseAcceptance_disablesWhenRestartBecomesRequired() {
        runBlocking { saver.enable(folder) }
        Prefs.savePhrase(app, Prefs.loadPhrase(app)!!.reversed())
        runBlocking { saver.save(true) }
        show()
        row(R.string.backup_review_phrase).performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText(str(R.string.backup_continue)).assertIsEnabled()
        commitWorks = false
        runBlocking { saver.disable() }
        compose.onNodeWithText(str(R.string.backup_continue)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.cancel_no_dialog_button)).performClick()
    }

    @Test fun openTurnOffConfirmation_disablesWhileSaveRuns() {
        runBlocking { saver.enable(folder) }
        show()
        row(R.string.backup_turn_off).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.backup_turn_off_yes)).assertIsEnabled()
        blockWrites()
        compose.onNodeWithText(str(R.string.backup_turn_off_yes)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.cancel_no_dialog_button)).assertIsEnabled()
    }
}
