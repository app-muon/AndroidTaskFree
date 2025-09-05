// app/util/TimeExt.kt
package com.taskfree.app.util

import java.time.*

fun Instant.sameLocalTimeOn(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): Instant =
    date.atTime(this.atZone(zone).toLocalTime()).atZone(zone).toInstant()