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
    fun `keeps regular football matches`() {
        assertFalse(
            OddsFootballMatchFilter.shouldIgnore(
                leagueName = "日本J1",
                homeTeam = "东京",
                awayTeam = "川崎前锋"
            )
        )
    }
}
