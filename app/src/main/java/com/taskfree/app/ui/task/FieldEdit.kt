// ui/task/FieldEdit.kt
package com.taskfree.app.ui.task

/**
 * Represents an edit to a field.
 * - NoChange = leave the field as is
 * - Set = update the field to a new value
 * - Clear = explicitly clear the field (to null, NONE, etc.)
 */
sealed class FieldEdit<out T> {
    data object NoChange : FieldEdit<Nothing>()
    data class Set<T>(val value: T) : FieldEdit<T>()
    data object Clear : FieldEdit<Nothing>()
}