# Custom repetition validation

The shared Repeat dialog supports Days, Weeks and Months at intervals 1–99, plus the existing fixed presets. Done applies to the creation draft or atomically saves the selected existing occurrence. Historical occurrences retain their rules and offer an explicit link to an eligible current occurrence.

The pure calculator retains due-date scheduling, skipped missed dates, monthly clamping/drift and the 3,000-step cutoff. Editing a due date resets the base date and moves an existing reminder to the same local time. Disabling repetition retains the due date and reminder. Saves recheck the exact task ID, explicit successor links, status, archive state and active category.

Database version **21** adds only `recurrenceInterval INTEGER NOT NULL DEFAULT 1`. The shared factory registers migration 20→21 for plain and SQLCipher databases. Backup format **1.2** exports intervals explicitly; 1.0/1.1 imports default absent intervals to one. Invalid interval/preset combinations fail before replacement. The previous reader's 1.0/1.1 version gate rejects 1.2.

## Follow-up verification (17 November 2026)

Testing resumed at the user's request, excluding the two dedicated Spanish-language cases. **260 distinct JVM tests and 20 distinct device tests passed across the initial runs and focused reruns.** The debug app and instrumented-test APKs built successfully. This was focused feature verification, not a run of every test in the repository; lint was not rerun.

The JVM selection covered domain models, repositories, database migrations, task ViewModels, due choices and notifications. Three obsolete repository expectations were updated for explicit dates and typed stale-target failures. The repeat-save test now awaits the serialized operation before asserting its independently reconciled alarm. Added regressions cover quick-date retry/restoration, duplicate submissions and completion while another sheet opens, plus reminder failure after a committed write.

Device coverage on the API 37 emulator comprised RepeatUiTest, RepeatStepperUiTest, the five non-Spanish RepeatDraftUiTest cases, and RepeatEncryptionTest. The stale-save case now verifies the retained disabled draft through recreation and confirms that opening the current task does not transfer it. A new notification test verifies two taps, latest-request retention through recreation, and navigation only after both Repeat and its parent close.

The runs exposed and corrected three issues: a held stepper could advance once after release; a restored Repeat editor retained an outdated task title; and the reminder-failure toast could throw if completion resumed without a Looper. Repetition now cancels directly on release/cancel events, display-only titles refresh without replacing the draft, and the warning is posted to the main thread. **The previously unverified hold/release test now passes.**

An existing temporary build directory had a locked generated dex output, so verification used a fresh `%TEMP%\taskfree-approved-plan-tests` directory through a local ignored init script. The device run had intermittent startup timeouts and an API 37 window transition stuck after rapid notification taps and recreation; the affected cases passed on focused reruns. Window/transition animations were temporarily disabled for the final notification test and restored to their previous `1.0` values afterwards. Its final title selector was also corrected to allow the inline edit icon. Direct instrumentation output was inspected, including failure codes, rather than relying on the shell exit code.

Skipped: `weekdayErrorUsesConfigurationLocaleAndIncludesDayName` and `spanishLargeTextShowsFullLabelsAndAccessibleActions`. Manual TalkBack announcements, physical devices, and the full checklist below were not exhaustively exercised. The screenshots still predate this follow-up.

## Approved follow-up implementation (16 November 2026)

The initial implementation pass used read-only diff and call-site inspection only, as requested at that time: no tests, builds or emulator sessions. Its hold/release adjustment was explicitly **unverified** until testing resumed above.

The compact layout has frequency chips and an `Every − value + unit` stepper, without a separate visible rule summary. Accessibility exposes the complete localized rule, the current button value and short repeated-action labels. A polite rule announcement settles after 250 ms without a change. Limits remain 1–99, with tap and hold controls.

Date controls appear for missing/incompatible dates and remain visible after the user uses them, including after switching to Months or No repetition. A valid date uses the neutral “Due date” label. Explicit dates are retained; an automatically supplied Today is removed when repetition is undone. A submitted date is fixed so an unsuccessful draft does not advance at midnight.

