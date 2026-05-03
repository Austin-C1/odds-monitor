package com.wrbug.polymarketbot.service.market

import com.wrbug.polymarketbot.api.GammaEventMarketItem
import com.wrbug.polymarketbot.api.GammaSearchEventItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketBettingDateFilterTest {

    @Test
    fun `extracts date from plain telegram query`() {
        val parsedIso = MarketBettingTelegramCommandParser.parse("Celtics vs 76ers 2026-04-26")
        val parsedShort = MarketBettingTelegramCommandParser.parse("Celtics vs 76ers 4/26")

        assertEquals("Celtics vs 76ers", parsedIso?.query)
        assertEquals("2026-04-26", parsedIso?.date)
        assertEquals("Celtics vs 76ers", parsedShort?.query)
        assertEquals("2026-04-26", parsedShort?.date)
    }

    @Test
    fun `matches event by exact date`() {
        val event = GammaSearchEventItem(
            slug = "nba-bos-phi-2026-04-26",
            startDate = "2026-04-20T14:05:37.802584Z",
            endDate = "2026-04-26T23:00:00Z"
        )

        assertTrue(MarketBettingDateFilter.matches(event, "2026-04-26"))
        assertFalse(MarketBettingDateFilter.matches(event, "2026-04-28"))
    }

    @Test
    fun `matches market by exact date`() {
        val market = GammaEventMarketItem(
            slug = "nba-bos-phi-2026-04-26-moneyline",
            startDate = "2026-04-26T23:00:00Z",
            endDate = "2026-04-27T02:00:00Z"
        )

        assertTrue(MarketBettingDateFilter.matches(market, "2026-04-26"))
        assertFalse(MarketBettingDateFilter.matches(market, "2026-04-28"))
    }

    @Test
    fun `keeps only markets that belong to the selected event slug`() {
        val selectedEvent = GammaSearchEventItem(
            slug = "nba-okc-phx-2026-04-27",
            title = "Thunder vs. Suns"
        )
        val sameEventMarket = GammaEventMarketItem(slug = "nba-okc-phx-2026-04-27-luquentz-dort-points")
        val otherEventMarket = GammaEventMarketItem(slug = "nba-hou-lal-2026-04-27-jalen-green-points")
        val exactTitleFallbackMarket = GammaEventMarketItem(slug = "thunder-vs-suns", question = "Thunder vs. Suns")

        assertTrue(MarketBettingMarketFilter.belongsToEvent(sameEventMarket, selectedEvent))
        assertTrue(MarketBettingMarketFilter.belongsToEvent(exactTitleFallbackMarket, selectedEvent))
        assertFalse(MarketBettingMarketFilter.belongsToEvent(otherEventMarket, selectedEvent))
    }

    @Test
    fun `keeps only main game markets by default`() {
        assertTrue(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "moneyline")))
        assertTrue(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "spreads")))
        assertTrue(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "totals")))

        assertFalse(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "points")))
        assertFalse(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "rebounds")))
        assertFalse(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "assists")))
        assertFalse(MarketBettingMarketFilter.isMainGameMarket(GammaEventMarketItem(sportsMarketType = "first_half_totals")))
    }

    @Test
    fun `falls back to event markets when selected event has no main game markets`() {
        val selectedEvent = GammaSearchEventItem(
            slug = "2026-fifa-world-cup-winner-595",
            title = "2026 FIFA World Cup Winner"
        )
        val winnerMarket = GammaEventMarketItem(
            slug = "2026-fifa-world-cup-winner-595-brazil",
            conditionId = "0x1",
            sportsMarketType = "winner"
        )
        val unrelatedMarket = GammaEventMarketItem(
            slug = "nba-lal-hou-2026-05-01",
            conditionId = "0x2",
            sportsMarketType = "moneyline"
        )

        val visibleMarkets = MarketBettingMarketFilter.selectVisibleMarkets(
            markets = listOf(winnerMarket, unrelatedMarket),
            event = selectedEvent,
            date = null
        )

        assertEquals(listOf(winnerMarket), visibleMarkets)
    }
}
