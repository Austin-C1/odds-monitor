package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OddsNotificationMarketStateServiceTest {
    @Test
    fun `line change suppresses only the first odds baseline for the new line`() {
        val service = OddsNotificationMarketStateService()
        val match = OddsPlatformMatch(
            sourceKey = "crown",
            rawLeagueName = "英格兰超级联赛",
            rawHomeTeam = "主队",
            rawAwayTeam = "客队"
        )
        val standardMatch = OddsMatch(id = 12)

        service.notifyMarketState(match, standardMatch, "handicap", setOf("0"))
        service.notifyMarketState(match, standardMatch, "handicap", setOf("0.5"))

        val market = OddsMarket(
            matchId = 12,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "0.5",
            selectionName = "home"
        )

        assertTrue(service.shouldResetOddsBaselineAfterLineChange(market))
        assertFalse(service.shouldResetOddsBaselineAfterLineChange(market))
    }

    @Test
    fun `clearing source state removes pending line change suppression`() {
        val service = OddsNotificationMarketStateService()
        val match = OddsPlatformMatch(
            sourceKey = "crown",
            rawLeagueName = "英格兰超级联赛",
            rawHomeTeam = "主队",
            rawAwayTeam = "客队"
        )
        val standardMatch = OddsMatch(id = 18)

        service.notifyMarketState(match, standardMatch, "handicap", setOf("0"))
        service.notifyMarketState(match, standardMatch, "handicap", setOf("0.5"))
        service.clearSourceState("crown")

        assertFalse(
            service.shouldResetOddsBaselineAfterLineChange(
                OddsMarket(
                    matchId = 18,
                    sourceKey = "crown",
                    marketType = "handicap",
                    lineValue = "0.5",
                    selectionName = "home"
                )
            )
        )
    }
}
