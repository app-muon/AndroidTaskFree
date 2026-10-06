// data/ConvertersAndSerializersTest.kt
package com.taskfree.app.data

import com.taskfree.app.data.converters.InstantConverter
import com.taskfree.app.data.converters.LocalDateConverter
import com.taskfree.app.data.converters.RecurrenceConverter
import com.taskfree.app.data.converters.TaskStatusConverter
import com.taskfree.app.data.serialization.InstantSerializer
import com.taskfree.app.data.serialization.LocalDateSerializer
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ConvertersAndSerializersTest {

    private val date = LocalDate.of(2026, 10, 6)
    private val instant = Instant.parse("2026-10-06T08:15:30Z")

    @Test
    fun `LocalDate is stored as epoch day`() {
        val c = LocalDateConverter()
        assertEquals(1L, c.toEpochDay(LocalDate.of(1970, 1, 2)))
        assertEquals(date, c.fromEpochDay(c.toEpochDay(date)))
        assertNull(c.toEpochDay(null))
        assertNull(c.fromEpochDay(null))
    }

    @Test
    fun `Instant is stored as epoch millis`() {
        val c = InstantConverter()
        assertEquals(instant.toEpochMilli(), c.toEpochMillis(instant))
        assertEquals(instant, c.fromEpochMillis(c.toEpochMillis(instant)))
        assertNull(c.toEpochMillis(null))
        assertNull(c.fromEpochMillis(null))
    }

    @Test
    fun `enums are stored by name`() {
        val rc = RecurrenceConverter()
        Recurrence.entries.forEach { assertEquals(it, rc.toRecurrence(rc.fromRecurrence(it))) }
        val sc = TaskStatusConverter()
        TaskStatus.entries.forEach { assertEquals(it, sc.toStatus(sc.fromStatus(it))) }

        assertThrows(IllegalArgumentException::class.java) { sc.toStatus("ARCHIVED") }
    }

    @Test
    fun `backup JSON uses ISO-8601 strings`() {
        assertEquals("\"2026-10-06\"", Json.encodeToString(LocalDateSerializer, date))
        assertEquals(date, Json.decodeFromString(LocalDateSerializer, "\"2026-10-06\""))

        assertEquals("\"2026-10-06T08:15:30Z\"", Json.encodeToString(InstantSerializer, instant))
        assertEquals(instant, Json.decodeFromString(InstantSerializer, "\"2026-10-06T08:15:30Z\""))
    }
}
