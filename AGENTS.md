# Repository Guidelines

## Project Structure & Module Organization

TaskFree is a single-module Android app. The Gradle root includes `:app`, with source under `app/src/main/java/com/taskfree/app`. Key packages are `data/` for Room, repositories, backup, and serialization; `domain/` for task/category models and recurrence logic; `ui/` for Jetpack Compose screens, navigation, view models, and theme; `notifications/` for alarm scheduling and receivers; and `settings/` for persisted preferences. Android resources live in `app/src/main/res`, including localized strings in `values-es`. Unit tests belong in `app/src/test/java/com/taskfree/app`; instrumented tests belong in `app/src/androidTest/java/com/taskfree/app`. Documentation and screenshots are in `docs/`.

## Build, Test, and Development Commands

Use the Gradle wrapper from the repository root. On Windows, prefer `gradlew.bat`.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat test
.\gradlew.bat connectedAndroidTest
.\gradlew.bat lint
.\gradlew.bat clean
```

`assembleDebug` creates `app/build/outputs/apk/debug/app-debug.apk`. `assembleRelease` requires the signing properties referenced by `app/build.gradle.kts`. `test` runs local JVM tests, `connectedAndroidTest` requires an emulator or device, and `lint` runs Android lint with known Kotlin/lifecycle lint failures configured not to abort the build.

## Coding Style & Naming Conventions

Write Kotlin targeting Java 17, using four-space indentation and idiomatic Kotlin naming: `PascalCase` for classes/composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` only for constants. Keep Compose UI in `ui/`, persistence in `data/`, and business rules in `domain/`. Dependency versions are centralized in `gradle/libs.versions.toml`; avoid hard-coding new versions in module build files.

## Testing Guidelines

Add focused tests when changing recurrence, database migrations, backup/restore, notification scheduling, or view model behavior. Name test classes after the subject, for example `TaskRepositoryTest` or `RecurrenceTest`. Use local unit tests for pure Kotlin and repository logic where possible; use instrumented tests for Room, Android services, permissions, and UI behavior that requires a device.

## Commit & Pull Request Guidelines

Recent commits use short, imperative or past-tense summaries, for example `Prevented startup crashes...` and `Notification now has correct repeat description`. Keep the first line specific and user-visible when possible. Pull requests should include a concise description, test commands run, screenshots for UI changes, and notes for database, backup, encryption, or notification changes. Link related issues when available.

## Security & Configuration Tips

Do not commit local signing credentials, generated APKs, or personal `local.properties` changes. Treat encryption keys, mnemonic recovery logic, and backup transport behavior as high-risk areas; document manual ADB validation steps in the PR when changing them. See `dev_tips.md` for backup and restore testing commands.
