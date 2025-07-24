// ui/components/AppCheckbox.kt
package com.taskfree.app.ui.components

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.taskfree.app.R

@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) = Checkbox(
    checked = checked,
    onCheckedChange = onCheckedChange,
    colors = CheckboxDefaults.colors(
        checkedColor = colorResource(R.color.dialog_button_text_colour),
        uncheckedColor = colorResource(R.color.dialog_button_text_colour),
        checkmarkColor = colorResource(R.color.surface_colour)    // box when ON)
    )
)