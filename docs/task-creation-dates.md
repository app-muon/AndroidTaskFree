# Task creation dates

Tasks store `originalCreatedAt` and `occurrenceCreatedAt` as nullable UTC instants,
with millisecond precision. Details display **Created** and **Completed** side by
side in one compact row, both as localized dates including the year. Created means
the original creation date of the task, including for recurring tasks. Its calendar
date uses the phone's current timezone. Formatting follows the panel's configuration
and is remembered with the date, locale and timezone as keys.
Changing timezone can change the displayed calendar date; the stored instant stays
the same. English and Spanish labels are included.

New tasks and clones receive the same current instant in both fields. Completing or
archiving a single recurring occurrence copies its original date into the next
occurrence and records a new occurrence timestamp. Edits, recurrence changes,
reordering, completion, reopening, archiving and unarchiving preserve existing
timestamps. Undoing completion on a live task deletes its linked next occurrence
only when that successor is currently `TODO`, has no completion date and is not
archived. Edited `TODO` successors remain eligible for deletion; recreating one
records a new occurrence timestamp. `IN_PROGRESS`, `PENDING`, completed and archived
successors retain their edits, timestamps, source links and scheduled reminders.
Re-completing their parent reuses the link without creating another successor.
Eligibility uses the current status: a successor returned to `TODO` can be deleted
again, regardless of earlier edits or statuses.
Archiving an already completed occurrence never creates a
successor, even if its successor is unlinked or has been deliberately deleted.
Changing an archived occurrence from Done to another status and back leaves its
successor untouched and creates no new occurrence.

Migration 18 -> 19 adds nullable `sourceTaskId`, a deferred self-reference with a
unique index. Each generated occurrence points to the occurrence that created it.
Undo follows this link regardless of today's date or subsequent title, category,
due-date or recurrence edits. Identical manually added tasks stay independent.
Deleting a source task clears the reference while preserving its successor and
that successor's original creation timestamp.
Single-task insert conflicts abort instead of replacing an existing successor.

Links are not guessed for older rows or older backups. Undo leaves pre-existing,
unlinked successors alone. Re-completing a legacy task can create a new linked
successor alongside an old unlinked one; those old relationships cannot be reliably
reconstructed. All newly generated occurrences, including from older tasks, record
their source link.

Archive/unarchive operations read the current stored task before changing its
archive state, preserving edits made while the panel was open. Cloning reads saved
edits and uses the localized copy-name resource; clones get fresh timestamps and
no source link. A clone saves and schedules the same reminder on its due date using
the current timezone, including when the source reminder crosses a calendar-day
boundary after a timezone change. Reordering updates only the two order columns,
preserving newer saved fields and source links cleared by deletion. Startup
reindexing reads and updates every category in one transaction, closes gaps in
single-category positions and preserves all-category order. Test setup and
teardown close the database and remove both the main and temporary disposable
databases before clearing encryption preferences and cached keys.

Room migration 17 -> 18 adds two nullable INTEGER columns. Existing tasks have
unknown timestamps and display **Not recorded**. Descendants of these tasks keep
an unknown original date even though their own occurrence creation time is known.
No creation date is inferred from the due date, completion date, migration or restore.

JSON backup format 1.0 includes both optional timestamps using the existing Instant
serializer. Older backups decode missing fields as null. Import and database copying
preserve the stored values and source links, even when child tasks precede parents
in the JSON. Invalid links (missing sources, multiple successors or cycles) reject
the import with a dedicated English/Spanish validation message before replacing
any tasks or categories. Older app versions that ignore these
fields cannot preserve them if they export the data again.

Encryption migration removes any previous temporary database and its SQLite
sidecars before copying, even if that database used another phrase. An attempt
aborts before copying if temporary files remain. A failed copy closes both database
connections, clears the factory's plaintext instance, removes temporary files and
resets the encryption flag and cached key. If file cleanup also fails, the original
migration error is retained with the cleanup error recorded, and encryption state
is still reset. Once temporary files can be removed, retrying with the same or a
different phrase copies the unchanged source tasks and categories into a fresh
encrypted database. Recovery from failures during the final database-file swap is
a separate issue; these guarantees cover temporary-file cleanup and copy retries.

