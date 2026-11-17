# Custom repetition requirements

## Purpose and scope

Extend TaskFree's existing repeating tasks to support a user-defined interval in days, weeks or months, configured through a focused Repeat dialog.

This document records the approved product behavior and implementation choices. The shared dialog, interval limit, preview and historical navigation below are settled decisions.

## Confirmed product decisions

| Area | Requirement |
|---|---|
| Repeat basis | Preserve the current behavior: calculate from the scheduled due/base date, not the completion date. |
| Missed occurrences | Keep skipping missed dates. Generate the next scheduled occurrence strictly after today. |
| Month-end behavior | Keep the current clamping and drift behavior. For monthly repetition, 31 January becomes 28 February and then 28 March in a non-leap year. |
| Due-date changes | Preserve the current behavior: changing the due date resets the schedule's base date. |
| Edit scope | Apply repeat changes to the active occurrence and its future successors. Preserve completed history. |
| Editor | Provide a focused Repeat dialog, shared by task creation and task editing. |
| Missing due date | When enabling repetition on an undated task, automatically use today as its first due date. |

## Supported repeat rules

Users must be able to select:

- No repetition.
- Daily or every N days.
- Weekly or every N weeks.
- Monthly or every N months.

Retain the existing Weekdays, Weekends, Quarterly and Yearly options and their behavior. Existing tasks using these options must continue to display and repeat correctly.

N must be a positive whole number from 1 to 99. The editor uses minus/plus buttons that prevent values outside this range; tapping changes the value once and holding repeats the change. It does not open a keyboard. Repository and backup validation retain the same interval constraints. Fixed presets and No repetition require interval one.

An interval of one must display using the familiar labels Daily, Weekly and Monthly. Other intervals must display their actual values, such as Every 3 days or Every 2 months. Custom is an editing concept, not a saved schedule label.

## Scheduling behavior

1. Advance from the current occurrence's saved base date using its complete repeat rule.
2. Continue advancing until the candidate date is strictly after today, preserving the existing catch-up behavior.
3. Create the next occurrence through the existing completion or single-occurrence archive flow. Saving a repeat rule must not generate future task rows by itself.
4. Copy the complete repeat rule to the generated occurrence, retaining the existing occurrence links and creation-date behavior.
5. Preserve the current reminder behavior: the successor receives the same local reminder time on its own due date.

For every N months, each recurrence step adds N months once. It must not simulate that step by adding one month N times. For example, every two months from 31 January reaches 31 March, whereas two separate monthly steps would clamp in February.

Catch-up must preserve clamping between recurrence steps. Do not calculate monthly catch-up directly from an original day-of-month anchor if doing so changes the agreed drift behavior.

Changing only the repeat rule must retain an existing due date. Changing the due date must update the base date and future calculation consistently. Disabling repetition must retain the due date and reminder while clearing the recurrence base date.

Retain the requirement that a repeating task has a due date. Automatically choosing today applies when enabling repetition on an undated task; explicitly clearing the due date of an already repeating task must not leave an invalid saved task.

## Shared Repeat dialog

The same dialog is presented over Add task or task options (including notification entry). Keep the parent mounted and leave application navigation unchanged. Opening Repeat clears text focus without closing the parent or submitting an unfinished note.

The screen must provide:

- The current repeat selection and controls for frequency and interval, using an Every [−] N [+] unit row with localized singular/plural units and accessible button labels.
- Access to the retained preset options and No repetition.
- A date-selection prompt only when the task was undated or the selected rule requires correcting its date. Show the effective calendar date and the provisional Today default in that prompt. Otherwise, the due date remains editable in the parent task form; do not duplicate that editor in Repeat. Hide date controls for No repetition and read-only history.
- Clear validation beside the relevant control.
- An explicit Done action and Back/Cancel behavior.

The editor must keep a draft until Done. Opening the screen, changing a control, or cancelling must not write a repeat rule or automatically chosen date to the database. Preserve the draft through rotation and normal saved-state restoration.