Historical viewing and blocked edits are separate. A stale/deleted target retains the unsuccessful rule and date, disables changes and Done, explains that nothing was saved, and offers Close. An eligible linked successor offers “Edit current task’s repeat”; that opens its stored values and never copies the rejected draft. The opening baseline, draft and blocked state survive saved-state restoration, even if the parent task has disappeared.

An eligible task with an invalid stored rule opens an explicit No repetition repair draft. Opening or cancelling writes nothing; Done validates and saves the chosen rule. Historical invalid rows remain read-only and may offer the current-task link. Scheduling and backup validation stay strict. Repository creation and repeat-enabling edits require an explicit date; the Repeat editor supplies Today when needed. Cloning an unchanged legacy weekday/weekend date retains its existing exception.

Detail saves publish the committed row before reminder reconciliation. A reminder failure is logged and shows “Task saved, but its reminder could not be updated.” It does not turn a committed write into a retry. Typed validation, missing-task, inactive-category, invalid-rule and stale-target failures show corrective messages. Storage/access failures retain Retry with the original edit and completion behavior. Quick-date validation is unchanged. Pending quick-date operations and retries belong to the ViewModel, disable duplicate submissions, survive rotation, and close only the originating sheet session.

Notification intents use SINGLE_TOP. One saved pending request is retained; the latest tap wins. Only the resumed destination handles it after startup and editor restoration. An open task editor (including nested Repeat), category editor or other modal stays open with a brief localized notice. After it closes, an idle Categories screen routes to Tasks if needed. Closing only Repeat leaves its parent open. A tap for the already-open task is consumed without resetting its draft; other requests are consumed after opening or a missing-task notice. Recreation and Recents do not replay consumed requests.

Android repeat formatting is shared by Compose and notifications; invalid display data gets a localized label. Active-category lookup uses the existing by-ID query. Local `work/` contents are ignored in place, and ignored `CLAUDE.md` records database version 21. This follow-up adds no database migration or backup-format change.

### Verification checklist

The automated coverage above exercises part of this checklist; remaining manual scenarios are retained here.

- Correct Saturday to Monday, switch to Months, and confirm Monday remains visible. Explicitly date an undated task, then select No repetition; contrast with undoing automatic Today.
- Reject stale and deleted targets, rotate and restore saved state, and confirm the unsuccessful rule/date and explanation remain. Open a linked current task and confirm only its stored settings appear.
- Fail a database write, retry it, and separately fail reminder reconciliation after a commit. Confirm only storage/access errors offer Retry and that reminder failures never repeat the write.
- Rotate during postponement and during a retry. Close/reopen the same task or open another sheet while completion is queued; confirm no new session is dismissed. Retain weekday/weekend quick-date rejection.
- Open a corrupt current rule, cancel, reopen and explicitly repair it. Confirm historical corrupt rules are read-only. Reject a missing due date at repository creation/edit boundaries before any write; retain unchanged legacy cloning.
- Tap notifications during a task editor, nested Repeat, category editor and Tools/Backup modals, including restored editors, repeated taps and missing targets. Close only Repeat, then its parent. Confirm latest-tap handling, Categories routing, same-task draft retention, recreation and Recents behavior.
- Check English/Spanish complete-rule semantics, polite 250 ms settled announcements, button values, short long-click labels, large text, limits, tap behavior and hold/release. Automated limits, taps and hold/release passed on 17 November; dedicated Spanish cases and manual spoken announcements were not run in that pass.

## Earlier interval-button implementation (16 November 2026)

The interval changed to minus/plus buttons, with press-and-hold for faster changes and disabled controls at 1 and 99. No keyboard is needed. English and Spanish units use singular/plural forms. The separate visible rule summary was subsequently removed for the compact layout described above.

At that stage, Repeat showed date controls only for an undated task or a rule requiring date correction. The follow-up above retains controls after explicit use, including with No repetition; normal read-only history still hides them.

