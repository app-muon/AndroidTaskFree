package com.taskfree.app.ui.task.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.components.ArchivePill
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DragHandle
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.fromTask
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.recurrenceLabel
import com.taskfree.app.ui.theme.RowTransparency
import com.taskfree.app.util.AppDateProvider
import isNotificationPassed
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableCollectionItemScope

/* ====== dims & helpers ====== */
private object TaskRowDimens {
    val contentPaddingStart = 12.dp
    val contentPaddingEnd = 4.dp
    val contentPaddingTop = 6.dp
    val contentPaddingBottom = 6.dp
    val titleStartIndent = 12.dp
    val handleGap = 8.dp                 // space between meta block and handle
    val tierSpacing = 2.dp               // vertical space between meta-only lines
    val textMetaGap = 8.dp               // REQUIRED minimum gap text → first meta
    val metaGap = 4.dp                   // gap between meta items
    val stripeWidth = 4.dp
    val bellIconSize = 16.dp
    val minTouchTarget = 24.dp
}

private data class PackResultI(
    val inlineIndices: List<Int>,
    val carryLines: List<List<Int>>,
    val inlineWidthPx: Int,            // includes leading TextMetaGap (for fit calc)
    val inlineContentWidthPx: Int,     // excludes leading gap (for end-align)
    val inlineHeightPx: Int,
    val carryLineHeightsPx: List<Int>,
    val carryTotalHeightPx: Int
)

@Composable
private fun MetaBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.sizeIn(minHeight = TaskRowDimens.bellIconSize),
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun calculateLineWidthPx(
    itemIndices: List<Int>, itemWidthsPx: IntArray, gapPx: Int
): Int = itemIndices.fold(0) { acc, idx ->
    if (acc == 0) itemWidthsPx[idx] else acc + gapPx + itemWidthsPx[idx]
}

/** Integer-based packing to avoid cumulative rounding drift */
private fun packMetaInt(
    remainingOnLastLinePx: Int,
    slotWidthPx: Int,
    widthsPx: IntArray,
    heightsPx: IntArray,
    textMetaGapPx: Int,
    metaGapPx: Int
): PackResultI {
    val count = widthsPx.size

    // Inline pack on last text line
    var inlineCount = 0
    var used = 0
    if (count > 0 && remainingOnLastLinePx > 0) {
        for (i in 0 until count) {
            val w = widthsPx[i]
            val add = if (inlineCount == 0) (textMetaGapPx + w) else (metaGapPx + w)
            if (used + add <= remainingOnLastLinePx) {
                used += add
                inlineCount++
            } else break
        }
    }
    val inline = if (inlineCount > 0) (0 until inlineCount).toList() else emptyList()
    val carryIdx = if (inlineCount < count) (inlineCount until count).toList() else emptyList()

    // Wrap carry meta across new lines, end-aligned
    val carryLines = mutableListOf<MutableList<Int>>()
    if (carryIdx.isNotEmpty()) {
        var current = mutableListOf<Int>()
        var widthAcc = 0
        carryIdx.forEach { idx ->
            val w = widthsPx[idx]
            val add = if (current.isEmpty()) w else (metaGapPx + w)
            if (widthAcc + add <= slotWidthPx) { current.add(idx); widthAcc += add }
            else {
                if (current.isNotEmpty()) carryLines.add(current)
                current = mutableListOf(idx)
                widthAcc = w
            }
        }
        if (current.isNotEmpty()) carryLines.add(current)
    }

    val inlineContentWidthPx = if (inline.isEmpty()) 0
    else calculateLineWidthPx(inline, widthsPx, metaGapPx)

    val inlineH = inline.maxOfOrNull { heightsPx[it] } ?: 0
    val carryHs = carryLines.map { line -> line.maxOf { heightsPx[it] } }

    return PackResultI(
        inlineIndices = inline,
        carryLines = carryLines,
        inlineWidthPx = used,                    // with leading gap
        inlineContentWidthPx = inlineContentWidthPx, // without leading gap
        inlineHeightPx = inlineH,
        carryLineHeightsPx = carryHs,
        carryTotalHeightPx = carryHs.sum()
    )
}

/** Placement helper (horizontal ints, vertical centered with floats) */
private fun Placeable.PlacementScope.placeLineEndAlignedInt(
    placeables: List<Placeable>,
    indices: List<Int>,
    startX: Int,
    yTopF: Float,
    lineHeightPx: Int,
    gapPx: Int
) {
    var x = startX
    indices.forEachIndexed { j, idx ->
        val p = placeables[idx]
        val y = (yTopF + (lineHeightPx - p.height) / 2f).roundToInt()
        p.placeRelative(x, y)
        x += p.width + if (j < indices.lastIndex) gapPx else 0
    }
}

