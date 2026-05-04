package com.wrbug.polymarketbot.service.oddsmonitor.collector

import com.wrbug.polymarketbot.service.oddsmonitor.collector.crown.CrownFootballMatch
import com.wrbug.polymarketbot.service.oddsmonitor.collector.crown.CrownHandicapMarket
import com.wrbug.polymarketbot.service.oddsmonitor.collector.crown.CrownOddsMapper
import com.wrbug.polymarketbot.service.oddsmonitor.collector.crown.CrownTotalMarket
import com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle.PinnacleFootballMatch
import com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle.PinnacleHandicapMarket
import com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle.PinnacleOddsMapper
import com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle.PinnacleTotalMarket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OddsMainMarketMapperTest {
    @Test
    fun `pinnacle mapper keeps only first handicap and first total main markets`() {
        val match = PinnacleFootballMatch(
            sourceMatchId = "p1",
            leagueName = "日本J1",
            homeTeam = "东京",
            awayTeam = "川崎前锋",
            startTime = null,
            isLive = false,
            handicaps = listOf(
                PinnacleHandicapMarket("0/0.5", BigDecimal("1.91"), BigDecimal("1.99")),
                PinnacleHandicapMarket("1", BigDecimal("1.80"), BigDecimal("2.10"))
            ),
            totals = listOf(
                PinnacleTotalMarket("2.5", BigDecimal("1.88"), BigDecimal("2.02")),
                PinnacleTotalMarket("3", BigDecimal("1.77"), BigDecimal("2.13"))
            ),
            moneyline = null,
            rawPayload = emptyMap()
        )

        val rows = PinnacleOddsMapper().map(match, 10, 1000)

        assertEquals(4, rows.size)
        assertEquals(setOf("0/0.5"), rows.filter { it.marketType == "handicap" }.map { it.lineValue }.toSet())
        assertEquals(setOf("2.5"), rows.filter { it.marketType == "total" }.map { it.lineValue }.toSet())
        assertTrue(rows.none { it.marketType == "moneyline" })
    }

    @Test
    fun `crown mapper keeps only first handicap and first total main markets`() {
        val match = CrownFootballMatch(
            sourceMatchId = "c1",
            leagueName = "日本J1",
            homeTeam = "东京",
            awayTeam = "川崎前锋",
            startTime = null,
            isLive = false,
            handicaps = listOf(
                CrownHandicapMarket("0/0.5", BigDecimal("0.90"), BigDecimal("0.94")),
                CrownHandicapMarket("1", BigDecimal("0.82"), BigDecimal("1.02"))
            ),
            totals = listOf(
                CrownTotalMarket("2.5", BigDecimal("0.88"), BigDecimal("0.98")),
                CrownTotalMarket("3", BigDecimal("0.80"), BigDecimal("1.06"))
            ),
            moneyline = null,
            rawPayload = emptyMap()
        )

        val rows = CrownOddsMapper().map(match, 10, 1000)

        assertEquals(4, rows.size)
        assertEquals(setOf("0/0.5"), rows.filter { it.marketType == "handicap" }.map { it.lineValue }.toSet())
        assertEquals(setOf("2.5"), rows.filter { it.marketType == "total" }.map { it.lineValue }.toSet())
        assertTrue(rows.none { it.marketType == "moneyline" })
    }
}
