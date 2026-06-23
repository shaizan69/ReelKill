package com.reelkill.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object TimeUtils {
    fun nowUtcIso(clock: Clock = Clock.systemUTC()): String = Instant.now(clock).toString()

    fun localDay(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return LocalDate.ofInstant(instant, zoneId).toString()
    }

    fun localHour(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        return instant.atZone(zoneId).hour
    }

    fun parseInstantOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
