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
