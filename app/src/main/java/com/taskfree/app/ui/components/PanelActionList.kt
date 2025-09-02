package com.taskfree.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Modifier
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
    showBottomFade: Boolean = true        // optional hint
) {
    val shape = MaterialTheme.shapes.large
    val backgroundColour = colorResource(R.color.dialog_background_colour)
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box {
            Surface(
                shape = shape,
                color = backgroundColour,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)          // keep within viewport
                    .heightIn(min = 0.dp)          // allow scroll when content grows
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

            // Optional bottom fade overlay (static)
            if (showBottomFade && listState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    backgroundColour
                                )
                            )
                        )
                        .heightIn(min = 64.dp)
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }
    }
}

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
