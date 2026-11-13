# Task notes: reduced specification

This is the current implementation specification for task notes. It supersedes
[`task_extension.md`](task_extension.md), which is retained as the earlier proposal. It adds dated,
editable notes and autosaves edits to titles and existing notes. The first release includes the
storage and data-preservation work needed to keep notes safe through recurrence changes, backups,
and enabling encryption. Nothing described here is built yet.

## Scope and decisions

- Users can add, edit, and delete notes on any existing task, including done and archived tasks.
- Notes contain plain text, can span several lines, and display tappable links.
- Each note has a creation timestamp. Editing adds an "edited" marker without changing its position.
- Each recurring occurrence owns its notes. New occurrences and clones start with no notes.
- Reopening an earlier occurrence must preserve a successor that contains notes.
- Notes appear in a section of the task sheet. The task list shows an icon when notes exist.
- Titles and existing notes save automatically, with no Save or Cancel buttons.
- New notes keep **Add** for immediate submission. Closing the sheet or genuinely backgrounding the
  app also submits any nonblank new note. A typing pause alone does not create a note.
- Title autosave changes existing behavior and makes editing more consistent with due date,
  reminder, repeat, and category selection. Creating a task in `NewTaskDialog` keeps its Save button.
- Notes are included in manual backups, automatic backups, restore, and encryption migration.
- English and Spanish UI text ship together.

Deferred from the original proposal:

- Automatic status-change and due-date history.
- Searching note text. Search continues to match task titles using its existing filters.
- A mixed activity timeline and its entry kinds, old/new values, and formatting rules.

There is no title versioning, note revision history, shared series notebook, or rich-text editor.

## User interface

### Task sheet

Place `TaskNotesSection.kt` below `TaskDatesRow` and above the actions:

```text
Created Oct 2   Completed -
------------------------------
Notes
[ Write a note...        ] Add
Oct 15 14:02
  Plumber says part ordered
Oct 12 09:30
  Call back on Friday
Show all (8)
------------------------------
Postpone  Tomorrow Sat Sun
```

- There is no separate notes dialog or `Notes (0)` row. The composer makes adding a note discoverable.
- Put the multiline "Write a note..." composer at the top of the section. Bound its height and let
  its text scroll so the keyboard and a long draft do not hide the rest of the sheet.
- Add, sheet close, and app backgrounding use the same submission operation, inserting one note
  with `editedAt = null`. Disable Add for blank text and while insertion is pending. Keep the
  submitted text stable and clear the composer only after success. Repeated taps, overlapping
  close/background events, and retries after a known failure must not create duplicate notes.
- Background submission finishes the new note: on return after success, show the saved note and an
  empty composer. Further changes use that note's pencil editor. Rotation alone must not submit a
  note or start a new writing session.
- Merely moving focus to another field leaves a new note in the composer until Add, sheet close, or
  backgrounding. Users can discard it by clearing the field before submission; no discard
  confirmation is needed. A successfully closed sheet leaves no unsubmitted draft to retain by task
  ID. Pending or failed submissions keep their text until resolved.
- Show notes newest first, ordered by `createdAt DESC, id DESC` for stable ordering.
- Initially show the latest five, with **Show all (n)** when there are more. Allow returning to the
  latest five with **Show fewer**.
- Display at most three lines of each note initially, with an action to expand or collapse longer
  text. Limiting the number of notes alone does not keep the task actions reachable.
- Keep `PanelActionList` unchanged for this release and render the notes section in its existing
  header. The initial five previews bound the default list height. "Show all" can become expensive
  for very large collections; accept that limitation for now and revisit lazy rendering if needed.
- Each note shows its localised creation date/time, text with tappable links, and an edit action.
  Reuse the existing date/time formatter and linked-text component.
- Show an "edited" marker when `editedAt` is present. Editing keeps the original creation timestamp
  and ordering; older text is not retained.
- The pencil edits a note in place, showing Delete alongside the editor. Allow one active text editor
  at a time, including the always-visible composer. Focusing the composer flushes and closes an
  active title or existing-note editor. Focusing another editor removes focus from the composer
  without submitting it. Switching between existing-note/title editors flushes the field being left.