Both debug APKs built successfully. Nineteen of twenty focused UI tests passed, including limits, date correction, draft restoration, Spanish and large text. The hold/release test failed a timing assertion; its manual clock handling was adjusted, but the adjustment was left unverified at that stage because the user requested no more tests. The 17 November verification above subsequently corrected the control and passed this test. Lint retained the same five errors in untouched files, 37 warnings and one hint. The screenshots below were updated and visually inspected at that earlier stage.

## Earlier review fixes and validation (16 November 2026)

- Automatic Today is discarded when enabling repetition is undone, while explicit dates are retained. Fixed dates select the corresponding Today/Tomorrow/+2 chip without advancing the creation draft at midnight.
- Preview and persistence share the effective base-date calculation. Unchanged legacy Weekdays/Weekends dates remain cloneable; new incompatible dates are still rejected.
- Notification consumption survives saved-state recreation and ignores history launches. Category navigation clears saved dialog identity before leaving. Stale repeat saves refresh eligibility and offer the explicit current-task link without transferring the draft.
- Retry retains its result callback and logs failures. Quick-date buttons visibly disable during saving. Existing repeat editors load task and eligibility together; missing-task dialogs say Close.
- Invalid stored rules display a warning without crashing task rows or suppressing reminders. Scheduling and backup validation remain strict. Notifications are fully localized; validation errors include the weekday in the configuration locale. Repeat controls include a full rule summary, interval units and an accessible edit action.
- Removed obsolete recurrence controls/state and migrated tests off the compatibility APIs. Generated work screenshots are ignored; existing design mockups and the pre-existing footer/SmokeTest changes are preserved. Repository Today defaults and the 30-second refresh interval remain unchanged.

Validation: **164 affected JVM tests passed**, plus **16 focused Compose tests on Pixel 8 / API 37**. The new corrupt-rule UI test initially used an incorrect text selector; it passed after switching to the due-row test tag. Both debug APKs built successfully. A temporary Gradle init script redirected build output to `%TEMP%\taskfree-review-build` to avoid Dropbox file locks. The first emulator had insufficient installation storage; the Pixel 8 run completed normally.

Lint completed with **five errors in untouched files**: one `NewApi` issue in `ExactAlarmPrompter.kt`, and four `LocalContextGetResourceValueCall` issues in `BackupFileDialog.kt`, `SortMode.kt` and `ToolsMenuDialog.kt`. There were also 37 warnings and one hint. These unrelated findings were left unchanged; the reviewed repeat code has no lint errors.

The UI suites cover provisional/explicit dates in both entry points, fixed dates across midnight/restoration, legacy previews, stale-target recovery, notification consumption and fresh launches, category navigation/Back, corrupt-rule rendering, Spanish weekday errors, interval semantics and large text. Test reports were inspected directly because the current Gradle device runner can report build success after an APK installation failure.

Final screenshot inspection also caught the rule summary using the dialog window's English context inside the Spanish test configuration. Compose summaries now use `LocalResources` through the shared formatter, and the Spanish test asserts `Cada 99 meses`. The two screenshot scenarios and notification suite passed again after that correction; the screenshots below show the final UI.

## Original implementation results (15 November 2026)

- **251 affected JVM tests:** 250 initially passed; the remaining new test had a category-replacement fixture that cascaded deletion of its task. After correcting the fixture, all six schedule tests and all 32 view-model tests passed. The earlier passing suites were not needlessly rerun.
- **Eight focused device tests passed** on the Pixel 8 emulator, Android 17 / API 37: four options tests, three creation/restoration/localization tests and one SQLCipher migration/restore test. Failed test-fixture assertions were corrected and rerun.
- **Debug APK and instrumented-test APK built successfully.**
- **Android lint:** zero errors; existing unrelated warnings remain. No warnings remain in the new repeat dialog, resources, creation state or dialog restoration code.
- Existing local panel/footer and SmokeTest changes were preserved.

