package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsMonitorMatchDto
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class OddsMatchDetailServiceTest {
    @Test
    fun `builds crown match metrics from latest snapshots and hides moneyline markets`() {
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val match = OddsMonitorMatchDto(
            id = 100,
            leagueName = "日本J1百年构想联赛",
            homeTeam = "冈山绿雉",
            awayTeam = "广岛三箭",
            startTime = 1000,
            status = "赛前",
            sourceCount = 1,
            alertCount = 0,
            matchedPlatforms = listOf("crown")
        )
        val platformMatch = OddsPlatformMatch(
            id = 20,
            sourceKey = "crown",
            sourceMatchId = "crown-20",
            rawLeagueName = "Japan J1 League",
            rawHomeTeam = "Okayama",
            rawAwayTeam = "Hiroshima",
            rawStartTime = 1000
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(100L), "crown")).thenReturn(
            listOf(
                OddsMarket(id = 1, matchId = 100, sourceKey = "crown", marketType = "handicap", selectionName = "home", lineValue = "-0.5"),
                OddsMarket(id = 2, matchId = 100, sourceKey = "crown", marketType = "moneyline", selectionName = "home")
            )
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(1)).thenReturn(
            OddsSnapshot(marketId = 1, sourceKey = "crown", oddsValue = BigDecimal("0.8300"))
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(2)).thenReturn(
            OddsSnapshot(marketId = 2, sourceKey = "crown", oddsValue = BigDecimal("1.90"))
        )

        val detail = OddsMatchDetailService(
            marketRepository,
            snapshotRepository,
            OddsMonitorDisplayMapper()
        ).buildDetail(match, mapOf("crown" to platformMatch))

        assertEquals(1, detail.metrics.size)
        assertEquals("handicap home -0.5", detail.metrics.single().label)
        assertEquals("0.83", detail.metrics.single().value)
        assertEquals("crown", detail.metrics.single().sourceKey)
        assertTrue(detail.metrics.none { it.label.startsWith("moneyline") })
        assertEquals("日本J1百年构想联赛", detail.platformMatches.single().rawLeagueName)
        assertEquals("冈山绿雉", detail.platformMatches.single().rawHomeTeam)
        assertEquals("广岛三箭", detail.platformMatches.single().rawAwayTeam)
    }
}
