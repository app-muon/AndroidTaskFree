package com.taskfree.app.ui.onboarding

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp

data class OnboardingTip(
    val id: TipId,
    val title: String,
    val body: AnnotatedString?,
    val anchor: Anchor
)

sealed class Anchor {
    data object AboveBottomBarInCentre : Anchor()
    data object AboveBottomBarOnRight : Anchor()
    data class ScreenHeightPercent(val percent: Float) : Anchor()
    data object HandleOfFirstRow : Anchor()
    data class Absolute(val x: Dp, val y: Dp) : Anchor()
}