/* ====== main composable ====== */
@Composable
fun ReorderableCollectionItemScope.TaskRow(
    task: Task,
    isDragging: Boolean = false,
    showHandle: Boolean = true,
    showCategory: Boolean = false,
    category: Category,
    onClick: () -> Unit = {}
) {
    val dp = AppDateProvider.current
    val isOverdue = task.due?.let { it < dp.today() && task.completedDate == null } == true

    val elevation = if (isDragging) 4.dp else 0.dp
    val containerColor = task.status.backgroundColor().copy(alpha = RowTransparency)
    val surfaceColor = colorResource(R.color.surface_colour)

    val textColor = if (task.status == TaskStatus.DONE) Color.Gray else surfaceColor
    val textDecoration = if (task.status == TaskStatus.DONE) TextDecoration.LineThrough else null
    val dueLabel = recurrenceLabel(DueChoice.fromTask(task), task.recurrence)
    val isNotificationPassed = task.isNotificationPassed()
    val overdueStripeColor = Color.Red.copy(alpha = 0.3f)
    val titleStyle: TextStyle = MaterialTheme.typography.bodyMedium

    // A11y: single node description
    val a11yDesc = buildString {
        append(task.text)
        if (dueLabel.isNotBlank()) append(", ").append(dueLabel)
        if (task.isArchived) append(", ").append("Archived")
        if (showCategory) append(", ").append("Category ").append(category.title)
        if (task.reminderTime != null) append(", ").append("Reminder set")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = a11yDesc }
            .drawBehind {
                if (isOverdue) {
                    val stripeW = TaskRowDimens.stripeWidth.toPx()
                    drawRect(color = overdueStripeColor, size = Size(stripeW, size.height))
                }
            },
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = TaskRowDimens.contentPaddingStart,
                    top = TaskRowDimens.contentPaddingTop,
                    end = TaskRowDimens.contentPaddingEnd,
                    bottom = TaskRowDimens.contentPaddingBottom
                )
        ) {
            val density = LocalDensity.current
            SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                val layoutDir = this.layoutDirection
                val maxW = constraints.maxWidth

                // ints for horizontal planning
                val titleStartPx = with(density) { TaskRowDimens.titleStartIndent.toPx().roundToInt() }
                val handleGapPx = with(density) { TaskRowDimens.handleGap.toPx().roundToInt() }
                val tierSpacingPx = with(density) { TaskRowDimens.tierSpacing.toPx().roundToInt() }
                val textMetaGapPx = with(density) { TaskRowDimens.textMetaGap.toPx().roundToInt() }
                val metaGapPx = with(density) { TaskRowDimens.metaGap.toPx().roundToInt() }
                val minTouchPx = with(density) { TaskRowDimens.minTouchTarget.toPx().roundToInt() }

                val contentLeftPx = titleStartPx

                /* ---- 1) HANDLE ---- */
                val handlePlaceable = subcompose("Handle") {
                    Box(
                        Modifier
                            .sizeIn(
                                minWidth = TaskRowDimens.minTouchTarget,
                                minHeight = TaskRowDimens.minTouchTarget
                            )
                            .let { if (!showHandle) it.alpha(0f) else it }
                    ) {
                        // Not using draggableHandle()
                        DragHandle(show = true, modifier = Modifier.draggableHandle())
                    }
                }.first().measure(
                    Constraints(
                        minWidth = minTouchPx,
                        minHeight = minTouchPx
                    )
                )
                val handleW = handlePlaceable.width
                val handleH = handlePlaceable.height
                val contentRightPx = maxW - handleW - handleGapPx
                val maxTextWidthPx = (contentRightPx - contentLeftPx).coerceAtLeast(0)

                /* ---- 2) TITLE ---- */
                var titleLayout: TextLayoutResult? = null
                val titlePlaceable = subcompose("Title") {
                    Text(
                        text = task.text,
                        style = titleStyle,
                        color = textColor,
                        textDecoration = textDecoration,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { titleLayout = it }
                    )
                }.first().measure(Constraints(maxWidth = maxTextWidthPx))
                val titleResult = titleLayout ?: return@SubcomposeLayout layout(maxW, 0) {}

                val titleHeightPx = titlePlaceable.height
                val lineCount = titleResult.lineCount
                val lastLine = (lineCount - 1).coerceAtLeast(0)

                // use rounded right edge to align with integer packing; switch to ceil() if needed
                val lastRightPx = titleResult.getLineRight(lastLine).roundToInt()
                val remainingOnLastLinePx = (maxTextWidthPx - lastRightPx).coerceAtLeast(0)

                /* ---- 3) META (declarative list; no remember in measure) ---- */
                val iconTint = when {
                    task.status == TaskStatus.DONE -> Color.Gray
                    isNotificationPassed -> textColor.copy(alpha = 0.2f)
                    else -> textColor
                }
                val metaItems = buildList<@Composable () -> Unit> {
                    if (task.reminderTime != null) add {
                        MetaBox {
                            Icon(
                                painter = painterResource(R.drawable.ic_notification),
                                contentDescription = stringResource(R.string.notification_heading),
                                tint = iconTint,
                                modifier = Modifier.size(TaskRowDimens.bellIconSize)
                            )
                        }
                    }
                    if (task.isArchived) add { MetaBox { ArchivePill(big = false) } }
                    if (showCategory) add { MetaBox { CategoryPill(category = category, big = false, selected = true) } }
                    if (dueLabel.isNotBlank()) add {
                        MetaBox {
                            Text(
                                text = dueLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (task.status == TaskStatus.DONE) Color.Gray else textColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                softWrap = true,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }

                val metaSlotWidthPx = contentRightPx
                val metaPlaceables: List<Placeable> = subcompose("MetaItems") {
                    metaItems.forEachIndexed { i, item -> key(i) { item() } }
                }.map { it.measure(Constraints(maxWidth = metaSlotWidthPx)) }
                val metaWidthsPx = IntArray(metaPlaceables.size) { metaPlaceables[it].width }
                val metaHeightsPx = IntArray(metaPlaceables.size) { metaPlaceables[it].height }

                /* ---- 4) PACK (ints) ---- */
                val pack = packMetaInt(
                    remainingOnLastLinePx = remainingOnLastLinePx,
                    slotWidthPx = metaSlotWidthPx,
                    widthsPx = metaWidthsPx,
                    heightsPx = metaHeightsPx,
                    textMetaGapPx = textMetaGapPx,
                    metaGapPx = metaGapPx
                )

                /* ---- 5) HEIGHTS ---- */
                val topRowHeightPx = maxOf(titleHeightPx, pack.inlineHeightPx, minTouchPx)
                val carryBlockHeightPx =
                    if (pack.carryLines.isEmpty()) 0
                    else pack.carryTotalHeightPx + tierSpacingPx * (pack.carryLines.size - 1)
                val totalHeightPx =
                    if (pack.carryLines.isEmpty()) topRowHeightPx
                    else topRowHeightPx + tierSpacingPx + carryBlockHeightPx

                /* ---- 6) PLACE ---- */
                layout(width = maxW, height = totalHeightPx) {
                    // Title (horizontal ints; vertical center as float)
                    val textYF = (topRowHeightPx - titleHeightPx) / 2f
                    val textX = when (layoutDir) {
                        LayoutDirection.Ltr -> contentLeftPx
                        LayoutDirection.Rtl -> (contentRightPx - titlePlaceable.width)
                    }
                    titlePlaceable.placeRelative(textX, textYF.roundToInt())

                    // Inline meta on last text line (end-aligned + gap rule, all ints)
                    if (pack.inlineIndices.isNotEmpty()) {
                        val lastLineTop = titleResult.getLineTop(lastLine)
                        val lastLineBottom = titleResult.getLineBottom(lastLine)
                        val lastLineHeightPx = (lastLineBottom - lastLineTop).coerceAtLeast(0f)
                        val inlineTopF = textYF + lastLineTop +
                                (lastLineHeightPx - pack.inlineHeightPx) / 2f

                        val endAlignedStart = when (layoutDir) {
                            LayoutDirection.Ltr -> contentRightPx - pack.inlineContentWidthPx
                            LayoutDirection.Rtl -> contentLeftPx
                        }
                        val gapEnforcedStart = when (layoutDir) {
                            LayoutDirection.Ltr -> contentLeftPx + lastRightPx + textMetaGapPx
                            LayoutDirection.Rtl -> contentRightPx - lastRightPx - textMetaGapPx - pack.inlineWidthPx
                        }
                        val startX = maxOf(endAlignedStart, gapEnforcedStart)

                        placeLineEndAlignedInt(
                            placeables = metaPlaceables,
                            indices = pack.inlineIndices,
                            startX = startX,
                            yTopF = inlineTopF,
                            lineHeightPx = pack.inlineHeightPx,
                            gapPx = metaGapPx
                        )
                    }

                    // Carry meta lines below (end-aligned, all ints)
                    if (pack.carryLines.isNotEmpty()) {
                        var yF = topRowHeightPx + tierSpacingPx.toFloat()
                        pack.carryLines.forEachIndexed { li, line ->
                            val lineH = pack.carryLineHeightsPx[li]
                            val lineW = calculateLineWidthPx(line, metaWidthsPx, metaGapPx)
                                .coerceAtMost(metaSlotWidthPx)
                            val startX = when (layoutDir) {
                                LayoutDirection.Ltr -> contentRightPx - lineW
                                LayoutDirection.Rtl -> contentLeftPx
                            }
                            placeLineEndAlignedInt(
                                placeables = metaPlaceables,
                                indices = line,
                                startX = startX,
                                yTopF = yF,
                                lineHeightPx = lineH,
                                gapPx = metaGapPx
                            )
                            yF += lineH + tierSpacingPx
                        }
                    }

                    // Handle at PHYSICAL RIGHT, vertically centered
                    val hx = maxW - handleW
                    val hy = (totalHeightPx - handleH) / 2
                    handlePlaceable.placeRelative(hx, hy)
                }
            }
        }
    }
}