- Delete uses a `ConfirmDialog` owned by the notes section. Do not route it through `TaskDialogHost`,
  whose mutually exclusive dialog states would replace the task sheet.
- Note fields reject input or a paste that would exceed 2,000 characters without truncating it.
  Do not show a persistent character count. Show "2,000-character limit reached" when rejecting input;
  deleting or replacing text within the limit must remain possible.
- The title field likewise rejects typing or pasting beyond 100 characters and shows limit feedback.
  Share its limit with repository validation; never display a longer value as saved and silently cut
  it later. Keep validation in the repository for callers other than the editor.
- Preserve drafts, active note identity, and editing state through configuration changes. Database
  emissions must not replace newer local text or move the cursor while the user is typing.

### Autosave for titles and existing notes

- Save changed, valid text after about one second without typing, and when leaving the field:
  keyboard Done, tapping elsewhere, opening another field, or closing the sheet. For multiline
  notes, preserve the keyboard's newline action; handle Done when the keyboard provides it.
- Also request a flush when the app genuinely backgrounds, submitting a nonblank new note at the
  same time. Distinguish backgrounding from configuration changes. This reduces the amount of text
  at risk but cannot guarantee saving the final keystrokes before abrupt process termination.
- Do not save on every keystroke or rewrite unchanged normalized text. Save titles through a
  dedicated repository `updateTitle(id, text)` that updates only the title column. Do not use
  `applyEdits` for title autosave or reschedule alarms: reminder delivery reads the current title.
- Save trimmed text, but keep the raw editor value while focused so spaces and line breaks are not
  removed during typing.
- Never persist blank text. On field exit, restore the last successfully saved value, accounting
  for any earlier nonblank write still in flight. Blank text is not a request to delete a note.
- There are no Save or Cancel buttons for these editors. Remove the title editor's `EditCancelRow`;
  other fields keep their existing controls. Cancel would imply undoing already persisted text.
- Explicit closing includes Back, outside dismissal, the close action, navigation, and replacing the
  sheet with archive, clone, or permanent-delete confirmation. Flush edits and submit any nonblank
  new note before dismissing or replacing the sheet. If saving fails, keep the sheet open with the
  text and a localised "Couldn't save" message with Retry. Do not depend only on focus-loss callbacks
  or composable disposal.
- The ViewModel owns pending writes. Keep the latest text until a successful database write
  acknowledges it; an older save completing must not clear a newer edit. The UI remains responsive
  while a close request awaits the write.
- Backgrounding cannot wait for an error to be resolved. Retain failed text and show the error in
  the sheet when the user returns. No separate task-screen error UI or map of drafts for successfully
  closed sheets is needed. Pending state is not a guarantee of survival through process termination.
- Do not treat logging an exception or launching a coroutine as confirmation that a save succeeded.

### Save ordering and task actions

- Use one sequential command queue for relevant `TaskViewModel` mutations: title/note writes,
  metadata edits, completion, archive, clone, and deletion. One consumer awaits each complete
  suspending operation before processing the next command, in submission order.
- `Dispatchers.IO.limitedParallelism(1)` is not a substitute: it permits operations to interleave
  when they suspend. Debouncing also does not establish ordering.
- A flush cancels the pending debounce timer and enqueues the latest changed text immediately before
  the dependent action, even if the one-second pause has not elapsed. Do not launch an independent
  write from inside a queued command and finish the command before that write completes.
- Completion, archive, and clone must see the latest committed title; generated occurrences and
  clones inherit it. If its save fails, do not proceed with an older title. Other metadata updates
  must use the same queue, since a stale full-details update could otherwise overwrite a new title.
- A failed note save does not globally lock task actions. Actions that keep the sheet open and do
  not depend on that save may proceed while its error/text remain available. Actions that close or
  replace the sheet follow the explicit-close rule above.
- Edit existing notes with plain `UPDATE`s by ID, never insert-or-replace/upsert. Check the affected
  row count: a missing note is not a successful save and cannot be recreated by a delayed update.
