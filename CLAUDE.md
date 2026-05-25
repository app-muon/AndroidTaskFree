# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

On Windows, use `gradlew.bat` instead of `./gradlew`.

```bash
./gradlew :app:assembleDebug       # Build debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # Build release APK (requires signing config)
./gradlew test                     # Run unit tests
./gradlew connectedAndroidTest     # Run instrumented tests (requires emulator/device)
./gradlew lint                     # Lint (abort-on-error disabled due to Kotlin 2.1.x + lifecycle lint bug)
./gradlew clean                    # Clean build outputs
```

Release builds are normally done via Android Studio: Build → Generate Signed Bundle / APK.

## Architecture Overview

**TaskFree** is a privacy-focused task manager. Package root: `com.taskfree.app`.

### Layered MVVM + Repository Pattern

- **`domain/`** — Business logic and models: `Task`, `Category`, `Recurrence`, `TaskStatus`. Recurrence calculation and reminder validation live here.
- **`data/`** — Room database (v16 with migrations), DAOs, `TaskRepository`, `CategoryRepository`, `BackupManager` (Google Drive / local encrypted backups).
- **`ui/`** — Jetpack Compose screens and `ViewModel`s (`TaskViewModel`, `CategoryViewModel`, `ToolsViewModel`). ViewModels use a factory pattern for dependency injection.
- **`notifications/`** — `NotificationsScheduler` + `AlarmReceiver`. A `BootReceiver` reschedules alarms after device restart.
- **`settings/`** — DataStore + SharedPreferences for app preferences.

### Key Entry Points

- **`App.kt`** — Application class; loads SQLCipher native library.
- **`MainActivity.kt`** — Single activity; handles notification permission (API 33+), database encryption state, and reindexes task page orders on resume.
- **`AppNav.kt`** — Jetpack Navigation Compose hub routing to all screens; redirects to key-recovery flow if database is encrypted but key is not cached.

### Encryption

Database encryption is optional. SQLCipher is used for encrypted storage; `DatabaseKeyManager` and `MnemonicManager` manage the key and mnemonic recovery phrase. Backups can also be encrypted before being sent to Google Drive.

### Notable Libraries

| Library | Purpose |
|---|---|
| Jetpack Compose (BOM 2025.05.00) | All UI |
| Room 2.7.1 + SQLCipher 4.9.0 | Encrypted local database |
| Navigation Compose 2.9.0 | In-app navigation |
| DataStore Preferences 1.1.1 | Settings persistence |
| Kotlinx Serialization 1.6.3 | Backup JSON serialization |
| Reorderable 2.4.3 | Drag-and-drop task reordering |

Dependency versions are centralized in `gradle/libs.versions.toml`.

## Backup/Restore Testing (ADB)

See `dev_tips.md` for the full step-by-step. Summary:

```bash
# Build and install
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# Trigger backup
adb -s emulator-5554 shell bmgr enable true
adb -s emulator-5554 shell bmgr transport com.google.android.gms/.backup.BackupTransportService
adb -s emulator-5554 shell bmgr backupnow com.taskfree.app
adb -s emulator-5554 shell bmgr list sets   # copy the token

# Restore on second emulator
adb -s emulator-5556 shell bmgr restore <TOKEN> com.taskfree.app
```
