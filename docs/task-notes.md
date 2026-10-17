# Task notes implementation and validation

Implemented `task_extension_reduced.md`. Database version 20 adds `TaskNote` with
a cascading task foreign key. Backups export version 1.1 and accept versions 1.0
and 1.1. Encryption migration copies notes and compares every field before and
after installation. Key derivation, recovery phrases and migration journals are
unchanged.

The current task sheet owns its drafts in `TaskViewModel`, with one sequential
mutation consumer. Titles and existing notes debounce for one second. Add, close
and backgrounding share a pending insertion. Successful closes release the sheet
state and observation; failed writes retain text and offer Retry. Rotation retains
the sheet, cursor state and active editor without submitting a new note. Startup
navigation now preserves its completed state across recreation so it does not
replace that retained task screen.

Notes remain occurrence-specific. Recurrence rollback only removes successors
without notes. New occurrences and clones do not copy notes. Search continues to
match titles. `PanelActionList` is unchanged; its header renders the bounded note
previews and the optional full collection.

The leaving-app backup remains independent of the edit flush. It can precede the
last write; note invalidation schedules the ordinary automatic backup. Neither
autosave nor retained ViewModel state guarantees the last keystroke survives an
abrupt process termination.

## Results, 16–17 October 2026

- Debug app and instrumented test APK builds passed.
- 123 focused JVM tests passed across `TaskRepositoryTest`,
  `TaskCreationDatesTest`, `RecurringOccurrenceTest`, `TaskNotesTest`,
  `TaskViewModelTest`, `TaskSheetEditorTest`, `TaskMutationQueueTest`,
  `CreationDatesMigrationTest`, `BackupManagerTest` and `AutoBackupTest`.
- Three native SQLCipher tests passed: notes and timestamps survive encryption,
  reopening and an encrypted backup/restore; omitted or altered note fields fail
  copy verification; altering a note after installation fails reopen verification
  and restores the original database.
- Six Compose tests passed on the API 28 `Low_End_Africa_2GB` emulator: close
  without Add, Add followed by close, immediate title/note flushes, recreation
  without duplicate notes or edited markers, background submission, failed close
  and Retry, previews, editor switching and input limits.
- Spanish preview/limit and failed-close tests also passed. Show all/Show fewer was
  exercised with 100 multiline notes. This is a functional check, not a performance
  guarantee for arbitrarily large collections.
- Reviewed the [English keyboard screenshot](task-notes-en.png) and
  [Spanish notes screenshot](task-notes-es.png).

Tests used an isolated emulator launched with `-read-only -no-snapshot`; the
existing AVD's data was not changed. Physical devices and other API levels were
not exercised. Test-only assertions were corrected to account for lazy actions,
field hints and Android composing annotations. The initial recreation failure
identified the startup-navigation fix described above.

## Repeat the focused checks

Use a disposable device: `ResetAppStateRule` clears the app data on the selected
device. Follow the backup/encryption guidance in `../dev_tips.md`.

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests '*TaskRepositoryTest' --tests '*TaskCreationDatesTest' --tests '*RecurringOccurrenceTest' --tests '*TaskNotesTest' --tests '*TaskViewModelTest' --tests '*TaskSheetEditorTest' --tests '*TaskMutationQueueTest' --tests '*CreationDatesMigrationTest' --tests '*BackupManagerTest' --tests '*AutoBackupTest'
.\gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -e captureScreenshots true -e class 'com.taskfree.app.data.TaskNotesEncryptionTest,com.taskfree.app.ui.TaskNotesUiTest' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 pull /sdcard/Android/data/com.taskfree.app/files/task-notes.png docs/task-notes-en.png
```

This run used the wrapper and Android Studio JBR. Windows file-access failures in
the Dropbox build directory required a temporary Gradle init script, supplied
with `-I`, which set each project's `layout.buildDirectory` to
`%TEMP%/taskfree-notes-build/<project-name>`. The final APKs and test reports are
under that directory; no project build configuration was changed.

For the Spanish check on this isolated, root-capable API 28 emulator:

```powershell
adb -s emulator-5582 root
adb -s emulator-5582 shell setprop persist.sys.locale es-ES
adb -s emulator-5582 reboot
# Wait for sys.boot_completed = 1 before running instrumentation.
adb -s emulator-5582 shell getprop sys.boot_completed
adb -s emulator-5582 shell am instrument -w -e captureScreenshots true -e previewNotes 100 -e class 'com.taskfree.app.ui.TaskNotesUiTest#previewsExpansionAndInputLimitsKeepTextAndActionsAccessible,com.taskfree.app.ui.TaskNotesUiTest#failedCloseRetainsTextAndRetryClosesWithoutDuplicates' com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5582 pull /sdcard/Android/data/com.taskfree.app/files/task-notes.png docs/task-notes-es.png
```

For additional manual checks, open a task, tap Add a note, type several lines and
a URL, switch between the title, composer and note pencil, and rotate while typing.
Close with Back, outside dismissal and the close action; also open
archive/clone/delete confirmations. Reopen and verify a single unedited submission
and a closed composer showing Add a note. Press Home during typing and confirm the
note is saved and no draft remains after returning.
Expand a saved note and open its link. Check limits with both typing and paste.
The failure-injection UI test demonstrates retaining the sheet and its text until
Retry succeeds. Create notes before enabling encryption, restart, then perform
an encrypted backup/restore and compare the notes and edited timestamps.