- If a note or its parent task was deliberately deleted, treat a subsequent update as an expected
  missing-target result. A pending insertion for that deleted task can be abandoned. Do not swallow
  unrelated constraint/database errors; handle unexpected failures through the normal save error.
  No general cancellation mechanism is required solely to prevent note resurrection.

### Task list

`TaskRow.kt` shows a small notes icon beside its existing metadata when the task has at least one
note. Give the icon a localised accessibility description. It is an indicator; opening the task
continues to use the existing row interaction.

Adding the first note or deleting the last note updates the icon immediately after the write succeeds.

## Data model

Add one `TaskNote` table. The `Task` table does not gain a notes column.

```kotlin
@Entity(
    foreignKeys = [ForeignKey(
        entity = Task::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("taskId")]
)
data class TaskNote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val taskId: Int,
    val text: String,
    val createdAt: Instant,
    val editedAt: Instant? = null
)
```

Use the existing `Instant` Room converter. Make the entity serializable for backups, using the same
`Instant` serialization convention as `Task`. No entry-kind enum or new type converter is needed.

- Trim leading and trailing whitespace on add/edit; preserve internal line breaks.
- Reject blank text and text longer than 2,000 characters after trimming. Never silently truncate.
- Define the limit once and share it between editor, repository, and backup validation.
- Use `dates.nowInstant()` for timestamps so tests can control them.
- On edit, retain `createdAt` and set `editedAt` only when the normalized text actually changes.
- Writing a new draft does not touch the database until Add, sheet close, or app backgrounding submits
  it. The shared submission creates one unedited note and clears the composer after success. Later
  edits may set `editedAt`; no first-writing-session flag or `markEdited` argument is needed.
  Rotation alone does not create a note or mark one edited.
- Note edits cannot move a note to another task.
- Deleting a task cascades to its notes, including archive clearing and category deletion that
  actually removes tasks. Archiving alone retains notes.

For the current database version, add migration **19 -> 20**, register it in
`AppDatabaseFactory.kt`, and add the entity and DAO to `AppDatabase.kt`. The migration creates an
empty notes table with its foreign key and index; it does not fabricate notes for existing tasks.

## Repository and UI data flow

Add note operations to `TaskRepository`; a separate repository is unnecessary for this scope.

- Observe the notes for one task.
- Observe note counts grouped by task ID. Missing IDs mean zero notes.
- Add, edit, and delete a note, enforcing validation in the repository.
- Update only a task's title through `updateTitle(id, text)`, enforcing the shared 100-character
  limit and nonblank validation without silently truncating text or changing reminder scheduling.
- Read and insert notes for backup and encryption migration through `TaskNoteDao`.

Expose the operations through `TaskViewModel`. Observe full note text only while that task's sheet
is open. Use the count stream for row indicators; do not load every note's text into a global search
map. Keep note queries reactive so note-only changes update the UI without requiring a task-table
change. Cancel the sheet's observation when it closes or changes task. Keep write processing separate
from observation so configuration changes and backgrounding do not cancel pending submissions.

The ViewModel tracks the current sheet's editor text, last successfully saved values, note/task
identity, and pending/error state. Add, close, and backgrounding share the same in-flight submission
identity. Expose write outcomes for submission, autosave, and Retry instead of relying on the current
log-only `launchIO` error handling. Keep the queue focused on these task mutations; no general
persistence framework or collection of drafts for closed sheets is required.

Status changes, due-date edits, postponement, and ordinary task edits do not generate history notes.
If an action closes the sheet, that exit still submits any text in the new-note composer.

## Recurring tasks and reopening

The current `deleteTodoOccurrence` query checks that a successor is To do, has no completion date,
and is not archived. Those checks do not establish that the task is untouched.

Add a further condition: the successor has **no `TaskNote` rows**. Perform this check as part of
the conditional delete inside the existing status-change transaction, avoiding a separate UI check.

