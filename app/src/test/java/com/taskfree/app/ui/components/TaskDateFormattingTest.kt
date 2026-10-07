package com.taskfree.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class TaskDateFormattingTest {
    @Test
    fun `historical dates include the year in the selected locale`() {
        val date = LocalDate.of(2025, 7, 15)
        assertEquals("Jul 15, 2025", formatTaskDate(date, Locale.US))
        assertEquals("15 Jul 2025", formatTaskDate(date, Locale.UK))
        assertEquals("15 jul 2025", formatTaskDate(date, Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun `creation dates use the supplied timezone at midnight boundaries`() {
        val instant = Instant.parse("2025-07-15T00:30:00Z")
        assertEquals("Jul 15, 2025", formatCreationDate(instant, Locale.US, ZoneId.of("UTC")))
        assertEquals("Jul 14, 2025", formatCreationDate(instant, Locale.US, ZoneId.of("America/Los_Angeles")))
        assertEquals("15 Jul 2025", formatCreationDate(instant, Locale.UK, ZoneId.of("Europe/London")))
    }
}
