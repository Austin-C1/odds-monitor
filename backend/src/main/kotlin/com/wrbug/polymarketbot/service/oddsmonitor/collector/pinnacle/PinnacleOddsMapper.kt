package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.wrbug.polymarketbot.service.oddsmonitor.OddsLineDisplayFormatter
import org.springframework.stereotype.Component

@Component
class PinnacleOddsMapper {
    fun map(match: PinnacleFootballMatch, platformMatchId: Long, capturedAt: Long): List<PinnacleMappedOddsRow> {
        val rows = mutableListOf<PinnacleMappedOddsRow>()

        match.handicaps.take(1).forEach { market ->
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "handicap",
                lineValue = OddsLineDisplayFormatter.format("handicap", market.line),
                selectionName = "home",
                oddsValue = market.homeOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "handicap",
                lineValue = OddsLineDisplayFormatter.format("handicap", market.line),
                selectionName = "away",
                oddsValue = market.awayOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
        }

        match.totals.take(1).forEach { market ->
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "total",
                lineValue = OddsLineDisplayFormatter.format("total", market.line),
                selectionName = "over",
                oddsValue = market.overOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "total",
                lineValue = OddsLineDisplayFormatter.format("total", market.line),
                selectionName = "under",
                oddsValue = market.underOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
        }

        return rows
    }
}