Covered scenarios include interval-one parity, 2/99, early and late completion, leap dates, monthly and multi-month clamping, the cutoff, automatic Today, date validation, atomic saves, stale targets, historical links, cloning, completion/archive inheritance, undo protections, failed saves without alarm changes, alarm reconciliation and notification text.

Device coverage includes notification entry, Done/Cancel, numeric errors, incompatible weekday/weekend dates, parent-note preservation, rotation, restoration of creation drafts and existing editor IDs, reloading current database data, Spanish and the app's largest text scale. Today/preview refresh uses resume observation and a periodic day check.

The SQLCipher test reconstructs the v20 table shape in an encrypted database, reopens it through the real factory and verifies existing dates, source links, notes and default intervals. It then saves interval 99, restores both plain and encrypted 1.2 backups into SQLCipher and verifies another reopen. Plain migrations from versions 17–20 are covered by Room's schema validation in JVM tests. A separate two-APK upgrade was not run.

Screenshots: [Repeat editor](repeat-edit.png), [Spanish, largest app text size](repeat-spanish-large.png).

## Focused commands

Run from the repository root with the Android Studio JBR selected as JAVA_HOME:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.taskfree.app.domain.model.*" --tests "com.taskfree.app.data.repository.*" --tests "com.taskfree.app.data.database.*" --tests "com.taskfree.app.ui.task.*" --tests "com.taskfree.app.notifications.*"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Dropbox briefly made a generated KSP output unreadable. Subsequent checks used an uncommitted temporary Gradle init script that redirected build directories to `%TEMP%\taskfree-repeat-build\<project>`; no project build configuration or dependencies changed.

Run device tests only on a disposable emulator: ResetAppStateRule clears the app's data.

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class "com.taskfree.app.ui.RepeatUiTest,com.taskfree.app.ui.RepeatDraftUiTest,com.taskfree.app.data.RepeatEncryptionTest" com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Android/data/com.taskfree.app/files/repeat-edit.png docs/repeat-edit.png
adb -s emulator-5554 pull /sdcard/Android/data/com.taskfree.app/files/repeat-spanish-large.png docs/repeat-spanish-large.png
```

The actual session installed APKs from the temporary build output directory. Direct ADB instrumentation was used for multiple classes because the Gradle invocation only selected the first comma-separated class on this Windows setup.

## Manual upgrade and restore check

1. On a disposable emulator, install a version-20 APK. Create dated daily/monthly tasks, complete an occurrence to create a linked successor, add notes and set a reminder. Export plain and encrypted backups. Repeat with database encryption enabled.
2. Install the new APK with `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`, then run `adb -s emulator-5554 shell am start -n com.taskfree.app/.MainActivity`. Verify dates, history, links, notes and reminder times are unchanged and existing rules display interval one.
3. Set Every 3 weeks and Every 2 months through Repeat. Cancel one draft and verify it has no effect. Save another, then run the restart commands below. Verify the rule survives and a completion creates only one correctly scheduled successor.
4. Export 1.2 plain and encrypted backups, change data, and restore each through Tools. Verify intervals, dates, notes and links. Restore the old 1.0/1.1 files and verify interval one. A backup with interval 0, 100, or interval 2 for Weekdays must fail without replacing current data.
5. Tap a reminder notification and open Repeat. Complete its task while an editor is open and verify the stale save fails without modifying the successor.

```powershell
adb -s emulator-5554 shell am force-stop com.taskfree.app
adb -s emulator-5554 shell am start -n com.taskfree.app/.MainActivity
adb -s emulator-5554 shell cmd locale set-app-locales com.taskfree.app --locales es-ES
# Use Tools > Text size to select the largest app text size and inspect the scrolling dialog.
adb -s emulator-5554 shell cmd locale set-app-locales com.taskfree.app --locales en-GB
```

Encryption keys, encryption format and transport behavior are unchanged. Physical devices and earlier Android API levels were not exercised for this feature.
