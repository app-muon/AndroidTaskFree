package com.taskfree.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.ui.task.PANEL_PINNED_ACTIONS_TAG
import com.taskfree.app.ui.task.PANEL_SCROLLING_ACTIONS_TAG
import com.taskfree.app.ui.task.TASK_SHEET_VIEWPORT_TAG
import com.taskfree.app.ui.task.components.SEARCH_TOGGLE_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TaskNotesUiTest {
    private val reset = ResetAppStateRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    @After fun finish() { scenario?.close() }

    private fun launch(notes: Int = 0) {
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0xFF336699)))
            taskDao().insert(Task(1, 1, "Notes test", due = LocalDate.now(), singleCategoryPageOrder = 0))
            taskNoteDao().insertAll((1..notes).map {
                TaskNote(it, 1, "Note $it\nSecond line\nThird line\nhttps://example.com/fourth\nFifth line",
                    Instant.parse("2026-10-16T09:00:00Z").plusSeconds(it.toLong()))
            })
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasContentDescription("Notes test", substring = true))
        compose.onNode(hasContentDescription("Notes test", substring = true)).performClick()
        waitFor(hasTestTag("add-note"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    /** The task list shows no search progress; let the debounced note search settle before opening a row. */
    private fun searchNotes(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        waitFor(hasContentDescription("Insurance", substring = true))
        Thread.sleep(1_000)
        compose.waitForIdle()
    }
    private fun notes() = runBlocking { reset.db().taskNoteDao().getAllNow() }
    private fun SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher("Editable text equals the draft") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == value
        })
    private fun typeDraft(text: String) {
        if (compose.onAllNodes(hasTestTag("note-composer")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("add-note").performScrollTo().performClick()
        compose.onNodeWithTag("note-composer").performScrollTo().performTextInput(text)
    }
    private fun assertComposerClosed() {
        compose.onNodeWithTag("note-composer").assertDoesNotExist()
        compose.onNodeWithTag("add-note").assertExists()
    }
    private fun closeSheet() {
        // The IME can cover Close on smaller devices; dismiss it before injecting the tap.
        val close = compose.onNode(hasText(str(R.string.close_yes_dialog_button)) and hasAnyAncestor(isDialog()))
        val view = (close.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnUiThread {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
        compose.waitUntil(10_000) {
            !compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        close.performClick()
    }
    private fun awaitClosed() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("task-title-row")).fetchSemanticsNodes().isEmpty()
    }
    private fun reopen(title: String = "Notes test") {
        compose.onNode(hasContentDescription(title, substring = true)).performClick()
        waitFor(hasTestTag("add-note"))
    }

    @Test fun searchExpandsOnlyHiddenNotesAndOverflowingText() {
        val oldText = "Older context\n".repeat(35) + "Policy AB123"
        val firstLineMatch = "Visible keyword\n" + "More context\n".repeat(20)
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0xFF336699)))
            taskDao().insert(Task(1, 1, "Insurance", due = LocalDate.now(), singleCategoryPageOrder = 0))
            taskNoteDao().insertAll((1..8).map {
                TaskNote(it, 1, when (it) {
                    1 -> oldText; 7 -> firstLineMatch; 8 -> "Short keyword"; else -> "Other note $it"
                },
                    Instant.parse("2026-10-16T09:00:00Z").plusSeconds(it.toLong()))
            })
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasContentDescription("Insurance", substring = true))
        compose.onNodeWithTag(SEARCH_TOGGLE_TAG).performClick()
        searchNotes("Short keyword")
        compose.onNode(hasContentDescription("Insurance", substring = true)).performClick()
        waitFor(hasTestTag("note-8"))
        compose.onNodeWithTag("note-8").assertIsDisplayed()
        compose.onNodeWithTag("note-1").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.note_collapse)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.notes_show_all, 8)).assertExists()
        closeSheet()
        awaitClosed()

        searchNotes("Visible keyword")
        compose.onNode(hasContentDescription("Insurance", substring = true)).performClick()
        waitFor(hasTestTag("note-7"))
        compose.onNode(hasText(str(R.string.note_expand)) and hasAnyAncestor(hasTestTag("note-7"))).assertExists()
        compose.onNodeWithText(str(R.string.note_collapse)).assertDoesNotExist()
        closeSheet()
        awaitClosed()

        searchNotes("AB123")
        compose.onNode(hasContentDescription("Insurance", substring = true)).performClick()
        waitFor(hasTestTag("note-1"))
        compose.onNodeWithText(oldText).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(oldText).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val line = layout.getLineForOffset(oldText.indexOf("AB123"))
        try {
            compose.waitUntil(5_000) {
                val node = compose.onNodeWithText(oldText).fetchSemanticsNode()
                node.positionInRoot.y + layout.getLineBottom(line) <= node.boundsInRoot.bottom
            }
        } catch (_: ComposeTimeoutException) {
            // Report the actual clipped bounds below if the jump never reaches the match.
        }
        val noteNode = compose.onNodeWithText(oldText).fetchSemanticsNode()
        val viewport = compose.onNodeWithTag(TASK_SHEET_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val fade = 56 * context.resources.displayMetrics.density
        assertTrue("The matching line must be visible: position=${noteNode.positionInRoot}, " +
            "bounds=${noteNode.boundsInRoot}, line=${layout.getLineTop(line)}..${layout.getLineBottom(line)}",
            noteNode.positionInRoot.y + layout.getLineTop(line) >= viewport.top + fade &&
                noteNode.positionInRoot.y + layout.getLineBottom(line) <= viewport.bottom - fade)
        compose.onNode(hasText(str(R.string.note_collapse)) and hasAnyAncestor(hasTestTag("note-1")))
            .performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.note_expand)) and hasAnyAncestor(hasTestTag("note-1"))).assertExists()
        scenario!!.recreate()
        waitFor(hasTestTag("note-1"))
        compose.onNode(hasText(str(R.string.note_expand)) and hasAnyAncestor(hasTestTag("note-1"))).assertExists()
        compose.onNodeWithTag("note-composer").assertDoesNotExist()
    }

    @Test fun keyboardKeepsActionPlacementStableAndLeavesTheNoteEditorVisible() {
        launch(notes = 1)
        val pinned = compose.onNodeWithTag(PANEL_PINNED_ACTIONS_TAG).isDisplayed()
        typeDraft("Writing with the keyboard open")
        compose.onNodeWithTag("note-composer").performClick()
        val view = (compose.onNodeWithTag("note-composer").fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.onNodeWithTag(PANEL_PINNED_ACTIONS_TAG).assertIsNotDisplayed()
        if (pinned) compose.onNodeWithTag(PANEL_SCROLLING_ACTIONS_TAG).assertDoesNotExist()
        else compose.onNodeWithTag(PANEL_SCROLLING_ACTIONS_TAG).assertExists()
        compose.onNodeWithTag("note-composer").assertIsDisplayed()
        closeSheet()
        awaitClosed()
    }

    @Test fun rotationRetainsNoteSearchAndListPosition() {
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0xFF336699)))
            (1..40).forEach {
                taskDao().insert(Task(it, 1, "Insurance $it", due = LocalDate.now(), singleCategoryPageOrder = it))
                taskNoteDao().insertAll(listOf(TaskNote(it, it, "Policy AB123",
                    Instant.parse("2026-10-16T09:00:00Z"))))
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasTestTag(SEARCH_TOGGLE_TAG))
        compose.onNodeWithTag(SEARCH_TOGGLE_TAG).performClick()
        searchNotes("AB123")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        val row = hasContentDescription("Insurance 21", substring = true)
        val before = compose.onNode(row).fetchSemanticsNode().boundsInRoot.top
        scenario!!.recreate()
        waitFor(row)
        assertEquals(before, compose.onNode(row).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun closingAndAddThenClosingSaveOneNoteEachAndShowIndicator() {
        launch()
        typeDraft("Closed without Add")
        closeSheet()
        awaitClosed()
        assertEquals(listOf("Closed without Add"), notes().map { it.text })
        compose.onNodeWithContentDescription(str(R.string.task_has_notes), useUnmergedTree = true).assertExists()
        reopen()
        assertComposerClosed()
        typeDraft("Add then close")
        compose.onNodeWithText(str(R.string.add_category_yes_dialog_button)).performClick()
        closeSheet()
        awaitClosed()
        assertEquals(listOf("Closed without Add", "Add then close"), notes().map { it.text })
        assertTrue(notes().all { it.editedAt == null })
        reopen()
        assertComposerClosed()
    }

    @Test fun addReturnsToTheAddANoteButton() {
        launch()
        compose.onNodeWithTag("add-note").assertContentDescriptionEquals(str(R.string.add_note))
        typeDraft("First")
        compose.onNodeWithText(str(R.string.add_category_yes_dialog_button)).performClick()
        waitFor(hasTestTag("add-note"))
        assertComposerClosed()
        assertEquals(listOf("First"), notes().map { it.text })
    }

    @Test fun titleAndExistingNoteFlushBeforeDebounceWhenClosed() {
        launch(notes = 1)
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("task-title-row")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("task-title-editor").performTextReplacement("Changed title")
        compose.onNodeWithTag("add-note").performScrollTo().performClick()
        compose.onAllNodes(hasTestTag("task-title-editor")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithContentDescription(str(R.string.edit_note)).performScrollTo().performClick()
        compose.onNodeWithTag("note-editor-1").performTextReplacement("Changed note\nSecond line")
        closeSheet()
        awaitClosed()
        assertEquals("Changed title", runBlocking { reset.db().taskDao().taskById(1)!!.text })
        assertEquals("Changed note\nSecond line", notes().single().text)
        assertNotNull(notes().single().editedAt)
        reopen("Changed title")
        compose.onNodeWithText("Changed note\nSecond line").assertExists()
    }

    @Test fun rotationRetainsDraftAndActiveExistingNoteWithoutSubmittingOrMarkingEdited() {
        launch(notes = 1)
        typeDraft("Rotating draft")
        scenario!!.recreate()
        waitFor(hasTestTag("note-composer"))
        compose.onNodeWithTag("note-composer").assertEditableText("Rotating draft")
        assertEquals(1, notes().size)
        compose.onNodeWithTag("note-composer").performTextClearance()
        compose.onNodeWithContentDescription(str(R.string.edit_note)).performScrollTo().performClick()
        scenario!!.recreate()
        waitFor(hasTestTag("note-editor-1"))
        compose.onNodeWithTag("note-editor-1").assertExists()
        closeSheet()
        awaitClosed()
        assertEquals(1, notes().size)
        assertNull(notes().single().editedAt)
    }

    @Test fun backgroundSubmitsDraftAndShowsAddANoteOnReturn() {
        launch()
        typeDraft("Background draft")
        scenario!!.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { notes().size == 1 }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor(hasTestTag("add-note"))
        assertComposerClosed()
        assertEquals("Background draft", notes().single().text)
        assertNull(notes().single().editedAt)
    }

    @Test fun failedCloseRetainsTextAndRetryClosesWithoutDuplicates() {
        launch()
        reset.seed {
            openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        typeDraft("Retain after failure")
        closeSheet()
        waitFor(hasText(str(R.string.task_save_failed)))
        compose.onNodeWithTag("note-composer").assertEditableText("Retain after failure")
        assertTrue(notes().isEmpty())
        reset.seed { openHelper.writableDatabase.execSQL("DROP TRIGGER fail_note") }
        compose.onNodeWithText(str(R.string.task_save_retry)).performScrollTo().performClick()
        awaitClosed()
        assertEquals("Retain after failure", notes().single().text)
    }

    @Test fun previewsExpansionAndInputLimitsKeepTextAndActionsAccessible() {
        val count = InstrumentationRegistry.getArguments().getString("previewNotes")?.toInt() ?: 8
        launch(notes = count)
        compose.onAllNodes(hasText(str(R.string.note_expand))).fetchSemanticsNodes().let { assertEquals(5, it.size) }
        compose.onNode(hasText(str(R.string.note_expand)) and hasAnyAncestor(hasTestTag("note-$count")))
            .performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.note_collapse)).assertExists().performClick()
        compose.onNodeWithText(str(R.string.notes_show_all, count)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.notes_show_fewer)).performScrollTo().performClick()
        typeDraft("x".repeat(2_000))
        compose.onNodeWithTag("note-composer").performTextInput("overflow")
        compose.onNodeWithTag("note-composer").assertEditableText("x".repeat(2_000))
        compose.onNodeWithText(str(R.string.note_limit_reached)).assertExists()
        compose.onNodeWithTag("note-composer").performTextReplacement("Long draft\nhttps://example.com\nThird line\nFourth line\nFifth line\nSixth line")
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
            compose.onNodeWithTag("note-composer").performScrollTo()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "task-notes.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("task-title-row")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("task-title-editor").performTextReplacement("t".repeat(100))
        compose.onNodeWithTag("task-title-editor").performTextInput("overflow")
        compose.onNodeWithTag("task-title-editor").assertEditableText("t".repeat(100))
        compose.onNodeWithText(str(R.string.title_limit_reached)).assertExists()
        compose.onNodeWithTag("task-title-editor").performTextReplacement("Notes test")
        closeSheet()
        awaitClosed()
        assertEquals(count + 1, notes().size)
    }
}
