// ui/onboarding/LocalTipManager.kt
package com.taskfree.app.ui.onboarding

import androidx.compose.runtime.staticCompositionLocalOf

val LocalTipManager = staticCompositionLocalOf<TipManager> {
    error("TipManager not provided")
}
