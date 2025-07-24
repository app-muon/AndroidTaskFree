// ui/components/NotificationOption.kt
package com.taskfree.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*──────────────────────────── 1 ▌MODEL ────────────────────────────────*/

enum class NotificationKind { NONE, MORNING, NOON, AFTERNOON, OTHER }

sealed class NotificationOption {
    /** concrete time-of-day for the reminder, null for None */
    abstract val time: LocalTime?

    /*── Variants ──────────────────────────────────────────────────────*/
    data object None : NotificationOption() {
        override val time: LocalTime? = null
    }

    data object Morning : NotificationOption() {
        override val time: LocalTime? = LocalTime.of(9, 0)
    }

    data object Noon : NotificationOption() {
        override val time: LocalTime? = LocalTime.NOON
    }

    data object Afternoon : NotificationOption() {
        override val time: LocalTime? = LocalTime.of(15, 0)
    }

    data class Other(override val time: LocalTime?) :
        NotificationOption()   // may be null (picker not chosen)

    /*────────────────────────── factory ───────────────────────────────*/
    companion object {
        /** Convert a LocalTime to the closest preset or OTHER */
        fun from(time: LocalTime): NotificationOption = when (time) {
            LocalTime.of(9, 0) -> Morning
            LocalTime.NOON -> Noon
            LocalTime.of(15, 0) -> Afternoon
            else -> Other(time)
        }

        /** Build from Task.reminderTime (Instant?) */
        fun fromTask(task: Task): NotificationOption = task.reminderTime?.let {
            val local = it.atZone(ZoneId.systemDefault()).toLocalTime()
            from(local)
        } ?: None

        /** Preset list for chooser UI (order matters) */
        fun allChoices(): List<NotificationOption> =
            listOf(None, Morning, Noon, Afternoon, Other(null))
    }
}

/*────────────────────────── 2 ▌LABELS ────────────────────────────────*/

@Composable
fun NotificationOption.choiceLabel(): String = when (this) {
    NotificationOption.None -> stringResource(R.string.no_notification)
    NotificationOption.Morning -> stringResource(R.string.nine_am)
    NotificationOption.Noon -> stringResource(R.string.noon)
    NotificationOption.Afternoon -> stringResource(R.string.three_pm)
    is NotificationOption.Other -> stringResource(R.string.time_picker)   // always “Pick time…”
}

@Composable
fun NotificationOption.resultLabel(): String = when (this) {
    NotificationOption.None, NotificationOption.Morning, NotificationOption.Noon, NotificationOption.Afternoon -> choiceLabel()

    is NotificationOption.Other -> time?.toString()
        ?: stringResource(R.string.time_picker)            // HH:mm or “Pick time…”
}

/** True → UI should launch a time-picker */
fun NotificationOption.launchTimePicker(): Boolean = this is NotificationOption.Other

/*────────────────────────── 3 ▌SAVER ──────────────────────────────────*/

val NotificationOptionSaver: Saver<NotificationOption, List<Any?>> = Saver(save = { opt ->
    listOf(
        opt.kind.name,             // index 0
        opt.time?.toString()       // index 1 (ISO LocalTime or null)
    )
}, restore = { saved ->
    val kindName = saved[0] as String
    val iso = saved[1] as String?

    when (kindName) {
        "NONE" -> NotificationOption.None
        "MORNING" -> NotificationOption.Morning
        "NOON" -> NotificationOption.Noon
        "AFTERNOON" -> NotificationOption.Afternoon
        "OTHER" -> NotificationOption.Other(iso?.let(LocalTime::parse))
        else -> NotificationOption.None
    }
})

/*────────────────────────── 4 ▌EXTENSION ─────────────────────────────*/

val NotificationOption.kind: NotificationKind
    get() = when (this) {
        NotificationOption.None -> NotificationKind.NONE
        NotificationOption.Morning -> NotificationKind.MORNING
        NotificationOption.Noon -> NotificationKind.NOON
        NotificationOption.Afternoon -> NotificationKind.AFTERNOON
        is NotificationOption.Other -> NotificationKind.OTHER
    }


fun NotificationOption.toInstant(dueDate: LocalDate?): Instant? {
    val t = this.time ?: return null
    val d = dueDate ?: return null
    return d.atTime(t).atZone(ZoneId.systemDefault()).toInstant()
}

fun NotificationOption.isSameKindAs(other: NotificationOption) = this::class == other::class