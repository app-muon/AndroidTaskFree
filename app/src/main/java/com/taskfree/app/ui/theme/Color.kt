package com.taskfree.app.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.taskfree.app.R

@Composable
fun providePanelColors(): PanelColors {
    return PanelColors(
        dialogBackground = colorResource(R.color.dialog_background_colour),
        surfaceText = colorResource(R.color.surface_colour),
        dialogButtonText = colorResource(R.color.dialog_button_text_colour),
        selectedChipBackground = colorResource(R.color.dialog_pill_selected_colour),
        darkRed = colorResource(R.color.dark_red),
        brightRed = colorResource(R.color.bright_red),
        errorText = Color.Red,
        errorBackground = Color.Red.copy(alpha = 0.8f)
    )
}

data class PanelColors(
    val dialogBackground: Color,
    val surfaceText: Color,
    val dialogButtonText: Color,
    val selectedChipBackground: Color,
    val darkRed: Color,
    val brightRed: Color,
    val errorText: Color,
    val errorBackground: Color
)

@Composable
fun PanelColors.outlinedFieldColours() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = surfaceText,
    unfocusedTextColor = surfaceText,
    focusedBorderColor = surfaceText,
    unfocusedBorderColor = surfaceText,
    focusedLabelColor = surfaceText,
    unfocusedLabelColor = surfaceText,
    focusedContainerColor = dialogBackground,
    unfocusedContainerColor = dialogBackground
)


const val ERROR_ALPHA = 0.8f
const val RowTransparency = 0.35f
val FallBackColourForCategory = Color(0xFFE9EEF3)
val categoryPalette = listOf(
    Color(0xFFF44336), // Red
    Color(0xFFE91E63), // Pink
    Color(0xFF9C27B0), // Purple
    Color(0xFF673AB7), // Deep Purple
    Color(0xFF3F51B5), // Indigo
    Color(0xFF2196F3), // Blue
    Color(0xFF03A9F4), // Light Blue
    Color(0xFF00BCD4), // Cyan
    Color(0xFF009688), // Teal
    Color(0xFF4CAF50), // Green
    Color(0xFF8BC34A), // Light Green
    Color(0xFFCDDC39), // Lime
    Color(0xFFFFEB3B), // Yellow
    Color(0xFFFFC107), // Amber
    Color(0xFFFF9800), // Orange
    Color(0xFFFF5722), // Deep Orange
    Color(0xFF795548), // Brown
    Color(0xFF9E9E9E), // Grey
    Color(0xFF607D8B), // Blue Grey
    Color(0xFF6D4C41), // Dark Brown
    Color(0xFFB71C1C), // Dark Red
    Color(0xFF880E4F), // Dark Pink
    Color(0xFF603398), // Dark Purple
    Color(0xFF2F367C), // Dark Indigo
    Color(0xFF0D47A1), // Dark Blue
    Color(0xFF006064), // Dark Cyan
    Color(0xFF1B5E20), // Dark Green
    Color(0xFFF57F17), // Dark Yellow
    Color(0xFFE65100), // Dark Orange
    Color(0xFFBF360C)  // Dark Deep Orange
)