When creating a task, Done returns the rule and effective due date to the parent creation draft. The task is persisted only when the user saves that parent form. When editing an existing task, Done saves through the repository and returns the saved result to the task options panel.

Show one date labelled "Next due if completed today", using the same calculator as occurrence generation. Hide it for No repetition and invalid input. Relative due-date wording includes its effective calendar date. Refresh Today and the preview on resume and day changes.

If saving fails, retain the draft and show the failure. Do not show a successful saved state before the write succeeds.

## Active occurrence and history

Repeat changes must affect the active occurrence and the successors subsequently generated from it. Completed occurrences retain the repeat values that applied to them.

Follow explicit successor links to the terminal unfinished, unarchived task in an active category. Older occurrences show their historical rule and an explicit "Edit current task’s repeat" link when that task exists. The link opens an editor identifying the current task. Recheck eligibility on save; never silently redirect a pending save or create a successor when opening the dialog.

Account for the existing undo behavior: an older occurrence can be reopened while a later occurrence is already in progress or pending. Being unfinished alone does not identify the current schedule. Recheck links, status and archive state when applying the edit so a stale editor cannot change the wrong occurrence.

Use explicit occurrence links only. Do not infer a series from matching task titles, categories or dates. Preserve the existing separation of legacy unlinked tasks.

## Other affected UI and task actions

| Surface or action | Required integration |
|---|---|
| Task options | Show the full saved rule in the Repeat row and open the separate screen to edit it. |
| Task creation | Use the shared editor and retain the rule and effective due date in the creation draft. |
| Due-date editor | Keep the due date, base date, repeat validation and reminder date consistent. |
| Task lists | Show the full rule in Today, category, search, completed and archived views. Allow longer labels to wrap. |
| Notifications | Include the interval in the repeat description. Retrieve it in the notification database projection. |
| Completion and undo | Carry the interval through successor generation and preserve existing successor deletion/protection behavior. |
| Clone | Copy the full repeat rule into the independent cloned task without linking it into the original occurrence chain. |
| Archive and restore | Preserve the rule when archiving or restoring a task. Single-occurrence archiving must generate a successor using the full rule where it does so today. |
| Series archive and delete | Preserve existing link-based behavior and reminder cancellation. Adding an interval must not change which tasks an action targets. |
| Filters and sorting | Continue to use stored occurrence dates. A schedule preview must not imply that future occurrences have already been created. |
| Accessibility and localization | Provide English and Spanish labels and plural forms, complete screen-reader descriptions, usable touch targets and support for the app's larger text settings. |

## Recommended backend design

### Repeat value and persistence

Keep the existing `Recurrence` enum and introduce a domain value that carries the type and interval together:

```kotlin
data class RepeatRule(
    val recurrence: Recurrence,
    val interval: Int = 1
)
```

Validate this value before it reaches persistence or scheduling. Allow intervals greater than one for DAILY, WEEKLY and MONTHLY. Require interval one for NONE and the retained fixed presets.

Retain the existing `recurrence` database column and add `recurrenceInterval INTEGER NOT NULL DEFAULT 1`. Existing rows receive interval one without rewriting their current recurrence values, dates or links.

Use the complete rule in task creation and edit commands so callers cannot save the type and interval separately. Keep the selected interval in the UI draft until Done.

The current occurrence model and unique successor link are sufficient for this feature. A separate series table is not part of the proposed implementation.

### Shared domain calculation and validation

Use pure Kotlin calculation code accepting a repeat rule, a base `LocalDate` and an explicit today value. Resolve today through the existing `DateProvider` at the application boundary. Keep Compose types and localized strings out of this calculation.

Use the same rule validation for creation, editing and imported data. The editor may validate early, but repository operations must enforce the rules independently.

Use one resource-backed formatter for repeat descriptions in task lists, the editor and notifications, replacing duplicated enum-only mappings where necessary.

### Atomic writes and reminders

Use a repository operation that reads the latest task, validates the edit target and rule, resolves the effective due date, and saves the schedule in one database transaction.

