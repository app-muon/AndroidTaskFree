// ui/task/FieldEditTest.kt
package com.taskfree.app.ui.task

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldEditTest {

    @Test
    fun `resolve keeps, replaces or clears`() {
        assertEquals("old", FieldEdit.NoChange.resolve("old") { "cleared" })
        assertEquals("new", FieldEdit.Set("new").resolve("old") { "cleared" })
        assertEquals("cleared", FieldEdit.Clear.resolve("old") { "cleared" })
    }

    @Test
    fun `toFieldEdit maps null to Clear`() {
        assertEquals(FieldEdit.Clear, (null as String?).toFieldEdit())
        assertEquals(FieldEdit.Set(5), 5.toFieldEdit())
    }
}
