# CLI commands to test backup

## Archive and recoverable encryption validation

Use a disposable emulator only: `ResetAppStateRule` deletes app data, including
migration journals, sidecars, pending requests, outcomes and recovery copies.
Build with the existing tool versions; no dependency or SDK changes are needed.
Use `--offline -Pandroid.builder.sdkDownload=false` when downloads are not wanted
(quote the `-P` argument in PowerShell).

The factory getter now only returns a ready cached database. Startup/recovery is
owned by `RealDatabaseMigrator`; receivers wait at most five seconds and receive
`Ready(database)`, `RetryLater`, or `NeedsUserAction`. Temporary unavailability gets
at most three retries, after 1, 5 and 15 minutes, using a count in the existing
PendingIntent extras. Normal scheduling resets that count. Stored reminder times
never change. Delivery re-reads cancellation, deletion, completion, archiving and
moved times; both reminder queries exclude DONE tasks.

States needing user input, or exhausted retries, post one localized notice:
"Open TaskFree to see your reminders". It contains no task data. A fixed tag/ID and
a flag in backup-excluded `runtime_state.xml` deduplicate it. Normal foreground
readiness clears the notice and flag. The migrator restores only future reminders
on IO, once per successful startup; BootReceiver does not repeat this work. It waits
(up to 8 seconds, never in a migration process) until that restore has finished
before completing the broadcast. If startup is not ready it sets a follow-up
BootReceiver alarm with the same 1/5/15-minute policy, then posts the notice. A
pending restart or a rolled-back attempt awaiting restart is retried like a
temporary state; only key recovery, blocked recovery and conflicts need the user.
There is no queue and no replay of expired reminders after fallback.

Phrase confirmation synchronously saves the phrase and pending request in the
backup-excluded preferences before requesting a fresh process. That process never
opens normal repositories/view models, including after a successful migration.
Its single process-owned startup job captures the mode once. Pending encryption
waits for a foreground activity; receivers cannot start it. Once started, migration
survives rotation and backgrounding. Legacy-conflict previews also wait for foreground.
Encrypted-plus-pending startup verifies the database with the stored key/settings,
then synchronously clears obsolete pending/attempt markers before restarting.
Missing keys require recovery; failed verification keeps access blocked.

Automatic restart records its timestamp synchronously in `runtime_state.xml`. A
previous automatic restart less than 10 seconds ago, a future timestamp, or a failed
write leaves the manual Restart screen. Suppression lasts for that process, including
Home/resume; explicit Restart bypasses the guard. `clearInstance()` does not reset the permanent
"normal database opened" guard. Tests simulate process boundaries explicitly and
inject restart requests, never kill their own runner.

The 16-byte SQLite header guard runs before every existing plaintext open. It is
an additional guard, alongside integrity checks and process isolation. A mismatch
preserves the file and does not change encryption preferences. A zero-length file
is accepted, because SQLite opens it as an empty database. Missing main files
cannot create an empty database while unresolved migration files remain.
Plaintext Room opens use a callback whose corruption handler only logs, so a
corrupt file fails to open and stays on disk instead of being deleted and
recreated. SQLCipher already never deletes on corruption (`hasCodec()`).

Pending, attempt and outcome flags and the saved legacy settings live in
`runtime_state.xml`, which cloud backup and device transfer both exclude.
Encryption never starts while any journal exists. A legacy selection that ends
with a cleanup warning in a pending-encryption process restarts instead of
encrypting; the normal process retries the cleanup and encryption is requested
again later. A failed integrity re-check closes its database handle, and Got it
on the blocked dialog only hides it, so every retry re-checks.

Recovery persists a rolled-back/interrupted outcome before removing its journal.
Background startup does not acknowledge it; the next foreground screen offers
Retry encryption or Retry recovery as appropriate. Back/outside dismissal does
neither. A COMMITTED journal takes precedence over an unfinished attempt marker.
After verifying the committed database and key/settings, failed copy deletion
permits normal use with an unencrypted-copy warning and cleanup retry.
Successful verified recovery silently clears stale BLOCKED feedback. Real rollback
feedback and unresolved cleanup warnings remain. Blocked access takes precedence
over cleanup-warning wording/actions. Failed request/acknowledgment writes stay on
screen with a retryable error. Recovery retry only clears feedback after verification.
Skip performs checked database/sidecar deletion and synchronous preference changes
on IO under the database gate; failure offers retry. Back/outside dismissal never
executes Skip. Reindexing runs on every STARTED transition after readiness, on IO
against the current database, with a shared mutex preventing overlapping jobs.

