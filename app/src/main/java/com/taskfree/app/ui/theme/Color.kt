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



const val RowTransparency = 0.35f
val FallBackColourForCategory = Color(0xFFE9EEF3)
val categoryPalette = listOf(
    Color(0xFFD32F2F), // Red
    Color(0xFFC2185B), // Pink
    Color(0xFF9C27B0), // Brighter Purple
    Color(0xFF7C4DFF), // Brighter Deep Purple
    Color(0xFF5C6BC0), // Brighter Indigo
    Color(0xFF1976D2), // Blue
    Color(0xFF0288D1), // Light Blue
    Color(0xFF00838F), // Cyan
    Color(0xFF009688), // Teal
    Color(0xFF388E3C), // Green
    Color(0xFF689F38), // Light Green
    Color(0xFF827717), // Lime
    Color(0xFFF57F17), // Yellow
    Color(0xFFE65100), // Amber
    Color(0xFFEF6C00), // Orange
    Color(0xFFE64A19), // Deep Orange
    Color(0xFF795548), // Brighter Brown
    Color(0xFF78909C), // Slate Grey
    Color(0xFF90A4AE), // Lighter Blue Grey
    Color(0xFF8D6E63), // Brighter Dark Brown
    Color(0xFFC62828), // Dark Red
    Color(0xFFAD1457), // Dark Pink
    Color(0xFF8E24AA), // Brighter Dark Purple
    Color(0xFF5C6BC0), // Brighter Dark Indigo
    Color(0xFF1E88E5), // Dark Blue
    Color(0xFF26A69A), // Dark Cyan
    Color(0xFF43A047), // Dark Green
    Color(0xFFEF6C00), // Dark Yellow
    Color(0xFFD84315), // Dark Orange
    Color(0xFFE64A19) // Dark Deep Orange
)