## Automated checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin
```

Coverage includes creation precision, multiple recurring generations, both generation
paths, undo/recreation, legacy tasks, clones, editing and lifecycle preservation,
backup round trips and older JSON backups, and populated v17 and v18 databases opened
and validated by Room v19. Fixed-output formatting tests cover English and Spanish
locales and timezone boundaries. Instrumented tests cover known and unknown dates in task
details, timestamp preservation after reopening a SQLCipher database, and the app's
actual plaintext-to-encrypted database copy with matching backup task values.
`EncryptionRetryTest` checks stale temporary databases with matching/different keys,
partial-copy failure followed by a retry with either phrase in the same test,
cleanup failure, and refusal to copy while temporary files remain. Assertions cover
all task/category values, source links, timestamps, encryption-state reset and
temporary database, WAL, SHM and journal removal.

## Validation results

### Archive and encryption recovery changes (2026-10-07)

Both Tools archive commands require `status = DONE`, a non-null completion date
strictly before the injected `today`, and `isArchived = false`. Repeats-only also
requires `recurrence != NONE`; having a source link never makes a one-off eligible.
The count comes from SQLite's affected rows and is shown in a short English/Spanish
toast, including zero. Each result is consumed once even when consecutive counts
match. Cancelling the confirmation does nothing. Failed operations show a localized
message and do not request a refresh; coroutine cancellation remains cancellation.
The menu styling, date/timezone behavior and category-specific archive action are
unchanged.

**Archive series** archives the selected occurrence and its explicitly linked later
occurrences, including descendants beyond already-archived intermediates. The
transaction changes only `isArchived`; reminders for newly archived rows are
cancelled after it commits. Earlier occurrences and unrelated matching tasks are
preserved. Cycles cannot cause endless traversal. Historical unlinked occurrences
remain separate: titles and dates are not evidence of a relationship. New successors
of legacy parents still receive links. Archiving an already-Done occurrence still
does not create a successor, including when an earlier successor was unlinked or
intentionally deleted. Existing regression tests for these rules remain in place.

Backup validation now rejects task IDs and source IDs less than or equal to zero
with a dedicated localized error containing the offending ID, before replacing any
data. IDs are never renumbered. The JSON format and Room schema remain unchanged.

Encryption migration and all factory database opens share one gate. Migration uses
private builders so copying and verification cannot recursively trigger recovery.
The atomic, versioned journal `no_backup/encryption-migration.journal` records only
the stage and whether an original database existed. It contains no key or phrase.
The original database moves to `no_backup/encryption-rollback.db` only after all
task/category fields (including occurrence links) match the encrypted copy, both
databases have successfully checkpointed, and their handles have closed. Required
renames are checked and an unresolved rollback copy is never overwritten.

After installation, the encrypted database is reopened, checked for integrity, and
compared again. The matching phrase, key, phrase hash and encrypted flag use checked
synchronous preference commits. Only then is `COMMITTED` recorded. Cleanup failure
after this point keeps encrypted mode and the committed journal for a later cleanup
attempt. An initially empty installation follows the same verified process.

Before commitment, failure or cancellation restores the original and plaintext
preferences, removes the stored/cached migration key, and keeps the phrase for retry.
If restoring the original fails, the installed copy is retained separately as
`no_backup/encryption-uncommitted.db`, with its sidecars. Recovery errors are added
as suppressed exceptions without replacing the original failure. Restart recovery
uses the durable stage and file locations, so it can resume interrupted renames or
preference commits. A corrupt journal, missing original, unresolved copy or file
conflict blocks normal database creation and further migration. An older
`databases/checklists_backup.db` is restored with its sidecars only if the main file
is missing; conflicting main/backup files are both preserved.

The encryption wizard displays localized failure feedback. Once rollback succeeds,
it offers retry or restart to rebuild closed database references. Incomplete recovery
offers recovery retry and blocks task access, including at startup. Key derivation,
SQLCipher format, Room version and backup transport have not changed.

Startup captures one encryption mode and uses a process-owned job. A receiver can
start readiness checks, but pending encryption and legacy-conflict previews wait
for a foreground activity. Migration then survives activity recreation and Home.
Encrypted-plus-pending startup verifies the installed database and synchronously
clears obsolete request/attempt markers before restarting. Automatic restarts have
a durable 10-second guard; suppression lasts until process exit and explicit Restart
remains available. Successful verification clears stale BLOCKED feedback silently.

Each transition into STARTED waits for database readiness before reindexing on IO,
using the current database and excluding overlapping reindex jobs. Recovery Skip
uses the gated, checked reset, including sidecars; dismissing the prompt never skips.
Reminder receivers retry temporary unavailability after 1, 5 and 15 minutes, then
show a generic localized notice with no task data. States requiring user action
show that notice immediately. Foreground readiness clears it; expired reminders
are not replayed. Both queries exclude DONE tasks, and only the migrator restores
future alarms after startup.

`PRAGMA wal_checkpoint(TRUNCATE)` checks both the busy result and the completed frame
count; pending WAL data is never removed during the swap. See
[SQLite checkpoint semantics](https://www.sqlite.org/c3ref/wal_checkpoint_v2.html).

Added/updated coverage includes the full archive recurrence/status/date matrix,
exact snapshots and affected counts, event consumption/error/cancellation, series
transaction rollback and reminder cancellation, invalid backup IDs, restart recovery
at every stage, orphan/conflicting backups, injected checkpoint/rename/commit/reopen/
rollback/cleanup failures, empty installs, and concurrent opening during migration.

**Execution status:** resource XML parsing, resource-name uniqueness and
`git diff --check` pass. Compilation, JVM tests, emulator tests and new UI screenshots
are pending: the configured Gradle distribution was unavailable locally, and its
download was not completed. No dependency or build configuration was changed.
Earlier screenshots below describe the previous date-display work, not validation
of this change.

When the configured build tools are available locally, run:

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest `
  --tests com.taskfree.app.data.database.TaskDaoTest `
  --tests com.taskfree.app.data.repository.TaskRepositoryTest `
  --tests com.taskfree.app.data.repository.RecurringOccurrenceTest `
  --tests com.taskfree.app.data.repository.TaskCreationDatesTest `
  --tests com.taskfree.app.data.repository.BackupManagerTest `
  --tests com.taskfree.app.ui.admin.ToolsViewModelTest `
  --tests com.taskfree.app.ui.task.TaskViewModelTest `
  --tests com.taskfree.app.data.EncryptionRecoveryTest `
  :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

