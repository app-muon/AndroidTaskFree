package com.taskfree.app.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.taskfree.app.ui.task.TaskNoteReveal
import com.taskfree.app.ui.task.TaskSheetContainer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskSheetContainerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun taskSheetHasOneControlBlockAndOnlyOutsideTapsDismissIt() {
        var open by mutableStateOf(true)
        val reveal = TaskNoteReveal(false)
        compose.setContent {
            MaterialTheme {
                if (open) TaskSheetContainer(reveal,
                    headerContent = { Text("Sheet heading") },
                    bodyContent = { Text("Sheet body") },
                    controlsContent = { Text("Only controls", Modifier.height(110.dp)) },
                    additionalContent = {}, onDismiss = { open = false })
            }
        }
        assertEquals(1, compose.onAllNodesWithText("Only controls").fetchSemanticsNodes().size)
        compose.onNodeWithText("Sheet heading").performTouchInput { click() }
        compose.onNodeWithText("Sheet body").assertIsDisplayed()
        val surface = compose.onNodeWithTag("task-sheet-surface").fetchSemanticsNode().boundsInRoot
        val outside = compose.onNodeWithTag("task-sheet-outside").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("task-sheet-outside").performTouchInput {
            click(Offset(centerX, surface.top - outside.top - 8f))
        }
        compose.onNodeWithText("Sheet body").assertDoesNotExist()
    }

    @Test fun editingCancelsARevealBeforeTheTargetArrives() {
        val reveal = TaskNoteReveal(true)
        var targetLoaded by mutableStateOf(false)
        var draft by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                TaskSheetContainer(reveal,
                    headerContent = { Text("Sheet heading") },
                    controlsContent = { Text("Controls", Modifier.height(110.dp)) },
                    additionalContent = {}, onDismiss = {},
                    bodyContent = {
                        Column {
                            BasicTextField(draft, { draft = it }, Modifier.testTag("early-editor"))
                            Spacer(Modifier.height(1200.dp))
                            if (targetLoaded) Text("Late matching note",
                                Modifier.onGloballyPositioned { reveal.coordinates = it },
                                onTextLayout = { reveal.localBounds = Rect(0f, 0f, it.size.width.toFloat(), it.size.height.toFloat()) })
                        }
                    })
            }
        }
        compose.onNodeWithTag("early-editor").performTouchInput { click() }
        compose.onNodeWithTag("early-editor").performTextInput("Draft in progress")
        compose.runOnIdle { targetLoaded = true }
        compose.onNodeWithText("Late matching note").assertIsNotDisplayed()
        val view = (compose.onNodeWithTag("early-editor").fetchSemanticsNode().root as ViewRootForTest).view
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnUiThread {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
        compose.waitUntil(10_000) {
            !compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.onNodeWithText("Late matching note").assertIsNotDisplayed()
        compose.onNodeWithTag("early-editor").assertIsDisplayed()
    }
}