| Action | Result |
|---|---|
| Complete a recurring task | Keep its notes; create the next occurrence with no notes |
| Archive one recurring occurrence | Keep its notes; any newly created successor has no notes |
| Clone a task | The clone has no notes |
| Reopen an occurrence whose successor meets the existing deletion conditions and has no notes | Delete the successor as today |
| Reopen an occurrence whose successor contains notes | Preserve the successor, its notes, and its `sourceTaskId` link |
| Complete that reopened occurrence again | Reuse the existing successor link; do not create a duplicate |

Example: complete Monday's task, add a note to Tuesday's task, then reopen Monday. Both tasks remain,
and Tuesday's note is preserved. Existing handling of successors already in progress, done, or
archived continues to apply. This change does not introduce general tracking of task edits.

## Backup and restore

- Add `notes: List<TaskNote> = emptyList()` to `Backup`.
- Export backup version `"1.1"`. The new app accepts `"1.0"` and `"1.1"`; older app versions reject
  `"1.1"` rather than silently dropping notes.
- `BackupManager.snapshot` reads categories, tasks, and notes in the same transaction. Sort notes by
  ID for deterministic output.
- Extend restore and `TaskRepository.replaceAll` to restore notes. Within one transaction, remove
  old tasks and their cascading notes, restore categories and tasks, then insert the restored notes.
- Validate the complete backup before replacing data: note IDs must be positive and unique, every
  `taskId` must exist in the backup, and text must be nonblank and within the length limit. Reject
  invalid notes rather than dropping or truncating them. Existing serializer validation remains.
- Preserve note IDs, text, creation timestamps, and edit timestamps on round trip.
- An existing `"1.0"` backup without notes restores with empty notes. Restore replaces existing notes
  along with the rest of the database; it does not merge them.
- Add `TaskNote` to `AutoBackup`'s observed tables. Adding, editing, or deleting only a note must
  trigger the existing automatic-backup process.
- The watcher normally saves after about 60 seconds without database changes, but the app also
  attempts backups on entering and leaving. Autosave does not imply a backup for every keystroke.
- Keep the leaving-app backup independent of the edit flush. It may snapshot before the latest
  edits commit. Their database changes trigger the normal backup mechanism; the next successful
  backup includes them, possibly on the next app launch. Do not promise that the immediate exit
  backup contains the final edit or make `MainActivity` wait for the ViewModel's write queue.
- Retain existing backup encryption, file-size limits, and failure reporting.

## Enabling database encryption

Storing notes in the same database provides encryption once that database is encrypted. Enabling
encryption on an existing database also requires explicit copying and verification changes.

`RealDatabaseMigrator.kt` currently copies and verifies only categories and tasks. Extend it to:

1. Copy notes after their parent tasks in `copyTables`.
2. Include notes, sorted by ID, in its verification `Snapshot`.
3. Compare all note fields both after copying and after reopening the installed encrypted database.

A missing or altered note must cause verification to fail through the existing migration recovery
path. Do not change key derivation, recovery phrases, or the migration journal protocol.

Backup support and encryption copying must be complete before the UI permits users to create notes.

## Files

New production files, relative to `app/src/main/java/com/taskfree/app`:

- `data/entities/TaskNote.kt`
- `data/database/TaskNoteDao.kt`
- `ui/task/TaskNotesSection.kt`

Changed production files:

- `data/database/AppDatabase.kt`, `DatabaseMigrations.kt`, and `TaskDao.kt`
- `data/AppDatabaseFactory.kt` and `RealDatabaseMigrator.kt`
- `data/repository/TaskRepository.kt`, `Backup.kt`, and `BackupManager.kt`
- `data/backup/AutoBackup.kt`
- `ui/task/TaskViewModel.kt`, `TaskOptionsPanel.kt`, and editor state as needed for autosave
- Task dialog/action wiring for flushing before dismissal and sequencing dependent actions
- Lifecycle observation for background submission, independent of the existing backup exit hook
- `ui/task/components/TaskRow.kt` and the list wiring that passes `hasNotes`
- `app/src/main/res/values/strings.xml` and `values-es/strings.xml`

