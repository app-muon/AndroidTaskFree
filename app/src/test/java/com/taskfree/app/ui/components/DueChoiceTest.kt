package com.taskfree.app.ui.components

import com.taskfree.app.testutil.AppDateProviderRule
import com.taskfree.app.util.AppDateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class DueChoiceTest {
    @get:Rule val dates = AppDateProviderRule(LocalDate.of(2026, 11, 16))

    @Test fun fixedDatesSelectRelativeChipsWithoutBecomingRelativeDrafts() {
        val today = AppDateProvider.current.today()
        for ((offset, option) in listOf(0L to DueChoice.Today, 1L to DueChoice.Tomorrow, 2L to DueChoice.Plus2)) {
            val fixed = DueChoice.Other(today.plusDays(offset))
            assertTrue(fixed.matchesDateOption(option))
            assertFalse(fixed.matchesDateOption(DueChoice.Other(null)))
            assertEquals(today.plusDays(offset), fixed.date)
        }
        assertTrue(DueChoice.Other(today.plusDays(8)).matchesDateOption(DueChoice.Other(null)))
        assertTrue(DueChoice.None.matchesDateOption(DueChoice.None))
        assertFalse(DueChoice.None.matchesDateOption(DueChoice.Other(null)))
    }
}
