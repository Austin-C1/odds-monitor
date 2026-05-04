package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.service.oddsmonitor.OddsLineDisplayFormatter
import org.springframework.stereotype.Component

@Component
class CrownOddsMapper {
    fun map(match: CrownFootballMatch, platformMatchId: Long, capturedAt: Long): List<CrownMappedOddsRow> {
        val rows = mutableListOf<CrownMappedOddsRow>()

        match.handicaps.take(1).forEach { market ->
            val line = OddsLineDisplayFormatter.format("handicap", market.line)
            rows += CrownMappedOddsRow(platformMatchId, "handicap", line, "home", market.homeOdds, capturedAt, match.rawPayload)
            rows += CrownMappedOddsRow(platformMatchId, "handicap", line, "away", market.awayOdds, capturedAt, match.rawPayload)
        }

        match.totals.take(1).forEach { market ->
            val line = OddsLineDisplayFormatter.format("total", market.line)
            rows += CrownMappedOddsRow(platformMatchId, "total", line, "over", market.overOdds, capturedAt, match.rawPayload)
            rows += CrownMappedOddsRow(platformMatchId, "total", line, "under", market.underOdds, capturedAt, match.rawPayload)
        }

        return rows
    }
}
