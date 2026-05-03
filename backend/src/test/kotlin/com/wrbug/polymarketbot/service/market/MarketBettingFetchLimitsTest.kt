package com.wrbug.polymarketbot.service.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketBettingFetchLimitsTest {

    @Test
    fun `caps detail market fetches to keep manual query responsive`() {
        assertEquals(40, MarketBettingFetchLimits.coerceMarketLimit(100))
        assertEquals(1, MarketBettingFetchLimits.coerceMarketLimit(0))
        assertEquals(20, MarketBettingFetchLimits.coerceMarketLimit(20))
    }

    @Test
    fun `limits nested polymarket request fan out`() {
        assertTrue(MarketBettingFetchLimits.MARKET_DETAIL_CONCURRENCY <= 8)
        assertTrue(MarketBettingFetchLimits.ORDERBOOK_CONCURRENCY <= 8)
        assertTrue(MarketBettingFetchLimits.MAX_TRADE_PAGES_PER_MARKET <= 2)
    }
}
