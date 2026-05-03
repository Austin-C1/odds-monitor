package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import org.springframework.stereotype.Component

@Component
class CrownOddsMapper {
    fun map(match: CrownFootballMatch, platformMatchId: Long, capturedAt: Long): List<CrownMappedOddsRow> {
        val rows = mutableListOf<CrownMappedOddsRow>()

        match.handicaps.forEach { market ->
            rows += CrownMappedOddsRow(platformMatchId, "handicap", market.line, "home", market.homeOdds, capturedAt, match.rawPayload)
            rows += CrownMappedOddsRow(platformMatchId, "handicap", market.line, "away", market.awayOdds, capturedAt, match.rawPayload)
        }

        match.totals.forEach { market ->
            rows += CrownMappedOddsRow(platformMatchId, "total", market.line, "over", market.overOdds, capturedAt, match.rawPayload)
            rows += CrownMappedOddsRow(platformMatchId, "total", market.line, "under", market.underOdds, capturedAt, match.rawPayload)
        }

        return rows
    }
}
