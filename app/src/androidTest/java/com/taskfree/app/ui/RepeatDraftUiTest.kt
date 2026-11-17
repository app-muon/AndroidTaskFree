package com.taskfree.app.ui

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import com.taskfree.app.ResetAppStateRule
import org.junit.rules.RuleChain
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.ui.theme.scaled
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.task.components.NewTaskDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RepeatDraftUiTest {
    private val reset = ResetAppStateRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun resetClock() = com.taskfree.app.util.AppDateProvider.reset()

    private fun repeatButton(resource: Int) = compose.onNode(
        hasText(context.getString(resource)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("repeat_dialog")))

    @Test fun automaticDateIsUndoneButExplicitTodaySurvivesReturningToNoRepetition() {
        var savedDue: java.time.LocalDate? = java.time.LocalDate.MIN
        compose.setContent {
            MaterialTheme {
                NewTaskDialog("Undated", initialCategory = Category(1, "Home", 0),
                    allCategories = listOf(Category(1, "Home", 0)),
                    onSave = { _, due, _, _, _ -> savedDue = due.date }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        repeatButton(R.string.repeat_days).performClick()
        repeatButton(R.string.repeat_none).performClick()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.save_yes_dialog_button)).performScrollTo().performClick()
        assertNull(savedDue)
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        repeatButton(R.string.repeat_days).performClick()
        repeatButton(R.string.today).performScrollTo().performClick()
        repeatButton(R.string.repeat_none).performScrollTo().performClick()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.save_yes_dialog_button)).performScrollTo().performClick()
        assertEquals(java.time.LocalDate.now(), savedDue)
    }

    @Test fun repeatDateSelectsTodayChipAndRemainsFixedAcrossMidnightAndRestoration() {
        val day = java.time.LocalDate.of(2026, 11, 16)
        fun setDay(date: java.time.LocalDate) {
            com.taskfree.app.util.AppDateProvider.setForTesting(com.taskfree.app.util.DateProvider(
                java.time.Clock.fixed(date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC)))
        }
        setDay(day)
        val restoration = StateRestorationTester(compose)
        var savedDue: java.time.LocalDate? = null
        restoration.setContent {
            MaterialTheme {
                NewTaskDialog("Date draft", initialCategory = Category(1, "Home", 0),
                    allCategories = listOf(Category(1, "Home", 0)),
                    onSave = { _, due, _, _, _ -> savedDue = due.date }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        repeatButton(R.string.repeat_days).performClick()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        compose.onNodeWithTag("due_option_TODAY").assertIsSelected()
        compose.onNodeWithTag("due_option_OTHER").assertIsNotSelected()
        setDay(day.plusDays(1))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("due_option_TODAY").assertIsNotSelected()
        compose.onNodeWithTag("due_option_OTHER").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.save_yes_dialog_button)).performScrollTo().performClick()
        assertEquals(day, savedDue)
    }

    @Test fun legacyPreviewUsesPreservedBaseAndShowsSteppedInterval() {
        val today = java.time.LocalDate.now()
        compose.setContent {
            MaterialTheme {
                com.taskfree.app.ui.task.components.RepeatDialog(
                    RepeatRule(com.taskfree.app.domain.model.Recurrence.DAILY), today,
                    initialBaseDate = today.minusDays(1), onDone = { _, _ -> }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("repeat_increment").performClick()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("2")
        compose.onNodeWithTag("repeat_interval_unit").assertTextEquals("days")
        val expected = today.plusDays(1).format(java.time.format.DateTimeFormatter
            .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(context.resources.configuration.locales[0]))
        compose.onNodeWithTag("repeat_preview_date").assertTextContains(expected, substring = true)
    }

    @Test fun weekdayErrorUsesConfigurationLocaleAndIncludesDayName() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale("es")) }
        val spanish = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides spanish, LocalResources provides spanish.resources,
                LocalConfiguration provides config) {
                MaterialTheme {
                    com.taskfree.app.ui.task.components.ValidationRecurrenceErrorText(
                        com.taskfree.app.domain.model.RecurrenceValidationResult.NotWeekday(java.time.LocalDate.of(2026, 11, 14)))
                }
            }
        }
        compose.onNode(hasText("sábado", substring = true)).assertExists()
    }

    @Test fun creationDraftAndNestedRepeatSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var savedRule: RepeatRule? = null
        var savedTitle = ""
        var savedDue: java.time.LocalDate? = null
        restoration.setContent {
            MaterialTheme {
                NewTaskDialog("", DueChoice.None, initialCategory = Category(1, "Home", 0),
                    allCategories = listOf(Category(1, "Home", 0)),
                    onSave = { text, due, rule, _, _ -> savedTitle = text; savedDue = due.date; savedRule = rule },
                    onDismiss = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Creation draft")
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.repeat_months)).performClick()
        compose.onNodeWithTag("repeat_increment").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("repeat_done").assertIsEnabled()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("2")
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        assertNull(savedRule)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("Creation draft")
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        compose.onNodeWithTag("repeat_interval").assertTextContains("2")
        compose.onNodeWithTag("repeat_increment").performClick()
        compose.onNode(hasText(context.getString(R.string.cancel_no_dialog_button)) and
            hasAnyAncestor(hasTestTag("repeat_dialog"))).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.save_yes_dialog_button)).performScrollTo().performClick()
        assertEquals("Creation draft", savedTitle)
        assertEquals(2, savedRule!!.interval)
        assertNotNull(savedDue)
    }

    @Test fun spanishLargeTextShowsFullLabelsAndAccessibleActions() {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
            fontScale = 2f
        }
        val spanish = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides spanish, LocalResources provides spanish.resources, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(typography = MaterialTheme.typography.scaled(1.6f)) {
                    com.taskfree.app.ui.task.components.RepeatDialog(
                        RepeatRule(com.taskfree.app.domain.model.Recurrence.MONTHLY, 99),
                        java.time.LocalDate.now(), onDone = { _, _ -> }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("Meses").assertExists()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("99")
        compose.onNodeWithTag("repeat_interval_unit").assertTextEquals("meses")
        compose.onNodeWithTag("repeat_increment").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_decrement").assertHasClickAction()
        compose.onNodeWithTag("repeat_due").assertDoesNotExist()
        compose.onNodeWithTag("repeat_done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Listo").assertHasClickAction()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "repeat-spanish-large.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun existingEditorRestoresIdentityAndReloadsCurrentTaskWithoutLosingRepeatDraft() {
        val db = reset.db()
        runBlocking {
            db.categoryDao().insertAll(listOf(Category(1, "Home", 0)))
            db.taskDao().insert(com.taskfree.app.data.entities.Task(1, 1, "Original title",
                due = java.time.LocalDate.now(), singleCategoryPageOrder = 0))
        }
        val repo = com.taskfree.app.data.repository.TaskRepository(db)
        val restoration = StateRestorationTester(compose)
        var initiallyOpened = false
        restoration.setContent {
            val vm = remember { com.taskfree.app.ui.task.TaskViewModel(context, repo) }
            DisposableEffect(vm) { onDispose { vm.viewModelScope.cancel() } }
            LaunchedEffect(vm) {
                if (!initiallyOpened) {
                    initiallyOpened = true
                    vm.openTaskOptions(1)
                }
            }
            val dialogs by vm.dialogs.collectAsState()
            MaterialTheme {
                com.taskfree.app.ui.task.components.TaskDialogHost(dialogs, vm::setDialog,
                    listOf(Category(1, "Home", 0)), vm, null, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("open_repeat")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("repeat_dialog")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.repeat_weeks)).performClick()
        repeat(2) { compose.onNodeWithTag("repeat_increment").performClick() }
        runBlocking { repo.updateTitle(1, "Reloaded title") }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("repeat_interval")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("repeat_interval").assertTextContains("3")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Reloaded title", substring = true) and
                hasAnyAncestor(hasTestTag("repeat_dialog"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Reloaded title", substring = true) and hasAnyAncestor(hasTestTag("repeat_dialog"))).assertExists()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.taskById(1)!!.recurrenceInterval == 3 } }
        assertEquals("Reloaded title", runBlocking { repo.taskById(1)!!.text })
    }

}
