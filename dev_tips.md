# CLI commands to test backup

### 0 · Build APK once on your host
Android Studio, you normally use Build → Generate Signed Bundle / APK which handles the signing step.

Or alternatively build the version you want from the CLI:

`./gradlew :app:assembleDebug     # On macOS/Linux`          
`gradlew.bat :app:assembleDebug   # On Windows`

Or
`./gradlew :app:assembleRelease     # On macOS/Linux`          
`gradlew.bat :app:assembleRelease   # On Windows`

This will build the APK here: `app/build/outputs/apk/debug/app-debug.apk`

## 1 · Device A (“old phone”)
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
2.5	Install the APK (required before or after restore; either is fine)
`adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk`
2.6	Restore only this app’s data
`adb -s emulator-5556 shell bmgr restore <TOKEN> com.taskfree.app`
Example:
`adb -s emulator-5556 shell bmgr restore 4793848356023 com.taskfree.app`
2.7	Logcat should show
`dispatchRestore(): com.taskfree.app`
`restoreFinished(): SUCCESS`
2.8	Launch the app → the Restore-phrase prompt should appear.

