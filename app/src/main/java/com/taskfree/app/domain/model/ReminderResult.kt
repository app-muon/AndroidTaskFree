package com.taskfree.app.domain.model

import java.time.Instant

sealed class ReminderResult {
    data class Scheduled(val instant: Instant) : ReminderResult()
    data object InPast : ReminderResult()
    data object Blocked : ReminderResult()
}