# Automatic encrypted backups

Choose **Tools → Backup files → Set up automatic backup** and a dedicated folder,
for example `Downloads/TaskFree`. Android 11 and later restrict selection of the
Downloads root ([Android storage guidance](https://developer.android.com/training/data-storage/shared/documents-files)).
The picker grants persistent read and write access. A cancelled picker changes nothing.
If a selection returns while another backup operation is running, the database is not
ready, or a restart is required, setup reports why and asks the user to choose again
after resolving it. This applies before cloud confirmation and again when confirming.
Selections are not queued or retried automatically.
Accepted local selections and cloud confirmations reopen the backup panel. It stays
open through progress and completion, including delayed setup errors.

Setup creates new `TaskFree-2026-10-12_14-05-00Z-A.tfbackup` and
`TaskFree-2026-10-12_14-05-00Z-B.tfbackup` documents, writes and
verifies both, and commits the new configuration. Providers may append a suffix when
these names already exist; existing documents are never adopted by name. Failed setup
keeps the previous configuration and backups. Before settings commitment is attempted,
failed or cancelled setup tries to delete only the documents it just created, then releases
the unused folder grant. It never deletes current managed or legacy documents. A failed
display-name lookup uses the requested filename. Once commitment has been attempted,
both candidate files and necessary grants are retained because persistence may be uncertain.
Provider refusal to delete, process death, or uncertain commitment can still leave setup
files behind; check them before deleting any manually.
Each new pair shares its UTC setup time. The actual provider-returned filenames,
including collision suffixes, are stored and reused without renaming during rotation.
Previously configured filenames and all previous pairs remain unchanged. The panel lists
both current filenames, identifies the last successfully saved file, and retains the saved
timestamp and location. Filename dates identify pair creation, not the latest contents;
use the restore preview to inspect a file's backup date and counts.

Later saves alternate between the files. The protected active file must still match its
recorded digest before a replacement is encrypted or the other file is opened for writing.
Unchanged data is skipped before verification. After writing, flushing, supported synchronization and closing,
a bounded read must match the exact ciphertext length and SHA-256 digest. Only a
successful synchronous settings commit changes the active file and latest save time.
Genuine synchronization failures fail the save. Unsupported descriptor synchronization
does not bypass read-back verification. Providers that return stale or unreadable data
report failure, so phone storage plus a separate sync app remains recommended.

Pending work is committed before overwriting the inactive file, unless already durably
pending. The final success commit is always required. A failed or interrupted
forced save therefore remains pending even if the tasks have not changed; the retry
policy below decides when another automatic attempt is useful. Errors retain the last
successful time and fingerprint. A settings commit
failure pauses all writes for the rest of the process because the durable result is
uncertain: force stop and reopen the app to reload it. The UI explicitly shows that a
restart is required and disables setup, folder changes, saving, accepting a phrase and
turning off. Those actions are also disabled while an operation is running. Restore,
export and viewing the phrase remain available. Progress appears during first setup,
and later operations retain the latest successful filename and timestamp.

A ciphertext length/digest mismatch or invalid verification metadata marks the protected
file as damaged; restore an intact copy if necessary, then choose a folder to create a
new pair. Permission failures report lost access. Other protected-file open/read/close
failures (including plain or wrapped `FileNotFoundException`) are retryable save failures. Every verification
failure prevents opening the inactive slot. Automatic saves never replace backups with
an empty database; they leave pending work, errors, fingerprint and saved time unchanged.
Manual saving remains possible, and adding data makes pending repair eligible again. Changed
recovery phrases require explicit review; older files can still need the older phrase.
The phrase-review action appears for either the current backup's error or a folder
operation's phrase error, including legacy configurations. Accepting the current phrase
clears only a saved phrase-change error, preserves unrelated errors and pending work,
and does not clear the historical folder-change notice. Empty data remains pending;
legacy configurations still need folder selection.

### Automatic retry policy

The saver uses one policy for eligibility, attempts and service-hold decisions, before
rebuilding/encrypting paused work. Manual saves still run normal validation and protected-slot
verification; they cannot overwrite a damaged protected pair.

| Current error | Automatic behavior |
| --- | --- |
| `ACTIVE_DAMAGED` | Pause until explicit recovery/setup. |
| `PHRASE_CHANGED` | Pause until the user reviews and accepts the phrase. |
| `TOO_LARGE` | Suppress another attempt at the same database revision; reassess after a task/category invalidation or manual save. |
| `FAILED` | Retry at normal automatic triggers, including unchanged data needing repair. |
| `NO_ACCESS` | Check persisted grants cheaply. Missing read/write grants pause retries; provider failures with valid grants remain retryable. Restored access permits saving again. |

Size suppression is in memory only. The database revision increments before the watcher's
debounce, and each attempt captures it before reading its snapshot, so edits arriving during
an oversized attempt are not lost. A new process allows one reassessment of a persisted size
error. There are no additional preference keys. A mismatch while verifying newly written
ciphertext is a retryable `FAILED` error at the verification stage; a protected-file mismatch
remains `ACTIVE_DAMAGED`.

Folder setup/change failures have a separate notice. Ordinary saves to the previous folder
neither retry unchanged data because of that notice nor clear it. It clears after successful
setup, disabling backup or process restart. A failed settings commit retains the last trusted
configuration and does not replace that notice. Setup access failures say that access could
not be retained; current-backup failures say that access was lost. Size validation/export use
the neutral 20 MiB message; automatic-backup UI adds preservation reassurance only if a
successful backup exists.

The automatic-save eligibility cache starts conservatively true and refreshes after
operations and database invalidations. Leaving the app holds the service only when this
cache indicates eligible work allowed by the retry policy or a save is active. Persisted
pending work alone does not start it; paused errors and repeated exits after an empty
snapshot has been observed do not restart the
service. Each successful hold is released on job completion, including cancellation before
the coroutine starts. Duplicate setup requests are rejected until the first setup job ends.
If grants return after a paused eligibility refresh, saving can recover without a service
hold on that first exit. The watcher and completion callbacks retain only application context.

Both tables are read in one Room transaction. Sorting, JSON encoding, hashing and
encryption happen afterward. The existing JSON schema and encryption format are
unchanged. The common limit is **20 MiB (20,971,520 bytes) per final file**, including
the encrypted format's 62-byte overhead. Oversized automatic saves and plain exports
are rejected before opening an output stream. Import uses the same final-file limit.

Both task and category screens share a persistent banner above bottom navigation.
Red banners prioritize restart-required state, then single-file migration, then any saved
backup error. Retryable failures explain that saving failed and will be retried.
Otherwise an amber banner says **Automatic backup is off**, including on an empty fresh
installation, or **No backup saved in 24 hours** when configured without a successful save
or when the last save is strictly more than 24 hours old. Exactly 24 hours and future save
times count as recent. An old backup triggers the warning even if nothing has changed.
Warnings remain during saving and clear only when the resulting status is healthy and recent.
Disabling backup replaces a saved-error banner with the off warning.
The entire amber strip opens **Backup files**, with one accessible click target and an action
that wraps below the message when space is limited. Age is checked on status changes,
foreground entry, and every minute while resumed; the timer stops in the background.
Its action and Tools open the same backup panel; the picker host remains composed when the
panel closes. Historical folder-operation errors stay in the panel and do not override the
banner rules. There is no dismissal or new backup scheduling behavior.
Tools also uses warning styling for migration.
The panel has one folder action: **Choose backup folder** for migration/damage/current `FAILED`, **Choose
folder again** for lost access, otherwise **Change folder**, with phrase review separate.
For a current retryable failure it explains that persistent failures can be recovered by
explicitly creating a new pair. Manual retry still verifies the protected file before
opening the inactive slot; a missing protected file never permits overwriting the survivor.
The legacy file is never modified. Changing folders and disabling share the save mutex; obsolete
grants are released only after the serialized operation and successful settings commit.
All destination and verification settings remain excluded from Android backup/transfer.

Unencrypted exports build and validate JSON before opening the output stream with explicit
`wt` mode; there is no fallback to `w`. Opening, writing and closing use the tools view
model's I/O dispatcher. Build, validation, open, write, close and cancellation failures
propagate without deleting the destination. Cancellation produces no failure toast,
including cancellation delivered after successful close, which leaves the completed file intact.
Failed exports can leave empty or partial files. Overwriting an existing document is not
atomic and can damage its previous contents once opening/writing begins. The export
confirmation explains this limitation. The neutral size-limit message is unchanged.

## Restore and synchronization

Include **both** files in the sync app. Either can be selected in **Restore from file**;
check the date, category count and task count in the existing confirmation. One slot
can be incomplete after an interrupted save, so try the other if a file is rejected.
After a successful save there is a complete backup in the selected folder even if a
later replacement fails. This guarantee does not cover external deletion/corruption
or cloud upload completion, which remains the sync app's responsibility.

## Focused automated checks

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.taskfree.app.data.backup.*' --tests 'com.taskfree.app.data.repository.BackupManagerTest' --tests 'com.taskfree.app.data.repository.TaskRepositoryTest' --tests 'com.taskfree.app.ui.admin.ToolsViewModelTest'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

The saver tests inject open, partial write, flush, sync, close, verification and settings
commit failures, preserving and restoring the protected ciphertext. Restart tests cover
pending writes, verification before commitment, and commitment on disk. They also cover
unchanged retries, lost grants, phrase changes, migration, collision handling, failed
folder changes, oversized saves, and disabling during a save. Review regressions cover
folder notices surviving normal saves, empty pending repair without automatic service
holds, transient protected reads versus mismatches, cancellation before execution,
reset ordering, cleanup of candidate files while excluding managed/legacy files,
uncertain commits, compatible fingerprints and commit counts. Repository checks cover
one enclosing snapshot transaction, recorded synchronously with both table reads, and plain/encrypted final files immediately
below, at, and above the limit.

## Backup warning validation, October 29, 2026

- `:app:testDebugUnitTest --tests com.taskfree.app.ui.admin.BackupBannerTest`: all seven
  tests passed, including the strict 24-hour boundary, future timestamps, error priority,
  historical folder errors, and warnings during saving.
- `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, and
  `:app:assembleDebugAndroidTest` passed.
- All 19 `BackupUiTest` cases passed across the initial run and focused reruns on a
  disposable API 28 emulator. Three existing panel tests needed a taller viewport to
  find offscreen lazy-list rows; their helpers were left unchanged. The existing API 36.1
  Espresso incompatibility described below also reproduced.
- The screen test checked all 32 combinations of Tasks/Categories, off/stale,
  English/Spanish, light/dark, and 1×/2× text on a 360dp-wide screen. Each warning had a
  single click target of at least 48dp and opened the panel. Screenshots confirmed the
  wrapping message/action and both warning palettes. The lifecycle test verified minute
  updates while resumed, no timer updates while backgrounded, and immediate refresh on return.
- Unrelated existing large-text limitations remain: bottom navigation labels truncate,
  and the Spanish Today filter can wrap one letter per line at 2× text.

| Screenshot | Configuration |
| --- | --- |
| [Tasks, backup off](backup-banner-tasks-off-en-light-1x.png) | English, light, 1× |
| [Categories, backup off](backup-banner-categories-off-es-dark-2x.png) | Spanish, dark, 2× |
| [Tasks, stale backup](backup-banner-tasks-stale-es-light-2x.png) | Spanish, light, 2× |
| [Categories, stale backup](backup-banner-categories-stale-en-dark-1x.png) | English, dark, 1× |

To reproduce all 32 screenshots on a disposable emulator with both debug APKs installed:

```powershell
adb -s emulator-5582 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.BackupUiTest#warningBanners_onBothMainScreensInEachLanguageThemeAndTextSize' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

The test supplies the clock and screen configurations; no device language/theme changes
are needed. Images are saved to `/sdcard/Android/data/com.taskfree.app/cache/backup-banner-*.png`.

## Export safety and visible recovery validation, October 12, 2026

- **45 tests passed** in `BackupSaverTest` and `ToolsViewModelTest`, across the initial
  run and the corrected export-provider regression. That fixture initially assumed a
  closed Robolectric database could not reopen; the duplicate assertion was removed.
  The separate build/size-before-open regression passed. Tests cover explicit `wt`
  truncation of longer JSON, exact parsable output, no fallback or provider deletion,
  write/close/open failures, cancellation during writing and after successful close,
  and provider operations off the main thread.
- Saver regressions cover missing protected files, plain/wrapped read failures,
  repeated attempts preserving the inactive file, transient recovery, explicit new-pair
  recovery, shared UTC creation names, provider collision suffixes, unchanged rotation
  names, retained previous pairs, and existing commit/cleanup safeguards.
- Both debug and instrumentation APKs built. **11 affected English Compose checks
  passed** on a fresh disposable API 36 emulator. Both picker paths reopen the panel,
  show delayed failures, and stay open after success. Current filenames and the full
  saved timestamp are asserted. Tests cover restart/migration/retry banner priority,
  both main screens, warning clearance, separate historical errors, single folder
  actions, cancellation/rejection, and busy/restart restrictions. `git diff --check` passed.
- Spanish strings were updated, but Spanish tests and captures were not rerun, as
  requested. Earlier Spanish screenshots below are historical. These new picker/failure
  checks use injected providers/activity results; no real cloud synchronization, physical
  device, long-idle run, or new real DocumentsUI/provider validation was performed.
  The export truncation test uses a Robolectric provider whose plain `w` mode deliberately
  preserves trailing bytes. API 36.1/37 Espresso limitations below still apply.

Relevant commands for this change (with a configured Java/Android environment):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.taskfree.app.data.backup.BackupSaverTest' --tests 'com.taskfree.app.ui.admin.ToolsViewModelTest'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# Run only the changed UI cases and affected restrictions/migration checks in English.
adb -s emulator-5586 shell cmd locale set-app-locales com.taskfree.app --locales en-GB
adb -s emulator-5586 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.BackupUiTest#localPicker_startedSetupReopensPanelAndShowsDelayedFailure,com.taskfree.app.ui.BackupUiTest#cloudPicker_startedSetupReopensPanelAndShowsDelayedFailure,com.taskfree.app.ui.BackupUiTest#retryBanner_opensRecoveryPanelAndClearsAfterRecoveryOrDisable,com.taskfree.app.ui.BackupUiTest#recoveryBanner_prioritizesRestartThenMigrationAndIgnoresHistoricalFolderError,com.taskfree.app.ui.BackupUiTest#busySave_keepsDateAndDisablesMutations,com.taskfree.app.ui.BackupUiTest#restartRequired_keepsFolderNoticeAndDisablesMutations,com.taskfree.app.ui.BackupUiTest#firstSetup_showsProgressAndDisablesSetup,com.taskfree.app.ui.BackupUiTest#pickerReturnWhileBusy_reportsRejectionBeforeCloudConfirmation,com.taskfree.app.ui.SmokeTest#backupRetryBanner_opensPanelOnBothMainScreens,com.taskfree.app.ui.SmokeTest#backupRestartBanner_opensPanelOnBothMainScreens,com.taskfree.app.ui.SmokeTest#toolsBackupFolder_legacyFileShowsMigrationAndKeepsSavedDate' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

| Changed state | English capture |
| --- | --- |
| Both dated filenames and last saved file | [Current pair](backup-current-pair-en.png) |
| Delayed local setup failure | [Local setup](backup-local-setup-failed-en.png) |
| Delayed cloud setup failure | [Cloud setup](backup-cloud-setup-failed-en.png) |
| Retry recovery panel | [Recovery](backup-retry-recovery-en.png) |
| Retry banner on both main screens | [Tasks](backup-retry-tasks-en.png), [Categories](backup-retry-categories-en.png) |
| Restart banner on both main screens | [Tasks](backup-restart-tasks-en.png), [Categories](backup-restart-categories-en.png) |

## Earlier recovery/retry follow-up validation, October 12, 2026

- All **81 focused JVM tests** passed across the initial run and focused corrections
  (backup package, backup manager and tools view model). Coverage includes early pause
  without snapshots/commits, revision changes during oversized attempts, manual and
  restart reassessment, grant recovery, service-hold counts, legacy/empty phrase acceptance,
  setup rejection, write/close/build/validation failure, cancellation,
  valid JSON output and I/O-thread operations. Existing preservation, fingerprint, pending,
  setup cleanup and commit-count checks remain.
- The snapshot test records SQL synchronously into a thread-safe collection and separates
  the snapshot transaction from Room's concurrent invalidation transactions. It passed,
  failed with `withTransaction` temporarily removed, and passed again after restoration.
  The temporary mutation was not included in either installed APK.
- Both debug APKs built. All **13 affected Compose tests passed in English and Spanish**
  across the focused runs on disposable API 36. These include both persistent banners,
  opening from the banner and Tools, disappearance after disabling/successful migration,
  single folder actions, legacy folder-operation phrase review, and busy/restart restrictions.
  The final picker regression observed the actual localized busy toast through accessibility
  when a cloud selection returned during a save, and verified cancellation created no documents.
  Assertions still target clickable rows and complete localized saved timestamps.
- Actual DocumentsUI setup in `Download/TaskFreeReview` created two new **245-byte**
  files without replacing the earlier pairs. ADB observed `isShortFgs=true` during setup;
  afterward settings showed `pending=false`, matching sizes/digests, and no running service.
- This run used an installed API 36 image and temporary AVD. No physical phone, cloud
  synchronization, second-device download, extended idle/Doze run, or process-death export
  handling was tested. The API 36.1/37 Espresso limitations noted below remain. No dependency
  or SDK changes were made.

| Updated recovery state | English | Spanish |
| --- | --- | --- |
| Task-screen migration banner | [Screenshot](backup-migration-tasks-en.png) | [Screenshot](backup-migration-tasks-es.png) |
| Category-screen migration banner | [Screenshot](backup-migration-categories-en.png) | [Screenshot](backup-migration-categories-es.png) |
| Legacy phrase review after folder rejection | [Screenshot](backup-legacy-phrase-review-en.png) | [Screenshot](backup-legacy-phrase-review-es.png) |
| Damaged pair with separate phrase review | [Screenshot](backup-damaged-phrase-review-en.png) | [Screenshot](backup-damaged-phrase-review-es.png) |

For manual recovery checks, seed legacy `local_backup` settings on a disposable emulator,
visit both screens, close/reopen the panel from each entry point, accept a changed phrase,
and verify folder selection is still required. Attempt a second setup while the first is
queued/running, and return a picker selection while setup is blocked: expect localized
feedback and no queued selection. For export failures, use a provider that rejects writes
or close; verify no deletion is attempted and cancellation shows no failure toast. Overwrite
longer JSON with shorter JSON using a provider whose `w` mode does not truncate, and verify
exact output without trailing data. Cancelling after successful close must retain valid JSON.

## Review follow-up validation, October 11–12, 2026

- All **69 focused JVM tests** passed across the backup, backup-manager and tools-view-model
  runs, including the new lifecycle/provider tests. The final provider rerun corrected
  test-provider authority registration; the lifecycle fixture uses the existing main
  dispatcher rule. Both debug APKs built successfully. `git diff --check` passed.
- All **nine affected Compose tests passed in English and Spanish on a fresh disposable
  API 36 emulator** (18 successful test executions). These cover first-setup progress,
  busy/restart controls, phrase/turn-off confirmations, separate folder/current errors,
  contextual size reassurance, setup and legacy migration. Migration asserts disabled
  semantics on the clickable row and the full formatted saved timestamp. The initial
  test-only locale override was replaced by real Android app locales so dialogs and
  timestamp assertions use the same configuration.
- Real DocumentsUI setup accepted `Download/TaskFreeReview`, retained the tree grant,
  and produced two verified **587-byte** files. ADB observed the service running with
  `isShortFgs=true` during setup and stopped after completion.
- Adding `ReviewTwo` and immediately pressing Home updated A to **930 bytes** without
  reopening the app. B kept its original SHA-256 digest, pending cleared, and the
  service stopped. No stuck hold or foreground-service exception was observed.
- Temporarily renaming protected A caused a retryable verification failure from the
  local provider (`IllegalArgumentException`), with B's hash unchanged. Restoring A's
  name and retrying succeeded; A remained unchanged and B became active.
- Choosing the same folder again created `TaskFree-A (1).tfbackup` and
  `TaskFree-B (1).tfbackup`, both verified at 930 bytes. The original pair's hashes stayed
  unchanged. Restoring each original file through the app succeeded and displayed
  `ReviewOne`, `ReviewTwo` and their `BackupReview` category.
- Deterministic JVM tests cover empty pending repair without repeated service starts,
  cancellation before execution, setup cleanup/refused deletion, uncertain settings
  commits both with and without durable candidates, and snapshot/read retries. These
  injected failure boundaries were not reproduced with a real cloud provider.
- **Remaining device limits:** no cloud account, second-device sync, long-idle/Doze run,
  or physical phone was tested. API 36 works with the current Compose/Espresso versions;
  the earlier API 36.1/37 limitation below still applies to those images. No dependencies
  or SDK configuration were changed. The disposable API 36 AVD uses an already installed
  system image and its own temporary data directory.

| Changed screen | English | Spanish |
| --- | --- | --- |
| First setup in progress | [Screenshot](backup-first-setup-busy-en.png) | [Screenshot](backup-first-setup-busy-es.png) |
| Saving with prior timestamp | [Screenshot](backup-save-busy-en.png) | [Screenshot](backup-save-busy-es.png) |
| Restart required with folder notice | [Screenshot](backup-restart-required-en.png) | [Screenshot](backup-restart-required-es.png) |
| Setup access failure | [Screenshot](backup-setup-access-error-en.png) | [Screenshot](backup-setup-access-error-es.png) |
| Current-backup access failure | [Screenshot](backup-current-access-error-en.png) | [Screenshot](backup-current-access-error-es.png) |
| Size failure before first success | [Screenshot](backup-setup-too-large-en.png) | [Screenshot](backup-setup-too-large-es.png) |
| Size failure with preserved backup | [Screenshot](backup-saved-too-large-en.png) | [Screenshot](backup-saved-too-large-es.png) |

Reproduce the UI checks on a disposable API 36 emulator (the reset rule wipes app data):

```powershell
adb -s emulator-5586 shell cmd locale set-app-locales com.taskfree.app --locales en-GB
adb -s emulator-5586 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.BackupUiTest,com.taskfree.app.ui.SmokeTest#toolsBackupFolder_legacyFileShowsMigrationAndKeepsSavedDate,com.taskfree.app.ui.SmokeTest#toolsBackupFile_showsOffThenOpensPanelWithSetupRestoreAndExport' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5586 shell cmd locale set-app-locales com.taskfree.app --locales es-ES
# Repeat the same instrument command, then pull backup-*-en.png and backup-*-es.png
# from /sdcard/Android/data/com.taskfree.app/cache/.
```

## Earlier validation, October 11, 2026

- The 85 focused JVM tests passed across the initial run and the rerun of the two
  corrected size-test classes. The initial oversized fixtures used a single row beyond
  Android's cursor limit; the corrected fixtures span 24 rows. Both debug APKs built.
- Setup and legacy migration UI smoke tests passed on API 28 in English and Spanish
  (four successful instrumented invocations). The migration screen retains the prior
  saved date and makes the save action non-clickable. Both tests also passed together
  in one process after adding the backup reset hook; the final debug builds passed.
- On the disposable API 36.1 emulator, the real folder picker rejected storage/Downloads
  roots, accepted `Downloads/TaskFree`, and persisted access. Setup produced two verified
  862-byte files. Cancelling a folder change left both hashes and the configuration intact.
- Adding a task and immediately pressing Home completed a verified background save
  without reopening the app: A grew to 1,211 bytes and became active; B retained its
  original digest. Pending work cleared and the foreground service stopped.
- Force-stopping a forced save during preparation left both hashes intact. Reopening
  retained access and the prior successful configuration.
- **At that point, device validation was pending:** restoring both actual slot files, longer idle
  runs, and uploaded copies on a second device. API 36.1 shared storage failed with
  `Transport endpoint is not connected` during the restore check; the emulator then
  stalled during restart. The API 36 follow-up above now covers actual local restores. Both-slot decrypt
  and restore checks passed under Robolectric, including injected failures.
- The existing Espresso dependency cannot run these UI tests on API 36.1 or 37
  (`InputManager.getInstance` is missing). The recent-device checks above used ADB UI
  interaction instead. Test dependency upgrades are outside this change.
- No cloud sync account/provider was configured, so remote synchronization was not
  verified. Follow the separate upload/download checks below before relying on a sync app.

| Screen | English | Spanish |
| --- | --- | --- |
| Folder setup | [Screenshot](backup-folder-setup-en.png) | [Screenshot](backup-folder-setup-es.png) |
| Legacy migration | [Screenshot](backup-folder-migration-en.png) | [Screenshot](backup-folder-migration-es.png) |
| Verified save | [Screenshot](backup-folder-saved-en.png) | — |

## Disposable device validation

`ResetAppStateRule` deletes app data. Never run instrumentation on a personal device.
Use API 34 or later for the short foreground service and folder restrictions. Run
the Compose smoke tests on a device supported by the current Espresso dependency
(API 36 passed the current checks; API 28 was used for the earlier screenshots).

```powershell
adb -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5584 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.SmokeTest#toolsBackupFile_showsOffThenOpensPanelWithSetupRestoreAndExport' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.SmokeTest#toolsBackupFolder_legacyFileShowsMigrationAndKeepsSavedDate' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

The reset rule cancels and joins the database watcher, joins existing saves without
cancelling them, then resets process references before database teardown. For Spanish on API 33+:
`adb -s emulator-5584 shell cmd locale set-app-locales com.taskfree.app --locales es-ES`.
Screenshots are in `/sdcard/Android/data/com.taskfree.app/cache/backup-folder-*.png`.

1. Seed identifiable tasks/categories. Choose `Downloads/TaskFree` in the actual picker,
   allow access, and wait for the saved filename/date. Confirm two nonempty files.
   Cancel a folder change and check the old filename/date and file hashes remain.
   Select the same folder again and confirm newly named files, with old files intact.
2. Edit a task and press Home immediately. Without reopening the app, inspect both
   sizes/hashes and the saved time using ADB. Repeat after 60 seconds idle and after
   15 minutes idle. Verify one file changes and the other remains identical.
3. During repeated **Back up now** saves, force stop or kill the process at several
   points. Reopen, save again, and restore both files to check dates/counts/content.
   Deterministic interruptions at verification/commit are covered by JVM tests.
4. Make the active file temporarily unreadable and attempt a save. The remaining file
   must not change; access/I/O errors must be retryable. Restore access and retry. Alter
   its bytes and confirm the distinct damaged-file notice, with the other file still
   unchanged. Change folders and verify a new pair. During a save, turning off is disabled;
   afterward it retains both documents and releases the grant.
5. Restore A and B separately, including a slot left over from before a phrase change.
   Compare all task/category data, not only the displayed counts.
6. In a separately configured sync app, include both A and B, wait for its upload
   completion, and download both on another device. Compare SHA-256 hashes and restore
   both. Repeat offline/online and during replacement. A successful TaskFree save alone
   does not establish that either cloud upload finished.

Useful commands (inspect only the test app's preferences, which contain URIs/hashes,
not `encryption_secret.xml` or recovery words):

```powershell
adb -s emulator-5584 shell input keyevent KEYCODE_HOME
adb -s emulator-5584 shell ls -l /sdcard/Download/TaskFree
adb -s emulator-5584 shell sha256sum /sdcard/Download/TaskFree/TaskFree-A.tfbackup /sdcard/Download/TaskFree/TaskFree-B.tfbackup
adb -s emulator-5584 shell run-as com.taskfree.app cat shared_prefs/local_backup.xml
adb -s emulator-5584 shell am force-stop com.taskfree.app
adb -s emulator-5584 shell am start -W -n com.taskfree.app/.MainActivity
adb -s emulator-5584 shell dumpsys activity services com.taskfree.app
adb -s emulator-5584 shell dumpsys activity permissions
```