Reuse existing action labels where appropriate. Add localised heading, hint, Show all/Show fewer,
expand/collapse text, edited marker, limit-reached message, save-failure/Retry, delete confirmation,
and icon accessibility text. There is no character-count label or discard confirmation.
`TaskFilterUtils.kt` and `PanelActionList.kt` need no change.

## Verification

Use the existing repository test helpers and controlled dates. Keep tests focused on behavior.

Local Robolectric tests:

- Add/edit/delete validation, including blank input, the note/title length boundaries, and preserved
  note line breaks. Title-only saves leave all other task fields and alarm scheduling unchanged.
- New drafts make no database writes until Add, close, or backgrounding. Each submission creates
  one unedited note, clears the composer only on success, and later edits mark the note edited.
- Edits preserve creation time and ordering; saving unchanged text does not mark a note edited.
- Debounce and field-exit saves persist the latest title/note once, including pause followed by close.
- Overlapping Add/close/background events and repeated Add taps create one note. Rotation during
  insertion neither duplicates the submission nor starts another one.
- Rapid saves preserve the newest text even when earlier writes are slow. Task metadata changes and
  title autosave do not overwrite one another.
- Immediate completion, archive, and clone wait for pending saves and use the latest title.
- Blank edits restore the last successfully saved value; deleting requires an explicit confirmation.
- A failed submission/autosave during explicit close keeps the sheet open and text available for
  Retry; a background failure remains available on return. An older success never erases newer text.
- A delayed plain update cannot restore a deleted note. Inserting for a deliberately deleted parent
  is abandoned, while unexpected insertion failures are reported and retain the text.
- A failed title save prevents a dependent clone/completion from using the old title. An unrelated
  note error does not block actions that keep the sheet open and do not depend on that save.
- Notes can be added to done and archived tasks.
- New repeats and clones start empty; source notes remain intact.
- Reopening deletes an eligible successor without notes, preserves one with notes, and completing
  again does not create a duplicate successor.
- Task deletion cascades to notes; archiving retains them.
- Migration 19 -> 20 preserves tasks and categories and creates the notes table correctly.
- Backup round trip preserves all note fields, including timestamps; old backups restore with empty
  notes; invalid note IDs, references, and text are rejected before replacing existing data.
  Malformed timestamps still fail decoding; reuse any existing coverage rather than duplicate it.
- Note-only add/edit/delete operations trigger automatic backup and update observed counts/content.

Instrumented tests:

- SQLCipher migration preserves notes and edited timestamps, including after database reopen.
- Encryption verification detects an omitted or altered note and invokes existing failure recovery.
- Focused Compose smoke tests cover typing a new note and closing without Add, submitting with Add
  then immediately closing, and editing an existing note/title then closing before the debounce.
  Reopening shows the saved text and icon, with no duplicate note and an empty new-note composer.
- Rotation preserves a new draft and the identity of an existing note being edited without adding
  duplicates or marking unchanged text edited.

Manual validation on an emulator/device should cover long multiline text, links, previews and
Show all/Show fewer, the keyboard covering the lower note field, limit feedback for typing/paste,
rotation mid-typing, leaving the app mid-typing, and English/Spanish labels. Verify focusing the
composer closes another editor, a successful background submission leaves it empty on return, and
failed saves retain text for Retry. Check the 100-character title limit for both typing and paste,
and confirm explicit close stays open on an injected write failure. Exercise Show all with a large
collection to assess the accepted first-release limitation without changing the shared panel.
Also create notes before enabling encryption, restart the app, verify them, then perform an encrypted
backup/restore round trip. Document the device/ADB steps used in the implementation commit, following
`dev_tips.md`.

## Implementation order

1. Entity, DAO, database migration, and migration test.
2. Repository note/title operations, recurrence protection, and focused tests.
3. Backup/restore, automatic-backup observation, encryption copying/verification, and their tests.
4. Inline notes section, title/note autosave and ordered actions, list indicator, lifecycle flush,
   error feedback, and English/Spanish strings.
5. Focused UI/device validation.

Search and automatic history remain separate future decisions. Do not add placeholder event types
or extra history fields to this release.
