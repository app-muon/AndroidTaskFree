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
without notes. New recurring occurrences start without notes; clones copy their
source's notes into independent records. Search matches
titles and full saved notes, including older and collapsed notes. The task list
keeps its existing layout: a task found only through a note is an ordinary row, and
opening it reveals the newest matching note and scrolls to the matching line.
Existing filters and sorting still apply, and note edits and deletions update
results while searching. Search observes notes once while active. Title matches
appear immediately; note matches follow a short debounce, and the last completed
results stay listed while typing. A row kept from the previous query opens without
a reveal. If notes can't be read, title matches still work and the failure is
logged. Only hidden matching notes and text whose match is outside the collapsed
preview expand on entry. Opening a result clears search focus. The reveal centres
the matching text in the measured viewport, clear of the fades, and follows initial
viewport changes without waiting for a keyboard dismissal or a fixed frame count.
Touch interaction, editing and expansion controls cancel it. Attempts and
cancellations survive recreation, so interruption cannot force the note open again.

The task-only `TaskSheetContainer` measures one copy of the date/status controls
and the available window constraints before pinning them. Its pinning choice stays
fixed during keyboard transitions. Pinned controls
hide while the keyboard is open; compact layouts keep controls before the notes.
Opening the keyboard does not insert or remove controls inside the list. The
task dialog explicitly handles keyboard and system-bar insets; Close remains
outside the list. Transparent space outside the surface dismisses the sheet.
`PanelActionList` retains its original layout for category, tools and backup dialogs.
Search still reloads the notes snapshot after saved-note changes; this work does
not add incremental indexing or alter storage.

The leaving-app backup remains independent of the edit flush. It can precede the
last write; note invalidation schedules the ordinary automatic backup. Neither
autosave nor retained ViewModel state guarantees the last keystroke survives an
abrupt process termination.

## Search and panel validation, 17–18 November 2026

- Debug app and instrumented test APK builds passed.
- The initial 22 focused JVM checks passed: search matching, subscription lifetime, typing
  debounce, task updates, note-read failures, existing filters, live note writes,
  and restoring the selected note.
- After the latest review fixes, 26 focused JVM checks passed, including retention
  and refresh state across subscription gaps, filter widening, matching/debounce,
  current title fallback, note updates and restoring the selected note.
- Eight focused UI checks passed on an isolated API 36 emulator: stable loading
  layout, full-match excerpts at 200% text, search and scroll position after
  recreation, selective note expansion/reveal/collapse after recreation, fade
  clearance, keyboard behavior, marking a task done, outside dismissal, a single
  control block, and cancelling a late reveal when editing starts. Earlier
  title-search coverage also passed.
- The Done, keyboard, note-reveal/fade and single-controls/outside-tap checks also
  passed in landscape at 150% text. The final filter-request capture adjustment
  passed its focused JVM regression check and debug build.
- The full test suite was not run. Build outputs used the temporary
  `taskfree-note-search-build` directory; the emulator used `-read-only -no-snapshot`.
- Later on 18 November the task list was returned to its committed layout: the row
  excerpts, search progress indicator and search error messages were removed with
  their UI checks. The remaining UI checks have not been rerun since.
- The incremental-search regression test reproduced a missing note match when typing
  `s` then `ss`. It failed with the nested derived states and passed after filtering
  and sorting were changed to remember list values. Debug and test APK builds passed;
  only that regression test was run for this fix, on a disposable emulator.

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
