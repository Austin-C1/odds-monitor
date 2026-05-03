package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TradingDayBoundaryTest {

    @Test
    fun `uses the configured zone instead of utc midnight`() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 4, 17, 10, 30, 0, 0, zoneId).toInstant().toEpochMilli()

        val start = TradingDayBoundary.currentDayStartMillis(now, zoneId)

        val expected = ZonedDateTime.of(2026, 4, 17, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        assertEquals(expected, start)
    }

    @Test
    fun `returns utc midnight when the zone is utc`() {
        val zoneId = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 4, 17, 10, 30, 0, 0, zoneId).toInstant().toEpochMilli()

        val start = TradingDayBoundary.currentDayStartMillis(now, zoneId)

        val expected = ZonedDateTime.of(2026, 4, 17, 0, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        assertEquals(expected, start)
    }
}
