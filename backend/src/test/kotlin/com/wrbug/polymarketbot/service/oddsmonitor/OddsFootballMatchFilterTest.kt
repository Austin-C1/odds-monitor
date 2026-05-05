package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OddsFootballMatchFilterTest {
    @Test
    fun `ignores esports football with readable Chinese labels`() {
        assertTrue(
            OddsFootballMatchFilter.shouldIgnore(
                leagueName = "电竞足球 GT体育",
                homeTeam = "FC Tokyo",
                awayTeam = "Kawasaki Frontale"
            )
        )
    }

    @Test
    fun `ignores special betting leagues`() {
        assertTrue(
            OddsFootballMatchFilter.shouldIgnore(
                leagueName = "意大利甲组联赛-特别投注",
                homeTeam = "主场",
                awayTeam = "客场"
            )
        )
    }

    @Test
    fun `ignores playoff leagues`() {
        assertTrue(
            OddsFootballMatchFilter.shouldIgnore(
                leagueName = "埃及超级联赛-附加赛",
                homeTeam = "索莫哈",
                awayTeam = "扎马雷克"
            )
        )
    }

    @Test
    fun `keeps regular football matches`() {
        assertFalse(
            OddsFootballMatchFilter.shouldIgnore(
                leagueName = "日本J1百年构想联赛",
                homeTeam = "东京",
                awayTeam = "川崎前锋"
            )
        )
    }
}
