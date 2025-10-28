package com.taskfree.app.ui.task.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import sh.calvin.reorderable.ReorderableCollectionItemScope

/* ====== constants ====== */
private val ContentPaddingStart = 12.dp
private val ContentPaddingEnd = 4.dp
private val ContentPaddingTop = 6.dp
private val ContentPaddingBottom = 6.dp
private val TitleStartIndent = 12.dp
private val HandleGap = 8.dp                 // space between meta block and handle
private val TierSpacing = 2.dp               // vertical space between meta-only lines
private val TextMetaGap = 8.dp               // REQUIRED minimum gap text → first meta
private val MetaGap = 4.dp                   // gap between meta items
private val StripeWidth = 4.dp
private val BellIconSize = 16.dp
private val MinTouchTarget = 24.dp

@Composable
private fun MetaBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.sizeIn(minHeight = BellIconSize),
        contentAlignment = Alignment.Center
    ) { content() }
}

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Overdue stripe anchored to PHYSICAL LEFT regardless of locale
            if (isOverdue) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier
                            .width(StripeWidth)
                            .fillMaxHeight()
                            .background(
                                overdueStripeColor,
                                RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                            )
                            .align(Alignment.CenterStart)
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ContentPaddingStart,
                        top = ContentPaddingTop,
                        end = ContentPaddingEnd,
                        bottom = ContentPaddingBottom
                    )
            ) {
                val density = LocalDensity.current
                SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                    val layoutDir = this.layoutDirection
                    val maxW = constraints.maxWidth

                    val titleStartPx = with(density) { TitleStartIndent.toPx().toInt() }
                    val handleGapPx = with(density) { HandleGap.toPx().toInt() }
                    val tierSpacingPx = with(density) { TierSpacing.toPx().toInt() }
                    val textMetaGapPx = with(density) { TextMetaGap.toPx().toInt() }
                    val metaGapPx = with(density) { MetaGap.toPx().toInt() }
                    val minTouchPx = with(density) { MinTouchTarget.toPx().toInt() }

                    val contentLeftPx = titleStartPx

                    /* ---- 1) HANDLE: subcompose ONCE and measure with a single constraint policy ---- */
                    val handlePlaceable = subcompose("Handle") {
                        // Keep size target consistent; if hidden, draw transparent but same layout size.
                        Box(
                            Modifier
                                .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                                .let { if (!showHandle) it.alpha(0f) else it }
                        ) {
                            DragHandle(show = true,
                                modifier = Modifier.draggableHandle())
                        }
                    }.first().measure(
                        Constraints(
                            minWidth = minTouchPx,
                            minHeight = minTouchPx
                        )
                    )
                    val handleW = handlePlaceable.width
                    val handleH = handlePlaceable.height
                    val reservedHandleSpace = handleW + handleGapPx

                    /* ---- 2) TITLE: compose once, capture TextLayoutResult non-null ---- */
                    var titleLayout: TextLayoutResult? = null
                    val contentRightPx = maxW - reservedHandleSpace
                    val maxTextWidth = (contentRightPx - contentLeftPx).coerceAtLeast(0)
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
                    }.first().measure(Constraints(maxWidth = maxTextWidth))

                    val titleResult = titleLayout ?: return@SubcomposeLayout layout(maxW, 0) {}
                    val titleHeight = titlePlaceable.height
                    val lineCount = titleResult.lineCount

                    val lastLine = (lineCount - 1).coerceAtLeast(0)
                    val lastRight = titleResult.getLineRight(lastLine).toInt()
                    val lastBaseline = titleResult.getLineBaseline(lastLine).toInt()
                    val remainingOnLastLine = (maxTextWidth - lastRight).coerceAtLeast(0)

                    /* ---- 3) META: subcompose & measure; clamp to available line width ---- */
                    val metaSlotWidth = (contentRightPx - 0).coerceAtLeast(0)
                    val metaPlaceables = run {
                        val iconTint = when {
                            task.status == TaskStatus.DONE -> Color.Gray
                            isNotificationPassed -> textColor.copy(alpha = 0.2f)
                            else -> textColor
                        }
                        subcompose("MetaItems") {
                            if (task.reminderTime != null) {
                                MetaBox {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_notification),
                                        contentDescription = stringResource(R.string.notification_heading),
                                        tint = iconTint,
                                        modifier = Modifier.size(BellIconSize)
                                    )
                                }
                            }
                            if (task.isArchived) {
                                MetaBox { ArchivePill(big = false) }
                            }
                            if (showCategory) {
                                MetaBox {
                                    CategoryPill(category = category, big = false, selected = true)
                                }
                            }
                            if (dueLabel.isNotBlank()) {
                                MetaBox {
                                    Text(
                                        text = dueLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (task.status == TaskStatus.DONE) Color.Gray else textColor,
                                        modifier = Modifier.padding(
                                            horizontal = 2.dp,
                                            vertical = 2.dp
                                        ),
                                        softWrap = true,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }.map {
                            it.measure(
                                Constraints(
                                    maxWidth = metaSlotWidth // prevent overflow on long labels
                                )
                            )
                        }
                    }
                    val metaCount = metaPlaceables.size
                    val metaWidths = metaPlaceables.map { it.width }
                    val metaHeights = metaPlaceables.map { it.height }

                    /* ---- 4) INLINE PACK ON LAST TEXT LINE ---- */
                    var inlineCount = 0
                    var used = 0
                    if (metaCount > 0 && remainingOnLastLine > 0) {
                        for (i in 0 until metaCount) {
                            val w = metaWidths[i]
                            val add = if (inlineCount == 0) (textMetaGapPx + w) else (metaGapPx + w)
                            if (used + add <= remainingOnLastLine) {
                                used += add
                                inlineCount++
                            } else break
                        }
                    }
                    val inlineIndices =
                        if (inlineCount > 0) (0 until inlineCount).toList() else emptyList()
                    val carryIndices =
                        if (inlineCount < metaCount) (inlineCount until metaCount).toList() else emptyList()

                    val inlineWidth = used
                    val inlineHeight = inlineIndices.maxOfOrNull { metaHeights[it] } ?: 0

                    /* ---- 5) WRAP CARRY META ACROSS NEW LINES (end-aligned) ---- */
                    val carryLines: List<List<Int>> = if (carryIndices.isEmpty()) {
                        emptyList()
                    } else {
                        val acc = mutableListOf<MutableList<Int>>()
                        var current = mutableListOf<Int>()
                        var widthAcc = 0
                        carryIndices.forEach { idx ->
                            val w = metaWidths[idx]
                            val add = if (current.isEmpty()) w else (metaGapPx + w)
                            if (widthAcc + add <= metaSlotWidth) {
                                current.add(idx); widthAcc += add
                            } else {
                                if (current.isNotEmpty()) acc.add(current)
                                // start new line with this item
                                current = mutableListOf(idx)
                                widthAcc = w
                            }
                        }
                        if (current.isNotEmpty()) acc.add(current)
                        acc
                    }
                    val carryLinesHeights =
                        carryLines.map { line -> line.maxOf { metaHeights[it] } }
                    val carryBlockHeight = if (carryLinesHeights.isEmpty()) 0
                    else carryLinesHeights.sum() + tierSpacingPx * (carryLinesHeights.size - 1)

                    /* ---- 6) HEIGHTS ---- */
                    val topRowHeight = maxOf(titleHeight, inlineHeight, minTouchPx)
                    val totalHeightPx =
                        if (carryLines.isEmpty()) topRowHeight else topRowHeight + tierSpacingPx + carryBlockHeight

                    /* ---- 7) PLACE ---- */
                    layout(width = maxW, height = totalHeightPx) {
                        // Title (centered within top row)
                        val textY = (topRowHeight - titleHeight) / 2
                        val textX = when (layoutDir) {
                            LayoutDirection.Ltr -> contentLeftPx
                            LayoutDirection.Rtl -> (contentRightPx - titlePlaceable.width)
                        }
                        titlePlaceable.place(x = textX, y = textY)

                        // Inline meta: baseline-align to last text line; enforce minimum TextMetaGap
                        // Inline meta: center on the last text line; enforce minimum TextMetaGap
                        if (inlineIndices.isNotEmpty()) {
                            val lastLineTop = textY + titleResult.getLineTop(lastLine).toInt()
                            val lastLineBottom = textY + titleResult.getLineBottom(lastLine).toInt()
                            val lastLineHeight = (lastLineBottom - lastLineTop).coerceAtLeast(0)
                            val inlineTop = (lastLineTop + (lastLineHeight - inlineHeight) / 2).coerceAtLeast(0)

                            val endAlignedStart = when (layoutDir) {
                                LayoutDirection.Ltr -> contentRightPx - inlineWidth
                                LayoutDirection.Rtl -> contentLeftPx
                            }
                            val gapEnforcedStart = when (layoutDir) {
                                LayoutDirection.Ltr -> (contentLeftPx + lastRight + textMetaGapPx)
                                LayoutDirection.Rtl -> (contentRightPx - lastRight - textMetaGapPx - inlineWidth)
                            }
                            val startX = maxOf(endAlignedStart, gapEnforcedStart)

                            var x = startX
                            inlineIndices.forEachIndexed { j, idx ->
                                val p = metaPlaceables[idx]
                                val yCentered = inlineTop + (inlineHeight - p.height) / 2
                                p.place(x, yCentered)
                                x += p.width + if (j < inlineIndices.lastIndex) metaGapPx else 0
                            }
                        }


                        // Carry meta lines (below top row), end-aligned per line
                        if (carryLines.isNotEmpty()) {
                            var y = topRowHeight + tierSpacingPx
                            carryLines.forEachIndexed { li, line ->
                                val lineH = carryLinesHeights[li]
                                val lineW = line.fold(0) { acc, idx ->
                                    if (acc == 0) metaWidths[idx] else acc + metaGapPx + metaWidths[idx]
                                }.coerceAtMost(metaSlotWidth)

                                val startX = when (layoutDir) {
                                    LayoutDirection.Ltr -> contentRightPx - lineW
                                    LayoutDirection.Rtl -> contentLeftPx
                                }
                                var x = startX
                                line.forEachIndexed { j, idx ->
                                    val p = metaPlaceables[idx]
                                    val yCentered = y + (lineH - p.height) / 2
                                    p.place(x, yCentered)
                                    x += p.width + if (j < line.lastIndex) metaGapPx else 0
                                }
                                y += lineH + tierSpacingPx

                            }
                        }

                        // Handle at PHYSICAL RIGHT, vertically centered
                        val hx = maxW - handleW
                        val hy = (totalHeightPx - handleH) / 2
                        handlePlaceable.place(x = hx, y = hy)
                    }
                }
            }
        }
    }
}
