// ui/task/FieldEditExtensions.kt
package com.taskfree.app.ui.task

/**
 * Resolve a FieldEdit<T> to a concrete value.
 *
 * @param current the current stored value
 * @param onClear value to use if the edit means "Clear"
 */
inline fun <T> FieldEdit<T>.resolve(
    current: T, onClear: () -> T
): T = when (this) {
    is FieldEdit.NoChange -> current
    is FieldEdit.Set -> value
    is FieldEdit.Clear -> onClear()
}

/**
 * Convert a nullable T into a FieldEdit.
 * - null => Clear
 * - not null => Set(value)
 */
fun <T> T?.toFieldEdit(): FieldEdit<T> = if (this == null) FieldEdit.Clear else FieldEdit.Set(this)