Legacy conflicts offer one explicit selection dialog. Counts come from direct SQL
against disposable copies including WAL/journal sidecars, without Room migrations.
Encrypted candidates try both stored and phrase-derived keys. An unreadable copy
is unavailable, never zero tasks. A populated copy is preselected only if both
inspections succeeded and the other has zero tasks. Both originals survive until
the selection is validated, reopened and committed; subsequent cleanup removes
the unselected copy. Only a missing main allows automatic legacy restoration.

After building both debug APKs, run the SQLCipher and Compose classes separately:

```powershell
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -e class com.taskfree.app.data.EncryptionRetryTest com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am instrument -w -e class com.taskfree.app.data.CreationDatesEncryptionTest com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am instrument -w -e class com.taskfree.app.data.EncryptedDatabaseTest com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am instrument -w -e class com.taskfree.app.ui.SmokeTest com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

The SQLCipher suite uses internal hooks for deterministic interruption at journal
stages and between file renames, as well as injected copy, checkpoint, commit,
reopen, rollback and cleanup failures. It compares every task/category field and
link, tests the same/different phrase after rollback, verifies empty installs,
rejects concurrent getters during copying, and reopens with cached keys cleared.
It also covers encrypted legacy candidates with incorrect preferences, both
selections, interrupted installation and cleanup failure that permits normal use.

Focused JVM checks:

```powershell
.\gradlew.bat --offline '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest --tests com.taskfree.app.data.EncryptionRecoveryTest --tests com.taskfree.app.notifications.AlarmReceiverTest --tests com.taskfree.app.notifications.NotificationSchedulerTest
.\gradlew.bat --offline '-Pandroid.builder.sdkDownload=false' :app:assembleDebug :app:assembleDebugAndroidTest
```

### Review fixes, 2026-10-08 (boot restore, journal guard, corruption, flags)

- `.\gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`:
  all 234 JVM tests passed, including the previously hanging
  `ToolsViewModelTest` cancellation test and the new `BootReceiverTest` and
  `EncryptionRecoveryTest` cases (cleanup warning in a pending process, journal
  guard, receiver retries for FAILED/RESTART, zero-length main, failed integrity
  re-check, unknown outcome, flags in `runtime_state.xml`).
- emulator-5582 (API 28): `EncryptedDatabaseTest` 6/6 (including a corrupt plaintext
  file kept on disk and a wrong SQLCipher key leaving the database readable) and
  `EncryptionRetryTest` 24/24 passed. `SmokeTest` passed 13/13 twice after three
  test fixes: the archive test now opens the date filter by `DATE_FILTER_TAG`
  (its "Today" label also matched a task row due today); the Tools event toast is
  shown on the main thread (the Compose test dispatcher resumed the collector on
  the IO sender thread); and the cleanup-warning test waits for the startup job to
  finish before tapping Retry, because `retryRecovery` ignores taps while startup
  is still restoring reminders after READY.
- Reboot check passed: after seeding a reminder 15 minutes ahead, launching once and
  rebooting without opening the app, `dumpsys alarm` showed the same REMINDER alarm
  and no RESTORE_REMINDERS follow-up. Not repeated on device: pending encryption
  across a reboot (instrumentation leaves the app force-stopped, so it would need a
  manual setup), and waiting for the restored alarm to fire.

```powershell
adb -s emulator-5582 shell am instrument -w -e class "com.taskfree.app.data.EncryptedDatabaseTest#externalProcessFixture" -e externalEncryptionFixture prepare -e fixtureTasks 3 -e reminderSeconds 900 com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am start -W -n com.taskfree.app/.MainActivity   # clears the stopped state
adb -s emulator-5582 shell input keyevent KEYCODE_HOME
adb -s emulator-5582 reboot
adb -s emulator-5582 shell dumpsys alarm | Select-String com.taskfree.app
```

### Focused regression follow-up, 2026-10-08

All six requested regressions passed using existing helpers:

| Regression | Test | Result |
| --- | --- | --- |
| Skip removes restored encrypted sidecars and allows startup | `EncryptionRetryTest#skipRestoredEncryptedDatabaseRemovesSidecarsAndAllowsStartup` | Passed, SQLCipher/API 28 |
| Encrypted plus pending preserves data and clears obsolete markers | `EncryptionRetryTest#encryptedPlusPendingPreservesDataAndClearsRequestBeforeRestart` | Passed, SQLCipher/API 28 |
| Receiver waits for foreground, migration starts once | `EncryptionRetryTest#receiverBeforeActivityWaitsForForegroundAndMigratesOnce` | Passed, SQLCipher/API 28 |
| Automatic restart guard persists across process resets; explicit restart works | `EncryptionRecoveryTest.automaticRestartGuardSurvivesProcessResetAndAllowsExplicitRestart` | Passed, Robolectric |
| Both reminder queries exclude DONE and retain eligible tasks | `TaskDaoTest.reminderQueriesExcludeCompletedTasks` | Passed, Robolectric |
| Bounded retries retain PendingIntent identity, counts and stored time, then fall back | `AlarmReceiverTest.boundedRetriesSurviveProcessResetAndPreserveStoredReminder` | Passed, Robolectric |

