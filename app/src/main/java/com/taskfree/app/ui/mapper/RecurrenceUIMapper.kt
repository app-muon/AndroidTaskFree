package com.taskfree.app.ui.mapper

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.resultLabel
import com.taskfree.app.util.format

@Composable
fun RepeatRule?.displayName(): String = format(LocalResources.current)

@Composable
fun recurrenceLabel(dueChoice: DueChoice, rule: RepeatRule?): String {
    val base = dueChoice.resultLabel()
    return if (rule?.recurrence != Recurrence.NONE) "$base, ${rule.displayName()}" else base
}
