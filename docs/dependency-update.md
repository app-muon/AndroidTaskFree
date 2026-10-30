# Dependency update validation

Validation started on 30 October 2026 against the stable versions selected on
29 October. App version stays 1.1.3 (63), min SDK 26, target SDK 36, database
schema 20, and backup format 1.1.

## Baseline

- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.2.10, Room 2.7.1, SQLCipher 4.9.0.
- Android Studio JBR 25.0.3; Robolectric runs Android SDK 34.
- `:app:testDebugUnitTest`: 362 tests, no failures or skips.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
  `:app:assembleRelease`: passed.
- `:app:lintDebug`: completed, with one pre-existing `NewApi` error for
  `AlarmManager.canScheduleExactAlarms` in `ExactAlarmPrompter.kt` (min SDK 26,
  API requires 31). Lint is configured not to abort on errors. Remaining findings
  are warnings/hints; the original XML report is preserved for comparison.
- The first combined build stopped on a locked release dex output directory;
  retry succeeded after Dropbox was paused.
- Local artifacts and reports are under
  `%TEMP%\TaskFree-dependencies-20261030\baseline`. APKs and generated fixtures
  are deliberately outside version control.

## Stage results

- Stage 1: explicit Espresso Core 3.7.0; all 19 `BackupUiTest` cases passed on
  the disposable API 36.1 AVD. Both plaintext and encrypted handoff fixtures
  passed seed/verify with the baseline libraries (four instrumentation runs).
- Stage 2: wrapper regenerated for Gradle 9.8.2; distribution SHA-256 pinned,
  generated wrapper JAR verified against the official checksum. Kotlin and its
  compiler plugins are 2.4.21, KSP 2.3.12, Robolectric 4.17. Java bytecode stays
  at 17 through `compilerOptions`; Robolectric stays on SDK 34 and its JDK
  access flags apply only to unit tests.
- Initial Stage 2 compilation failed in Compose `ModuleMetricsImpl.saveMetricsTo`
  with `java.io.IOException: Invalid file path`. Read-only compiler diagnostics
  showed the new default `TaskApp:app_debug` module name (and equivalent test and
  release variants). The build now explicitly preserves the prior `app_debug`,
  `app_release`, etc. names, avoiding colons in Windows report filenames and
  retaining the previous internal JVM names. No dependency downgrade or
  prerelease was used.
- Stage 2 gate passed: 57 focused recurrence, backup and view-model tests, plus
  debug and instrumentation APK assembly.
  Gradle 9.8.2 and AGP 9.4.0 remain above Kotlin's published fully tested range;
  these results validate this project's local build, not every possible toolchain.
- Stage 3 gate passed: 153 focused backup, serialization, reordering and
  view-model tests, plus both debug APK builds. Three added smoke tests passed
  on API 36.1: task dragging/recreation, category dragging/navigation, and
  date/time picker save/back navigation. The runtime/UI upgrade also reopened
  the old encrypted database and restored both old backups without clearing data.
  SDK 37 required a nullable `RecentTaskInfo` check in an existing smoke test.
  Native picker tests resolve Material button IDs through the target APK's
  resource table; using the test APK's library IDs targeted the wrong controls.
- Stage 4 uses Room 2.8.5 (one runtime/KTX/compiler version), AndroidX SQLite
  2.7.1 and SQLCipher 4.20.0. No production Kotlin source, migration, encryption
  setting, key handling or backup serialization code changed.
- Resolved Compose components remain BOM-managed: UI/Foundation 1.12.1,
  Material3 1.4.0 and extended icons 1.7.8. Different component versions are
  intentional; no individual Compose version overrides were introduced.

## Final build checks

- All 362 unit tests passed without failures or skips.
- Debug APK, instrumentation APK and signed release APK assembly passed.
- Recurring Windows locks in the regular build output prevented debug
  compilation from finishing there. Final debug/test APKs, unit tests and lint
  were built in `%TEMP%\TaskFree-dependencies-20261030\candidate-build` using
  the temporary `build-location.gradle` init script. The release APK built in
  the regular project output. Device acceptance uses the successful isolated
  build's APKs, not stale artifacts from failed builds.
- Lint completed but is **not clean**: the pre-existing `NewApi` error remains.
  The upgraded Compose lint rules additionally report four
  `LocalContextGetResourceValueCall` errors in unchanged toast/event callbacks:
  `BackupFileDialog.kt:115,116`, `SortMode.kt:70`, and `ToolsMenuDialog.kt:85`.
  They also flag one `LocalContextResourcesRead` warning. These existing code
  patterns were not changed or suppressed as part of the dependency update.

Commands used for the final checks (PowerShell, JBR on `JAVA_HOME`/`PATH`):

```powershell
.\gradlew.bat :app:assembleRelease
$artifacts = "$env:TEMP\TaskFree-dependencies-20261030"
$env:TASKFREE_VALIDATION_BUILD = "$artifacts\candidate-build"
.\gradlew.bat -I "$artifacts\build-location.gradle" `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The local init script only redirects output; it contains:

```groovy
allprojects {
    layout.buildDirectory.set(file(System.getenv("TASKFREE_VALIDATION_BUILD") + "/" + project.name))
}
```

## Device results and remaining baseline issues

All tests ran on newly created temporary AVDs, never personal devices. API 37
reported `PAGE_SIZE=16384`. Both AVDs were shut down after verification.

