package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.springframework.stereotype.Component

@Component
class PinnacleOddsMapper {
    fun map(match: PinnacleFootballMatch, platformMatchId: Long, capturedAt: Long): List<PinnacleMappedOddsRow> {
        val rows = mutableListOf<PinnacleMappedOddsRow>()

        match.handicaps.forEach { market ->
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "handicap",
                lineValue = market.line,
                selectionName = "home",
                oddsValue = market.homeOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "handicap",
                lineValue = market.line,
                selectionName = "away",
                oddsValue = market.awayOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
        }

        match.totals.forEach { market ->
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "total",
                lineValue = market.line,
                selectionName = "over",
                oddsValue = market.overOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
            rows += PinnacleMappedOddsRow(
                platformMatchId = platformMatchId,
                marketType = "total",
                lineValue = market.line,
                selectionName = "under",
                oddsValue = market.underOdds,
                capturedAt = capturedAt,
                rawPayload = match.rawPayload
            )
        }

        return rows
    }
}
