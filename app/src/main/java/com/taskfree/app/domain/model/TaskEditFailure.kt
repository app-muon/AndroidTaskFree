package com.taskfree.app.domain.model

/** Expected edit failures are corrective; only storage/access failures can be retried. */
enum class TaskEditFailureReason {
    VALIDATION, MISSING_TASK, INACTIVE_CATEGORY, INVALID_RULE, STALE_REPEAT_TARGET, STORAGE;

    val retryable: Boolean get() = this == STORAGE
}

class TaskEditException(val reason: TaskEditFailureReason) : IllegalArgumentException(reason.name)

fun Throwable.taskEditFailureReason(): TaskEditFailureReason =
    (this as? TaskEditException)?.reason ?: TaskEditFailureReason.STORAGE