| Check | API 28 | API 37, 16 KB |
| --- | --- | --- |
| Old plaintext database, install candidate without clearing data | Passed | Passed |
| Old encrypted database, install candidate without clearing data | Passed | Passed |
| Old plain/encrypted backup restore, preferences, notes, reminder values, reopen and integrity | Passed | Passed |
| Native encryption/recovery tests | 33 passed | 33 passed |
| Task/category dragging and date/time picker/back smoke tests | 3 passed | 3 passed |
| Full instrumentation run | 77 passed, 3 failed, 2 skipped | 78 passed, 2 failed, 2 skipped |

The two skips are opt-in fixture helpers (`DependencyUpgradeTest` and
`EncryptedDatabaseTest.externalProcessFixture`). The dependency upgrade helper
was run separately for every upgrade row above. Native tests cover wrong-key
handling, preserved database files, interrupted encryption, retry/rollback,
backup round trips, notes/creation dates, shutdown and reset.

The following failures reproduced with the preserved **old app and old test
libraries**, with only the Stage 1 Espresso fix:

- Both APIs: `SmokeTest.toolsBackupFile_showsOffThenOpensPanelWithSetupRestoreAndExport`
  selects "Backup files" ambiguously; both the background banner and dialog match.
- API 37: `SmokeTest.rollbackFeedbackSurvivesBackgroundRecoveryAndDismissalDoesNotRestart`
  times out waiting for rollback feedback to disappear.
- API 28: `BackupUiTest.warningBanners_onBothMainScreensInEachLanguageThemeAndTextSize`
  fails its dialog-displayed assertion.

An additional API 28 timeout occurred while starting
`TaskNotesUiTest.backgroundSubmitsDraftAndShowsAddANoteOnReturn` in the full run.
It passed in the baseline comparison and the complete seven-test candidate notes
class passed on recheck. This is recorded as an unreproduced timing failure,
not silently counted as an initially passing test. No unrelated production or
test fixes were made for these baseline failures.

API 37 passed all 19 backup UI tests, including the Tasks/Categories matrix in
English/Spanish, light/dark themes and 1x/2x text. The run captured 67 screenshots
under `%TEMP%\TaskFree-dependencies-20261030\candidate-screenshots-api37`;
representative English/light and Spanish/dark/large-text screens were inspected.
The full test suites are therefore **not green**, although the reproducible
failures also occur on the baseline. The lint findings above remain separate.

Logs are named `emulator-5582-*` (API 37) and `emulator-5584-*` (API 28), with
`api28-candidate-notes-recheck.log` for the focused rerun. Verified debug/test APKs
were copied back to the regular `app/build/outputs/apk` paths after testing.
The final debug APK SHA-256 is
`48641e109babbd422e16acf025f15658029fb495e7eb56b52625be4933402e78`;
the signed release APK SHA-256 is
`ffd2edaae6e7c45d2077f2c182f8df521bbc889688db0dadb423e9a079b44ed4`.

## Two-APK upgrade test

`DependencyUpgradeTest` is an opt-in handoff test. It skips during ordinary test
runs. It seeds synthetic tasks, a category, notes, a future recurring reminder,
text-scale preferences, and DataStore onboarding preferences with the baseline
APK. It also writes plaintext/encrypted backups and a closed database copy to
the app's external files directory. Its verify action checks rows, integrity,
schema version, preferences, a second database reopen, and restoration of both
old backup formats, including wrong-phrase rejection.

Use only a disposable emulator. Existing tests use `ResetAppStateRule` and wipe
app data. Do not run those tests between handoff seed and verify. Preserve the
baseline app APK and test APK (built with this test before updating dependencies)
and build the candidate app/test APKs. Both app APKs must use the same debug key.

PowerShell/ADB steps, with an explicitly selected disposable emulator:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = 'emulator-5582' # Verify this belongs to the disposable AVD.
$artifacts = "$env:TEMP\TaskFree-dependencies-20261030"
$candidate = "$artifacts\candidate-build\app\outputs\apk"
$mode = 'plain' # Repeat the entire sequence with 'encrypted'.

& $adb -s $serial install -r "$artifacts\baseline\app-debug.apk"
& $adb -s $serial install -r "$artifacts\baseline\app-debug-androidTest.apk"
& $adb -s $serial shell am instrument -w -r `
  -e class com.taskfree.app.data.DependencyUpgradeTest `
  -e disposableEmulator true -e dependencyUpgradeAction seed `
  -e dependencyUpgradeMode $mode `
  com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $serial pull /sdcard/Android/data/com.taskfree.app/files/dependency-upgrade "$artifacts\fixtures"

# Replace APKs in place: no uninstall, pm clear, or reset rule here.
& $adb -s $serial install -r "$candidate\debug\app-debug.apk"
& $adb -s $serial install -r "$candidate\androidTest\debug\app-debug-androidTest.apk"
& $adb -s $serial shell am instrument -w -r `
  -e class com.taskfree.app.data.DependencyUpgradeTest `
  -e disposableEmulator true -e dependencyUpgradeAction verify `
  -e dependencyUpgradeMode $mode `
  com.taskfree.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $serial shell getconf PAGE_SIZE
```

Require `OK (1 test)` in each instrumentation result; ADB exit status alone does
not establish success. Seed deliberately resets synthetic data. The fixed phrase
in the test is public test data and must never protect real user data.

## Release references

- [Gradle release and checksums](https://gradle.org/release-checksums/)
- [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Robolectric JVM configuration](https://robolectric.org/getting-started/#running-with-java-17-and-higher)
- [AndroidX stable versions](https://developer.android.com/jetpack/androidx/versions)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Espresso 3.7.0](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)
- [SQLCipher 4.20.0](https://www.zetetic.net/blog/2026/10/27/sqlcipher-4.20.0-release/)