The short API 28 Compose walkthrough
`SmokeTest#encryptionAndReminderRegressionWalkthrough` passed in 18 seconds. It
covered migration/rotation/background completion, restart requests, Home and return
to the original task, blocked recovery dismissal/retry, the generic notification
and its removal, and Skip dismissal/failure/retry. Restart callbacks and process
resets were injected so instrumentation could stay alive; this follow-up did not
repeat the earlier external PID or 50,000-task exercise. Early test runs needed
test-helper and system-UI synchronization corrections; all final selected checks passed.

The offline debug build, instrumented-test compilation and test APK build passed
with installed Gradle 9.6.0 and the Android Studio JBR. The wrapper initially tried
to download a distribution despite `--offline`, so these runs invoked the installed
Gradle binary directly. No dependencies, SDKs, schema or encryption format changed.
The full suite, device/API matrix, large-data reruns and real delayed alarm delivery
were intentionally skipped. Spanish strings were updated; this follow-up captured
the new screens in English only.

To repeat just these checks with an available wrapper distribution:

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests '*EncryptionRecoveryTest.automaticRestartGuardSurvivesProcessResetAndAllowsExplicitRestart' --tests '*TaskDaoTest.reminderQueriesExcludeCompletedTasks' --tests '*AlarmReceiverTest.boundedRetriesSurviveProcessResetAndPreserveStoredReminder'
.\gradlew.bat --offline :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -e class 'com.taskfree.app.data.EncryptionRetryTest#skipRestoredEncryptedDatabaseRemovesSidecarsAndAllowsStartup,com.taskfree.app.data.EncryptionRetryTest#encryptedPlusPendingPreservesDataAndClearsRequestBeforeRestart,com.taskfree.app.data.EncryptionRetryTest#receiverBeforeActivityWaitsForForegroundAndMigratesOnce' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.ui.SmokeTest#encryptionAndReminderRegressionWalkthrough' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

New captures: [manual restart](docs/encryption-restart-en.png),
[generic reminder notice](docs/reminder-access-en.png), and
[retryable Skip failure](docs/encryption-reset-failed-en.png).

External process fixture (opt-in; deliberately retains synthetic data):

```powershell
adb -s emulator-5582 shell am instrument -w -e externalEncryptionFixture prepare -e fixtureTasks 50000 -e class com.taskfree.app.data.EncryptedDatabaseTest#externalProcessFixture com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 shell am start -n com.taskfree.app/.MainActivity
adb -s emulator-5582 shell pidof com.taskfree.app
```

