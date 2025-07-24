// ui/ActionItem.kt
package com.taskfree.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

data class ActionItem(
    val labelText: String? = null,
    val labelContent: (@Composable () -> Unit)? = null,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val backgroundColour: Color? = null,
    val labelColor: Color? = null,
    val iconTint: Color? = null,
    val fontWeight: FontWeight? = null
)

fun ActionItem(
    label: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    backgroundColour: Color? = null,
    labelColor: Color? = null,
    iconTint: Color? = null,
    fontWeight: FontWeight? = null
) = ActionItem(
    icon = icon,
    enabled = enabled,
    onClick = onClick,
    labelText = label,
    labelContent = null,
    backgroundColour = backgroundColour,
    labelColor = labelColor,
    iconTint = iconTint,
    fontWeight = fontWeight
)

fun ActionItem(
    labelContent: @Composable () -> Unit,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    backgroundColour: Color? = null,
    labelColor: Color? = null,
    iconTint: Color? = null,
    fontWeight: FontWeight? = null
) = ActionItem(
    icon = icon,
    enabled = enabled,
    onClick = onClick,
    labelText = null,
    labelContent = labelContent,
    backgroundColour = backgroundColour,
    labelColor = labelColor,
    iconTint = iconTint,
    fontWeight = fontWeight
)
