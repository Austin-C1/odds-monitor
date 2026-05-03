package com.wrbug.polymarketbot.service.copytrading.statistics

import java.time.Instant
import java.time.ZoneId

internal object TradingDayBoundary {

    fun currentDayStartMillis(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        return Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