Save recurrence, interval, due date, base date and any corresponding reminder-date change consistently. Use the existing mutation queue for UI commands. Return the persisted result so the UI and reminder handling do not reconstruct it independently from a stale snapshot.

Schedule or cancel Android alarms after the transaction succeeds. Preserve the existing handling of reminders that are already in the past or blocked; automatically choosing today must not silently invent a new reminder time.

## Migration and backup compatibility

- Add a non-destructive Room migration. With the currently reviewed schema, this is version 20 to 21. Register it for both ordinary and encrypted database opening through the shared factory.
- Advance the backup format from the currently reviewed 1.1 to 1.2 and write the interval explicitly.
- Continue to read supported legacy backup formats, assigning interval one when the field is absent.
- Preserve all recurrence values, dates, occurrence links, notes and other task metadata during migration and restore.
- Validate interval values and rule combinations before replacing existing data. Reject invalid imports without partially restoring them.
- Ensure older application versions reject the new backup format. They must not ignore the new interval and reinterpret Every 3 weeks as Weekly.
- Preserve intervals through both plain and encrypted backup round trips. Encryption and backup transport behavior otherwise remain outside this change.

The backup version increment is necessary because the current reader ignores unknown fields. Adding a field without advancing the format version would allow silent loss of interval semantics in older applications.

## Acceptance criteria and verification

| Area | Acceptance criteria |
|---|---|
| Basic schedules | Daily, weekly and monthly with interval one produce the existing results. Custom intervals advance by the selected number of days, weeks or months. |
| Late completion | Missed dates are skipped and the generated due date is strictly after today. |
| Early completion | Completing an occurrence before its due date still advances from its scheduled base date, preserving the current behavior. |
| Month ends | Monthly 31 Jan 2026 produces 28 Feb then 28 Mar. Every two months from 31 Jan 2026 produces 31 Mar. Catch-up preserves per-step clamping. |
| Undated tasks | Applying a repeating rule supplies today and a matching base date. Cancelling leaves the original draft or saved task unchanged. |
| Editing | Rule-only edits retain an existing due date. Due-date edits reset the base date. Disabling repetition retains the due date and reminder. |
| History and stale state | Completed history is unchanged. Editing an older reopened occurrence cannot overwrite the current occurrence's schedule accidentally. |
| Lifecycle | Completion, archive and clone copy the full rule. Undo does not create duplicate successors or change the existing protection of started, pending, completed or archived successors. |
| Persistence | Existing database rows receive interval one. Newly saved intervals survive closing and reopening the app. |
| Backups | Legacy imports retain their old schedules. New plain and encrypted backups round-trip intervals. Invalid intervals are rejected before existing data is replaced. |
| Notifications | Reminder text contains the interval and the next reminder uses the correct successor date and existing local time. |
| Editor | Create and edit entry points share behavior; Done, Cancel, validation, save failures and draft restoration work consistently. |
| Presentation | Full repeat descriptions remain readable in English, Spanish, large text and screen-reader output. |

Use focused JUnit tests for pure recurrence logic and the repository's existing Robolectric helpers for persistence, view-model and notification behavior. Add Compose smoke tests for the Repeat dialog and relevant SQLCipher migration/restore coverage. Follow the repository's existing test guidance, including `vmTest` for `TaskViewModel` tests.

## Outstanding details and existing limitations

- Intervals are limited to 1–99 for Days, Weeks and Months.
- An automatic Today that is incompatible with Weekdays/Weekends produces an inline error. A compatible date must be selected before Done.
- The current calculator stops after 3,000 recurrence steps and can fail to produce a successor for sufficiently old schedules. Track this existing limitation separately; do not silently change catch-up semantics as part of the interval work.
- Existing undo can delete a TODO successor even after that successor has been edited. The interval feature should preserve this behavior unless a separate change is agreed; it does not introduce independent series-wide settings that survive such deletion.

## Out of scope

Completion-relative repetition, preserved original day-of-month anchors, multiple selected weekdays, rules such as the last Friday of a month, repeat end dates/counts, one-occurrence scheduling exceptions, advance creation of all future tasks, and unrelated UI or database refactors are outside this change.
