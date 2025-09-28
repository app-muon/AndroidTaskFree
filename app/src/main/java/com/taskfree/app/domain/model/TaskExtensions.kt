// domain/model/TaskExtensions.kt
package com.taskfree.app.domain.model

import com.taskfree.app.data.entities.Task
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.toInstant
import java.time.Instant
import java.time.LocalDate

/**
 * Decide if a Task should get a notification.
 *
 * Rules:
 * - DONE tasks never get notifications
 * - Archived tasks never get notifications
 * - Only future instants are valid
 */
fun Task.resolveReminderInstant(
    notify: NotificationOption,
    due: LocalDate?
): ReminderResult {
    if (status == TaskStatus.DONE || isArchived) return ReminderResult.Blocked

    val instant = notify.toInstant(due) ?: return ReminderResult.Blocked

    return if (instant.isAfter(Instant.now())) {
        ReminderResult.Scheduled(instant)
    } else {
        ReminderResult.InPast
    }
}

