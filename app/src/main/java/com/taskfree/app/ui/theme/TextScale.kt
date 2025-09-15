// app/src/main/java/com/taskfree/app/ui/theme/TextScale.kt
package com.taskfree.app.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.taskfree.app.settings.SettingsPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TextScaleOption(val prefValue: String, val multiplier: Float) {
    SYSTEM_ONLY("SYSTEM", 1f),
    SMALL("SMALL", 0.90f),
    NORMAL("NORMAL", 1.00f),
    LARGE("LARGE", 1.15f),
    XL("XL", 1.30f),
    XXL("XXL", 1.60f);

    companion object {
        fun fromPref(v: String?): TextScaleOption =
            entries.firstOrNull { it.prefValue == v } ?: SYSTEM_ONLY
    }
}

/** App-wide controller for text scale. */
object TextScaleController {
    private val _option = MutableStateFlow(TextScaleOption.SYSTEM_ONLY)
    val option: StateFlow<TextScaleOption> = _option.asStateFlow()

    /** Load once on app start (idempotent). */
    fun ensureLoaded(ctx: Context) {
        val saved = SettingsPrefs.getTextScaleOption(ctx)
        _option.value = if (saved == null) {
            val metrics = ctx.resources.displayMetrics
            val minDimDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
            if (minDimDp >= 600) TextScaleOption.LARGE else TextScaleOption.SYSTEM_ONLY
        } else saved
    }

    fun setOption(ctx: Context, option: TextScaleOption) {
        SettingsPrefs.setTextScaleOption(ctx, option)
        _option.value = option
    }
    fun resetToDefault(ctx: Context) {
        SettingsPrefs.clearTextScaleOption(ctx)
        ensureLoaded(ctx) // re-compute: LARGE on tablet, SYSTEM_ONLY on phone
    }
}

/** Returns a copy of this Typography with all sizes multiplied by [factor]. */
fun Typography.scaled(factor: Float): Typography = Typography(
    displayLarge = displayLarge.scale(factor),
    displayMedium = displayMedium.scale(factor),
    displaySmall = displaySmall.scale(factor),
    headlineLarge = headlineLarge.scale(factor),
    headlineMedium = headlineMedium.scale(factor),
    headlineSmall = headlineSmall.scale(factor),
    titleLarge = titleLarge.scale(factor),
    titleMedium = titleMedium.scale(factor),
    titleSmall = titleSmall.scale(factor),
    bodyLarge = bodyLarge.scale(factor),
    bodyMedium = bodyMedium.scale(factor),
    bodySmall = bodySmall.scale(factor),
    labelLarge = labelLarge.scale(factor),
    labelMedium = labelMedium.scale(factor),
    labelSmall = labelSmall.scale(factor)
)

private fun TextStyle.scale(f: Float): TextStyle = copy(
    fontSize = fontSize.scaleIfSpecified(f),
    lineHeight = lineHeight.scaleIfSpecified(f)
)

private fun TextUnit.scaleIfSpecified(f: Float): TextUnit =
    if (isSpecified) this * f else this