The wrapper distribution itself must already be installed even with `--offline`.
See the additional ADB recovery and screenshot steps in [dev_tips.md](../dev_tips.md).

Safe reindexing, status-based undo and encryption retries (2026-10-07):

- All 81 focused JVM tests passed across `TaskRepositoryTest`,
  `RecurringOccurrenceTest`, `TaskCreationDatesTest`, `TaskDaoTest`,
  `BackupManagerTest` and `TaskViewModelTest`. Coverage includes rollback across
  categories and preserving a started successor's scheduled alarm.
- Android test compilation passed. All 16 instrumented tests passed on the
  disposable Android 9 emulator (`emulator-5582`, read-only, no snapshots): six
  `EncryptionRetryTest`, one `CreationDatesEncryptionTest`, three
  `EncryptedDatabaseTest` and six existing `SmokeTest` tests.
- Instrumented classes were run individually and their XML test counts checked;
  the initial multi-class command ran only its first class in this environment.
- Version 19, existing migrations, UI formatting, smoke-test scrolling, backup
  format, key derivation and backup transport were retained. Final database-file
  swap recovery remains outside this patch.

The focused JVM and compilation command below was reused. Instrumented classes
were selected with this command, substituting each class listed above:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.injected.device.serial=emulator-5582' `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.taskfree.app.data.EncryptionRetryTest'
```

Recurrence and persistence follow-up (2026-10-07):

- All 76 focused JVM tests passed across `TaskRepositoryTest`,
  `RecurringOccurrenceTest`, `TaskCreationDatesTest`, `TaskDaoTest`,
  `BackupManagerTest` and `TaskViewModelTest`.
- Android test compilation passed, followed by all six existing `SmokeTest` tests
  on the disposable Android 9 emulator (`emulator-5582`, read-only, no snapshots).
  The tests retain scrolling for offscreen panel actions and fields.
- Regression coverage checks completed/archived recurrence transitions, clone
  reminders after a timezone day boundary, stale reordering and transaction rollback,
  duplicate-link insertion, and specific restore errors without changing existing
  tasks or categories. Valid links restore in either parent/child row order.
- Database version 19, both migrations, full completion-date formatting and the
  panel's snapshot-based observation remain unchanged. Historical links remain
  unknown, with the legacy re-completion duplication limitation described above.

Commands used for the focused follow-up:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.taskfree.app.data.repository.TaskRepositoryTest `
  --tests com.taskfree.app.data.repository.RecurringOccurrenceTest `
  --tests com.taskfree.app.data.repository.TaskCreationDatesTest `
  --tests com.taskfree.app.data.database.TaskDaoTest `
  --tests com.taskfree.app.data.repository.BackupManagerTest `
  --tests com.taskfree.app.ui.task.TaskViewModelTest `
  :app:compileDebugAndroidTestKotlin
$env:ANDROID_SERIAL = 'emulator-5582'
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.injected.device.serial=emulator-5582' `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.taskfree.app.ui.SmokeTest'
```

Initial creation-date validation:

- 171 JVM tests passed, including migrations from v17 and v18 to v19.
- Debug APK build and Android test compilation passed.
- All 10 instrumented tests passed on the Android 9 emulator, including matching
  vertical positions for the two date headings and encrypted source-link copying.
- English and Spanish details were visually checked on a 720-pixel-wide device:
  [English screenshot](task-creation-dates-en.png),
  [Spanish screenshot](task-creation-dates-es.png).
- `lintDebug` completed with the existing `NewApi` error in
  `notifications/ExactAlarmPrompter.kt:15` and existing warnings; lint is configured
  not to abort the build. There are no new errors for this feature.
- SQLCipher reopen tests for the initial date implementation also passed on Android 16. That emulator's Compose tests
  fail before launching the app because the existing Espresso dependency looks for
  the removed `InputManager.getInstance` method; Android 9 was used for UI validation.

An upgrade of a pre-existing encrypted v17 install and Google backup transport
restore remain manual checks; the instructions below cover them.

## Device validation

Use a disposable test emulator: Compose smoke tests reset TaskFree's app data.
Replace `emulator-5580` with its actual serial. Build a pre-change debug APK separately
if testing an upgrade; install it before installing this version.

1. Install the pre-change APK and create ordinary, recurring, completed and archived
   tasks. Repeat this check with encryption enabled in the old version.
2. Upgrade in place without clearing data:

   ```powershell
   adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5580 shell am start -n com.taskfree.app/.MainActivity
   ```

   Confirm all tasks, categories, reminders and statuses survive. Existing task
   details must show **Not recorded**. Completing an older recurring task must also
   leave its next occurrence's original date unknown.
3. Add a new recurring task, complete it, archive its next occurrence, undo completion
   where applicable, edit its recurrence/category/title and clone it. Check the
   inherited original date; use exported JSON to distinguish timestamps when all
   actions happen on the same calendar day.
4. Export JSON, restore it, and compare both fields with the export. Restore an older
   JSON backup with no creation fields and confirm **Not recorded**. Enable encryption
   on a populated unencrypted database, restart the app, and export again to check
   that copying into SQLCipher preserves both fields. The transport restore procedure
   in [dev_tips.md](../dev_tips.md) also applies to encrypted database backups.
5. Check details in English and Spanish, including a legacy task and a recurring
   occurrence with different original and occurrence dates. Confirm the year is
   shown, the row is read-only, and long labels and large text fit. On Android 13+
   a disposable emulator's app language can be set using:

   ```powershell
   adb -s emulator-5580 shell cmd locale set-app-locales com.taskfree.app --locales es-ES
   adb -s emulator-5580 shell cmd locale set-app-locales com.taskfree.app --locales en-GB
   ```

   Save English/Spanish screenshots for review. Check a timestamp near midnight in
   two timezones: only the displayed date should change, never the exported timestamp.
6. Run the instrumented tests on the disposable emulator:

   ```powershell
   $env:ANDROID_SERIAL = 'emulator-5580'
   .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.device.serial=emulator-5580'
   ```

7. Validate undo with a recurring task and a future reminder: complete the parent,
   edit the successor and mark it In progress or Pending. Reopen and re-complete
   the parent. The successor, edits, source link and alarm must remain, with no new
   successor. Repeat with an edited To do successor: reopening deletes it. Completed
   and archived successors must survive. Inspect alarms on the disposable device:

   ```powershell
   adb -s emulator-5580 shell dumpsys alarm | Select-String 'com.taskfree.app' -Context 2,6
   ```

8. For deterministic encryption failure/retry validation, install the app and test
   APKs on the disposable emulator and run the dedicated instrumentation class:

   ```powershell
   .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
   adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   adb -s emulator-5580 shell am instrument -w -e class com.taskfree.app.data.EncryptionRetryTest com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
   adb -s emulator-5580 shell run-as com.taskfree.app ls databases
   ```

   The tests inject a failure after committing part of the copy, assert that the
   plaintext data survives unchanged, then retry without an app-state reset between
   attempts. Successful retries must contain exactly the source tasks/categories
   and no stale rows. Each test checks temporary-file removal before teardown; the
   test rule removes the main database after the test as well. No
   `checklists_temp.db`, `-wal`, `-shm` or `-journal` should remain afterward.
