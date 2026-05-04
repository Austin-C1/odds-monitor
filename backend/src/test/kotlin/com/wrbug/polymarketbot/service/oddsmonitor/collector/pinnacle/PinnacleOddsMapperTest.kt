package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PinnacleOddsMapperTest {
    @Test
    fun `maps pinnacle match into first version database rows`() {
        val match = PinnacleFootballMatch(
            sourceMatchId = "9001",
            leagueName = "England - Premier League",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            startTime = 1893456000000L,
            isLive = false,
            handicaps = listOf(
                PinnacleHandicapMarket(
                    line = "-0.5",
                    homeOdds = BigDecimal("1.93"),
                    awayOdds = BigDecimal("1.97")
                ),
                PinnacleHandicapMarket(
                    line = "0-0.5",
                    homeOdds = BigDecimal("1.83"),
                    awayOdds = BigDecimal("2.07")
                )
            ),
            totals = listOf(
                PinnacleTotalMarket(
                    line = "2-2.5",
                    overOdds = BigDecimal("1.88"),
                    underOdds = BigDecimal("2.02")
                )
            ),
            moneyline = PinnacleMoneylineMarket(
                homeOdds = BigDecimal("2.11"),
                drawOdds = BigDecimal("3.30"),
                awayOdds = BigDecimal("3.20")
            ),
            rawPayload = mapOf("event_id" to "9001")
        )

        val rows = PinnacleOddsMapper().map(match, platformMatchId = 12, capturedAt = 1000L)

        assertEquals(4, rows.size)
        assertEquals(
            listOf("home", "away", "over", "under"),
            rows.map { it.selectionName }
        )
        assertEquals(
            listOf("handicap", "handicap", "total", "total"),
            rows.map { it.marketType }
        )
        assertEquals(listOf("-0.5", "-0.5", "2/2.5", "2/2.5"), rows.map { it.lineValue })
        assertEquals(listOf("1.93", "1.97", "1.88", "2.02"), rows.map { it.oddsValue.toPlainString() })
        assertEquals(12, rows.first().platformMatchId)
        assertEquals(1000L, rows.first().capturedAt)
    }
}
