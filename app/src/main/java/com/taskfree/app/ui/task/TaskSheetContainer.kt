package com.taskfree.app.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R
import com.taskfree.app.ui.components.dialogResponsiveWidth
import com.taskfree.app.ui.components.thinVerticalScrollbar
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

const val PANEL_PINNED_ACTIONS_TAG = "panelPinnedActions"
const val PANEL_SCROLLING_ACTIONS_TAG = "panelScrollingActions"
internal const val TASK_SHEET_VIEWPORT_TAG = "task-sheet-viewport"
internal val taskSheetFadeHeight = 56.dp

/** The saved flag prevents recreation from repeating an attempted or cancelled reveal. */
internal class TaskNoteReveal(pending: Boolean) {
    var handled by mutableStateOf(!pending)
        private set
    var pending by mutableStateOf(pending)
        private set
    var localBounds by mutableStateOf<Rect?>(null)
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)

    fun started() { handled = true }
    fun cancel() { handled = true; pending = false }
    fun boundsInRoot(): Rect? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = localBounds ?: return null
        return Rect(coords.localToRoot(bounds.topLeft), coords.localToRoot(bounds.bottomRight))
    }

    companion object {
        val Saver = Saver<TaskNoteReveal, Boolean>(save = { it.handled }, restore = { TaskNoteReveal(!it) })
    }
}

@Composable
internal fun rememberTaskNoteReveal(target: NoteSearchTarget?): TaskNoteReveal =
    rememberSaveable(target, saver = TaskNoteReveal.Saver) { TaskNoteReveal(target != null) }

/** Task-only layout. Window constraints, controls and note reveals share the same viewport. */
@Composable
internal fun TaskSheetContainer(
    reveal: TaskNoteReveal,
    headerContent: @Composable () -> Unit,
    bodyContent: @Composable () -> Unit,
    controlsContent: @Composable () -> Unit,
    additionalContent: @Composable () -> Unit,
    onDismiss: () -> Unit
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf<Rect?>(null) }
    var controlsMeasured by remember { mutableStateOf(false) }
    val background = colorResource(R.color.dialog_background_colour)

    // Observe placement, not a fixed number of frames. Re-centre on viewport changes during
    // the initial keyboard transition, until the user takes control. Scroll motion itself
    // does not restart this collector.
    LaunchedEffect(reveal, scroll) {
        snapshotFlow {
            if (reveal.pending && controlsMeasured && reveal.coordinates != null && reveal.localBounds != null)
                viewport else null
        }.collectLatest { area ->
            val target = reveal.boundsInRoot()
            if (area != null && target != null && area.height > 0) {
                reveal.started()
                val offset = (scroll.value + target.center.y - area.center.y).roundToInt()
                scroll.animateScrollTo(offset.coerceIn(0, scroll.maxValue))
            }
        }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val ime = WindowInsets.ime
        val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
        // Insets are outside the visible surface. Taps in this transparent area dismiss it.
        BoxWithConstraints(Modifier.fillMaxSize().testTag("task-sheet-outside")
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            val maxSheetHeight = maxHeight * 0.92f
            val availableHeight = with(density) { maxSheetHeight.roundToPx() }
            var controlsHeight by remember(maxWidth, density) { mutableIntStateOf(0) }
            var closeHeight by remember(maxWidth, density) { mutableIntStateOf(0) }
            var lastClosedPin by remember { mutableStateOf<Boolean?>(null) }
            val measured = controlsHeight > 0 && closeHeight > 0
            val fits = measured && availableHeight - controlsHeight - closeHeight >=
                with(density) { (240.dp.toPx() * fontScale.coerceAtLeast(1f)).roundToInt() }
            val pin = if (keyboardVisible) lastClosedPin ?: fits else fits
            SideEffect {
                controlsMeasured = measured
                if (!keyboardVisible && measured) lastClosedPin = fits
            }
            val controls: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .onSizeChanged { controlsHeight = it.height }
                    .testTag(if (pin) PANEL_PINNED_ACTIONS_TAG else PANEL_SCROLLING_ACTIONS_TAG)) {
                    controlsContent()
                }
            }
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.large, color = background,
                    modifier = Modifier.dialogResponsiveWidth().heightIn(max = maxSheetHeight)
                        .testTag("task-sheet-surface")
                        .pointerInput(reveal) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                reveal.cancel()
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures { /* Keep inside taps inside. */ } }) {
                    Column {
                        Box(Modifier.weight(1f, fill = false)
                            .testTag(TASK_SHEET_VIEWPORT_TAG)
                            .onGloballyPositioned { viewport = it.boundsInRoot() }
                            .drawWithContent {
                                drawContent()
                                val fade = minOf(taskSheetFadeHeight.toPx(), size.height / 4)
                                if (scroll.canScrollBackward) drawRect(
                                    Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)), endY = fade),
                                    size = size.copy(height = fade))
                                if (scroll.canScrollForward) drawRect(
                                    Brush.verticalGradient(listOf(background.copy(alpha = 0f), background),
                                        startY = size.height - fade, endY = size.height),
                                    topLeft = Offset(0f, size.height - fade), size = size.copy(height = fade))
                            }) {
                            Column(Modifier.thinVerticalScrollbar(scroll).verticalScroll(scroll)
                                .padding(vertical = taskSheetFadeHeight)) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { headerContent() }
                                if (!pin) controls()
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { bodyContent() }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { additionalContent() }
                            }
                        }
                        if (pin) {
                            // Keep one measured copy while hidden; it never enters the scrolling content.
                            Box(Modifier.layout { measurable, constraints ->
                                val child = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                layout(child.width, if (keyboardVisible) 0 else child.height) {
                                    if (!keyboardVisible) child.placeRelative(0, 0)
                                }
                            }) { controls() }
                        }
                        Text(stringResource(R.string.close_yes_dialog_button),
                            modifier = Modifier.fillMaxWidth().onSizeChanged { closeHeight = it.height }
                                .clickable(onClick = onDismiss).padding(16.dp),
                            color = colorResource(R.color.dialog_button_text_colour),
                            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