Record the normal PID, confirm the encryption phrase in Tools, and record the
migration PID. Press Home while the journal exists, then switch the screen off/on.
Completion in the background must retain the migration PID until foregrounded;
resuming must produce another PID and the original tasks. If restart cannot launch,
the retry screen must remain and normal access must stay disabled.

Prepare again with `-e pending true` to enter migration directly after the runner
exits. Force-stop while the journal says PREPARING/READY/ORIGINAL_MOVED/INSTALLED;
relaunch and check the persisted rollback message, then restart from that screen.
For alarms, fire the same reminder during migration and verify that it is re-armed
silently, then delivered only after access is available. A rooted disposable
emulator can explicitly broadcast to the non-exported receiver with:

```powershell
adb -s emulator-5582 shell am broadcast -a com.taskfree.app.REMINDER -n com.taskfree.app/.notifications.AlarmReceiver --ei task_id 1
adb -s emulator-5582 shell dumpsys alarm
adb -s emulator-5582 shell am instrument -w -e externalEncryptionFixture verify -e class com.taskfree.app.data.EncryptedDatabaseTest#externalProcessFixture com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
```

The verification fixture compares every task/category value against the preflight
JSON snapshot, including source links and dates. Instrumented tests alone are not
sufficient evidence that the original process-related data-loss issue is fixed;
real process changes, foreground/background, screen cycling, interruption and
alarm reproduction must succeed too.

Earlier validation recorded before the focused regression fixes below, on
2026-10-08 with the API 28 emulator (`emulator-5582`), without dependency changes:

- Offline debug build and instrumented APK compilation passed. Focused JVM checks
  passed: 20 recovery/startup tests, 3 alarm receiver tests and 9 scheduler tests.
- The 24 existing focused SQLCipher/creation-date tests passed, followed by the
  added stored-key/unrelated-phrase regression and affected legacy-selection tests.
  Five encryption Compose tests passed in both English and Spanish; the blocked
  recovery and cleanup retry tests also passed after the final retry change.
- With 50,000 synthetic tasks, phrase confirmation changed the normal PID from
  2813 to migration PID 3083. Home and screen off/on did not cancel migration;
  background completion retained PID 3083. Resume restarted into normal PID 3708.
  All task/category values matched the preflight JSON snapshot afterward.
- A reminder broadcast during migration re-armed the same PendingIntent for one
  minute later without changing its stored time. Delivery after normal startup
  produced the expected notification. The screen-off ADB transport temporarily
  stalled; `adb reconnect` restored it without terminating the app process.
- A separate run was force-stopped at the durable PREPARING stage (PID 4565).
  Startup rolled back, removed the journal and showed the persisted failure dialog
  (PID 4653). Its Restart app action produced PID 4750; the complete 50,000-task
  snapshot comparison passed again. Later-stage interruption coverage comes from
  the deterministic SQLCipher tests, not timing-dependent external force-stops.

Changed-dialog captures from the emulator:

| Dialog | English | Spanish |
| --- | --- | --- |
| Rolled-back attempt | [English](docs/encryption-rolled-back-en.png) | [Spanish](docs/encryption-rolled-back-es.png) |
| Blocked recovery | [English](docs/encryption-recovery-blocked-en.png) | [Spanish](docs/encryption-recovery-blocked-es.png) |
| Retained plaintext copy | [English](docs/encryption-cleanup-warning-en.png) | [Spanish](docs/encryption-cleanup-warning-es.png) |
| Legacy selection | [English](docs/encryption-legacy-selection-en.png) | [Spanish](docs/encryption-legacy-selection-es.png) |

These checks validate the reproduced failure and recovery on API 28. Physical
devices and other Android API levels have not been tested in this session.

For manual process interruption, first seed a disposable install with linked tasks,
archived tasks and categories, and export JSON for comparison. Start encryption,
then stop/relaunch while it is running. Timing alone is nondeterministic; use the
instrumented stage tests above for complete coverage.

```powershell
adb -s emulator-5582 shell am force-stop com.taskfree.app
adb -s emulator-5582 shell run-as com.taskfree.app ls no_backup
adb -s emulator-5582 shell run-as com.taskfree.app ls databases
adb -s emulator-5582 shell am start -n com.taskfree.app/.MainActivity
```

