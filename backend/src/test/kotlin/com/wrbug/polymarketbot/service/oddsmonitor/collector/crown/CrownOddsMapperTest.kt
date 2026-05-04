package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CrownOddsMapperTest {
    @Test
    fun `maps crown match into database odds rows`() {
        val match = CrownFootballMatch(
            sourceMatchId = "41001",
            leagueName = "England Premier League",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            startTime = null,
            isLive = false,
            handicaps = listOf(
                CrownHandicapMarket(
                    line = "0 / 0.5",
                    homeOdds = BigDecimal("1.93"),
                    awayOdds = BigDecimal("1.97")
                ),
                CrownHandicapMarket(
                    line = "0.5",
                    homeOdds = BigDecimal("2.05"),
                    awayOdds = BigDecimal("1.85")
                )
            ),
            totals = listOf(
                CrownTotalMarket(
                    line = "2 / 2.5",
                    overOdds = BigDecimal("1.88"),
                    underOdds = BigDecimal("2.02")
                )
            ),
            moneyline = CrownMoneylineMarket(
                homeOdds = BigDecimal("2.11"),
                drawOdds = BigDecimal("3.30"),
                awayOdds = BigDecimal("3.20")
            ),
            rawPayload = mapOf("gid" to "41001")
        )

        val rows = CrownOddsMapper().map(match, platformMatchId = 22, capturedAt = 2000L)

        assertEquals(4, rows.size)
        assertEquals(
            listOf("home", "away", "over", "under"),
            rows.map { it.selectionName }
        )
        assertEquals(
            listOf("handicap", "handicap", "total", "total"),
            rows.map { it.marketType }
        )
        assertEquals(listOf("0/0.5", "0/0.5", "2/2.5", "2/2.5"), rows.map { it.lineValue })
        assertEquals(listOf("1.93", "1.97", "1.88", "2.02"), rows.map { it.oddsValue.toPlainString() })
        assertEquals(22, rows.first().platformMatchId)
        assertEquals(2000L, rows.first().capturedAt)
    }
}
