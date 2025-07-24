//NotificationValidation.kt
package com.taskfree.app.domain.model

import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueKind
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.kind

sealed class NotificationValidationResult {
    data object Ok : NotificationValidationResult()
    data object MissingDueDate : NotificationValidationResult()
}

fun validateNotification(
    dueChoice: DueChoice,
    notify: NotificationOption
): NotificationValidationResult {
    return if (notify != NotificationOption.None && dueChoice.kind == DueKind.NONE)
        NotificationValidationResult.MissingDueDate
    else NotificationValidationResult.Ok
}
