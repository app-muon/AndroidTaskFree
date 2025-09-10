package com.taskfree.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R

@Composable
fun PanelActionList(
    headerContent: (@Composable () -> Unit)? = null,
    actions: List<ActionItem>,
    onDismiss: () -> Unit,
    showBottomFade: Boolean = true,
    showTopFade: Boolean = true // optional hint
) {
    val surfaceShape = MaterialTheme.shapes.large
    val backgroundColour = colorResource(R.color.dialog_background_colour)
    val listState = rememberLazyListState()

// ✅ Wrap frequently changing reads
    val topFadeVisible by remember(showTopFade, listState) {
        derivedStateOf {
            showTopFade && (listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 0)
        }
    }
    val bottomFadeVisible by remember(showBottomFade, listState) {
        derivedStateOf { showBottomFade && listState.canScrollForward }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box {
            Surface(
                shape = surfaceShape,
                color = backgroundColour,
                modifier = Modifier
                    .dialogResponsiveWidth()
                    .dialogMaxHeight()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .thinVerticalScrollbar(listState)
                ) {
                    // Header (scrolls with content)
                    headerContent?.let { hc ->
                        item {
                            hc()
                        }
                    }

                    // Actions (each row is tappable)
                    items(actions) { action ->
                        val labelColor = action.labelColor
                            ?: (if (!action.enabled) Color.Gray else colorResource(R.color.surface_colour))
                        val iconTint = if (!action.enabled) Color.Gray else action.iconTint
                            ?: colorResource(R.color.surface_colour)

                        RowWithLeftBar(
                            barColor = action.backgroundColour ?: Color.Transparent,
                            enabled = action.enabled,
                            onClick = {
                                action.onClick()
                                onDismiss()
                            }
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = backgroundColour
                                ),
                                headlineContent = {
                                    when {
                                        action.labelContent != null -> action.labelContent.invoke()
                                        action.labelText != null -> Text(
                                            text = action.labelText,
                                            color = labelColor,
                                            fontWeight = action.fontWeight
                                        )

                                        else -> {}
                                    }
                                },
                                leadingContent = action.icon?.let { icon ->
                                    {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = action.labelText ?: "",
                                            tint = iconTint
                                        )
                                    }
                                }
                            )
                        }
                    }

                    // Close (scrolls with content)
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDismiss() },
                            color = backgroundColour,
                            shape = MaterialTheme.shapes.large.copy(
                                topStart = MaterialTheme.shapes.large.topStart,
                                topEnd = MaterialTheme.shapes.large.topEnd
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.close_yes_dialog_button),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                color = colorResource(R.color.dialog_button_text_colour),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            if (topFadeVisible || bottomFadeVisible) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(surfaceShape)
                ) {
                    val fadeH = 56.dp

                    if (topFadeVisible) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(fadeH)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(backgroundColour, backgroundColour.copy(alpha = 0f))
                                    )
                                )
                        )
                    }
                    if (bottomFadeVisible) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(fadeH)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(backgroundColour.copy(alpha = 0f), backgroundColour)
                                    )
                                )
                        )
                    }
                }
            }
        }
    }}

    @Composable
    private fun RowWithLeftBar(
        barColor: Color,
        enabled: Boolean,
        onClick: () -> Unit,
        content: @Composable () -> Unit
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(barColor)
            )
            content()
        }
    }