After precommit recovery, exported tasks/categories must match the original and
the retained phrase must be available for retry. After a committed interruption,
encryption must remain enabled. In both cases successful recovery removes the
journal and rollback/uncommitted copies. If recovery cannot finish, preserve all
files and verify that the startup screen only offers recovery retry. Do not delete
or manually overwrite files to bypass a recovery conflict.

For both English and Spanish, test Tools archive confirmation cancellation, a
positive count, zero, consecutive equal counts, and automatic list refresh. Test
Archive series from the middle of a chain, including a previously archived
intermediate row and an edited successor. The confirmation must mention the selected
occurrence and its linked later occurrences. Capture the confirmations, counts and
failure/recovery dialogs for review. On Android 13+:

```powershell
adb -s emulator-5582 shell cmd locale set-app-locales com.taskfree.app --locales es-ES
adb -s emulator-5582 shell screencap -p /sdcard/archive-recovery-es.png
adb -s emulator-5582 pull /sdcard/archive-recovery-es.png docs/archive-recovery-es.png
adb -s emulator-5582 shell cmd locale set-app-locales com.taskfree.app --locales en-GB
adb -s emulator-5582 shell screencap -p /sdcard/archive-recovery-en.png
adb -s emulator-5582 pull /sdcard/archive-recovery-en.png docs/archive-recovery-en.png
```

On older emulator images, change the system language in Settings. These screenshot
paths are intended outputs, not existing verification artifacts.

### 0 · Build APK once on your host
Android Studio, you normally use Build → Generate Signed Bundle / APK which handles the signing step.

Or alternatively build the version you want from the CLI:

`./gradlew :app:assembleDebug     # On macOS/Linux`          
`gradlew.bat :app:assembleDebug   # On Windows`

Or

`./gradlew :app:assembleRelease     # On macOS/Linux`          
`gradlew.bat :app:assembleRelease   # On Windows`

This will build the APK here: `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release-unsigned.apk`.

## 1 · Device A (“old phone”)
You can run these from the terminal in Android Studio.

Step	Command / Action
- 1.1	Install the app
`adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`
- 1.2	Launch app → walk through Encrypt Data wizard.
- 1.3	Sign in with the same test Google account . `Settings ▸ Passwords & accounts ▸ Add account ▸ Google.` 
- 1.4	Enable & select Google Drive transport
  `adb -s emulator-5554 shell bmgr enable true`
  `adb -s emulator-5554 shell bmgr transport com.google.android.gms/.backup.BackupTransportService`
- 1.4b Remove old backups for that app
`adb -s emulator-5554 shell bmgr wipe com.google.android.gms/.backup.BackupTransportService com.taskfree.app`
- 1.5	Trigger backup
  `adb -s emulator-5554 shell bmgr backupnow com.taskfree.app`
  Logcat should end with Backup finished with status SUCCESS.
  1.6	Get backup token (needed later)
  `adb -s emulator-5554 shell bmgr list sets`
  Copy the long number, e.g. `4793848356023`.

## 2 · Device B (“new phone”)
Step	Command / Action
- 2.1	Wipe the AVD once in AVD Manager (to simulate a fresh device).
- 2.2	Start the emulator, add the same Google account (Settings ▸ Passwords & accounts).
- 2.3	Select Google transport (once):
`adb -s emulator-5556 shell bmgr enable true`
`adb -s emulator-5556 shell bmgr transport com.google.android.gms/.backup.BackupTransportService`
- 2.4	Verify the backup set is visible
`adb -s emulator-5556 shell bmgr list sets`
If it still says “No restore sets”, wait a minute for Drive sync and repeat.
- 2.5	Install the APK (required before or after restore; either is fine)
`adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk`
- 2.6	Restore only this app’s data
`adb -s emulator-5556 shell bmgr restore <TOKEN> com.taskfree.app`
Example:
`adb -s emulator-5556 shell bmgr restore 4793848356023 com.taskfree.app`
- 2.7	Logcat should show
`dispatchRestore(): com.taskfree.app`
`restoreFinished(): SUCCESS`
- 2.8	Launch the app → the Restore-phrase prompt should appear.

