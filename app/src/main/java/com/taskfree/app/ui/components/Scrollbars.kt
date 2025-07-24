// Scrollbars.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A very thin, read-only vertical scrollbar for a [LazyListState].
 *
 * *No experimental APIs, no extra dependencies.*
 */
fun Modifier.thinVerticalScrollbar(
    listState: LazyListState,
    thickness: Dp = 2.dp,
    minHeight: Dp = 16.dp,
    color: Color = Color.LightGray.copy(alpha = .6f)
): Modifier = drawWithContent {

    drawContent()                                    // ── 1. list first

    /* ── 2. short-circuit if nothing (reliably) visible ─────────────── */
    val totalItems = listState.layoutInfo.totalItemsCount
    val visibleItemsCnt = listState.layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleItemsCnt == 0) return@drawWithContent
    if (totalItems <= visibleItemsCnt) return@drawWithContent          // all fit

    /* ── 3. thumb geometry ───────────────────────────────────────────── */
    val fractionVisible = visibleItemsCnt.toFloat() / totalItems
    val thumbHeightPx = (size.height * fractionVisible)
        .coerceAtLeast(minHeight.toPx())

    val progress = listState.firstVisibleItemIndex
        .toFloat() / (totalItems - visibleItemsCnt)
    val clamped = progress.coerceIn(0f, 1f)                           //   0‥1

    val thumbTop = (size.height - thumbHeightPx) * clamped
    val barWidth = thickness.toPx()

    /* ── 4. draw it ─────────────────────────────────────────────────── */
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - barWidth, thumbTop),
        size = Size(barWidth, thumbHeightPx),
        cornerRadius = CornerRadius(barWidth, barWidth)
    )
}
