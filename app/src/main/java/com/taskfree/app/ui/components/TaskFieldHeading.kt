// TaskFieldHeading.kt
package com.taskfree.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.taskfree.app.ui.theme.providePanelColors

@Composable
fun TaskFieldHeading(headingString: String, modifier: Modifier = Modifier) {
    val colors = providePanelColors()
    val displayString = if (headingString == "") "" else "${headingString.uppercase()}:"
    Text(
        displayString,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
        color = colors.surfaceText.copy(alpha = 0.5f)
    )
}