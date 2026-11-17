package com.taskfree.app.ui.mapper

import androidx.annotation.StringRes
import com.taskfree.app.R
import com.taskfree.app.domain.model.TaskEditFailureReason

@StringRes
fun TaskEditFailureReason.messageResId(): Int = when (this) {
    TaskEditFailureReason.VALIDATION -> R.string.task_edit_validation_failed
    TaskEditFailureReason.MISSING_TASK -> R.string.task_edit_missing
    TaskEditFailureReason.INACTIVE_CATEGORY -> R.string.task_edit_inactive_category
    TaskEditFailureReason.INVALID_RULE -> R.string.task_edit_invalid_rule
    TaskEditFailureReason.STALE_REPEAT_TARGET -> R.string.repeat_stale_draft
    TaskEditFailureReason.STORAGE -> R.string.task_save_failed
}
