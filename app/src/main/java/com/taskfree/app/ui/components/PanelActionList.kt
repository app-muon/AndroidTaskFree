package com.taskfree.app.ui.components

// Add these imports at the top of your file
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    actions: List<ActionItem>, onDismiss: () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val backgroundColour = colorResource(R.color.dialog_background_colour)
    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = shape, color = backgroundColour, modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                if (headerContent != null) {
                    headerContent()
                }
                actions.forEach { action ->
                    val color = action.labelColor
                        ?: (if (!action.enabled) Color.Gray else colorResource(R.color.surface_colour))

                    val iconTint = if (!action.enabled) Color.Gray else action.iconTint
                        ?: colorResource(R.color.surface_colour)

                    // Use a Row to add the left color bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clickable(enabled = action.enabled) {
                                action.onClick()
                                onDismiss()
                            }
                    ) {
                        // Left color bar - always present, transparent if no color specified
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .background(
                                    action.backgroundColour ?: Color.Transparent
                                )
                        )

                        // The actual ListItem content
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = colorResource(id = R.color.dialog_background_colour)
                            ),
                            headlineContent = {
                                when {
                                    action.labelContent != null -> {
                                        // your custom composable (e.g. the pill + text)
                                        action.labelContent.invoke()
                                    }

                                    action.labelText != null -> {
                                        // fallback to plain text
                                        Text(
                                            action.labelText,
                                            color = color,
                                            fontWeight = action.fontWeight
                                        )
                                    }

                                    else -> {
                                        // nothing to show
                                    }
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
                            },
                            modifier = Modifier.weight(1f) // Take up remaining space
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss() },
                    color = colorResource(R.color.dialog_background_colour),
                    shape = MaterialTheme.shapes.large.copy(
                        topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)
                    ) // Only round the bottom corners
                ) {
                    Text(
                        text = stringResource(R.string.dismiss_yes_dialog_button),
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
}
